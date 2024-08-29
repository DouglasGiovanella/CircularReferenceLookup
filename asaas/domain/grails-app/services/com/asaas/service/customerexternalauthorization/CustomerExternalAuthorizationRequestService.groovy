package com.asaas.service.customerexternalauthorization

import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationProcessedRequestRefusalReason
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestStatus
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestAttempt
import com.asaas.http.HttpRequestStatusFilter
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import groovyx.net.http.ContentType

import org.springframework.util.StopWatch

import wslite.http.HTTPClient
import wslite.http.HTTPClientException
import wslite.rest.RESTClient

@Transactional
class CustomerExternalAuthorizationRequestService {

    private static final Integer TOTAL_REQUESTS_MAXIMUM_TIME = 50000

    def customerExternalAuthorizationRequestAttemptService
    def customerExternalAuthorizationRequestBillService
    def customerExternalAuthorizationRequestMobilePhoneRechargeService
    def customerExternalAuthorizationRequestPixQrCodeService
    def customerExternalAuthorizationRequestTransferService
    def customerExternalAuthorizationProcessedRequestService
    def customerExternalAuthorizationRequestMessageService

    public void consumeQueue() {
        List<Long> configIdList = CustomerExternalAuthorizationRequest.configReadyToProcess().list(max: 50)
        consumePendingQueue(configIdList)
    }

    private void consumePendingQueue(List<Long> configIdList) {
        Utils.processWithThreads(configIdList, 4, { List<Long> threadConfigIdList ->
            Utils.forEachWithFlushSession(threadConfigIdList, 50, { Long configId ->
                Date dateCreated = CustomDateUtils.sumSeconds(new Date(), CustomerExternalAuthorizationRequest.SECONDS_AFTER_CREATION)
                List<Long> externalAuthorizationRequestIdList = CustomerExternalAuthorizationRequest.pending([column: 'id', configId: configId, order: 'asc', "dateCreated[le]": dateCreated]).list(max: 200)
                processQueueItems(externalAuthorizationRequestIdList)
            })
        })
    }

    private void processQueueItems(List<Long> externalAuthorizationRequestIdList) {
        StopWatch stopWatch = new StopWatch()

        for (Long externalAuthorizationRequestId : externalAuthorizationRequestIdList) {
            stopWatch.start()

            if (stopWatch.totalTimeMillis > TOTAL_REQUESTS_MAXIMUM_TIME) {
                AsaasLogger.warn("CustomerExternalAuthorizationRequestService.processQueueItems >> Tempo total para receber requisições excedido. externalAuthorizationRequestId: [${externalAuthorizationRequestId}]")
                break
            }

            Utils.withNewTransactionAndRollbackOnError({
                increaseAttempt(externalAuthorizationRequestId)

                try {
                    CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.read(externalAuthorizationRequestId)

                    Map requestData = buildRequestData(externalAuthorizationRequest)
                    Map response = doPost(externalAuthorizationRequest.config.url, externalAuthorizationRequest.config.getDecryptedAccessToken(), requestData, externalAuthorizationRequestId)
                    CustomerExternalAuthorizationRequestAttempt attempt = saveAttempt(externalAuthorizationRequestId, response.status, requestData, response.responseData, response.errorMessage)

                    processRequestResult(externalAuthorizationRequestId, response)
                    sendEmailIfNecessary(externalAuthorizationRequestId, attempt)
                } catch (Exception e) {
                    AsaasLogger.error("CustomerExternalAuthorizationRequestService.processQueueItems [${externalAuthorizationRequestId}]", e)
                } finally {
                    stopWatch.stop()
                }
            })
        }
    }

    private void processRequestResult(Long externalAuthorizationRequestId, Map response) {
        Boolean isSuccessfulResponse = response?.status == 200
        if (isSuccessfulResponse) {
            Map receivedData = getResponseDataMap(response, externalAuthorizationRequestId)
            if (!receivedData) {
                setAsFailed(externalAuthorizationRequestId)
                customerExternalAuthorizationProcessedRequestService.save(externalAuthorizationRequestId, false, null, CustomerExternalAuthorizationProcessedRequestRefusalReason.INVALID_DATA)
                return
            }

            Boolean isApproved = receivedData.status == "APPROVED"
            CustomerExternalAuthorizationProcessedRequestRefusalReason refusalReason
            if (!isApproved) {
                refusalReason = CustomerExternalAuthorizationProcessedRequestRefusalReason.CUSTOMER_REFUSED
            }

            setAsProcessed(externalAuthorizationRequestId)
            customerExternalAuthorizationProcessedRequestService.save(externalAuthorizationRequestId, isApproved, receivedData.refusalDescription, refusalReason)
        } else {
            CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.read(externalAuthorizationRequestId)

            if (externalAuthorizationRequest.attempts >= CustomerExternalAuthorizationRequest.MAX_ATTEMPTS) {
                setAsFailed(externalAuthorizationRequestId)
                customerExternalAuthorizationProcessedRequestService.save(externalAuthorizationRequestId, false, null, CustomerExternalAuthorizationProcessedRequestRefusalReason.REQUEST_ERROR)
            }
        }
    }

    private Map doPost(String url, String accessToken, Map data, Long externalAuthorizationRequestId) {
        try {
            HTTPClient httpClient = new HTTPClient()
            httpClient.connectTimeout = 10000
            httpClient.readTimeout = 10000

            RESTClient client = new RESTClient(url, httpClient)

            Map headersMap = ["Content-Type": "application/json", "Accept": "application/json"]
            if (accessToken) headersMap."asaas-access-token" = accessToken

            def response = client.post(headers: headersMap) {
                type ContentType.JSON
                text JsonOutput.toJson(data)
            }

            return [status: response.statusCode, responseData: response.text]
        } catch (HTTPClientException exception) {
            if (!exception.getResponse()?.getStatusCode()) {
                if (exception.getCause()?.getMessage() == "Read timed out") {
                    return [status: HttpRequestStatusFilter.REQUEST_TIMEOUT.value, errorMessage: exception.getCause().getMessage()]
                }
            }
            return [status: exception.getResponse()?.getStatusCode(), errorMessage: exception.getCause()?.getMessage()]
        } catch (Throwable throwable) {
            AsaasLogger.error("CustomerExternalAuthorizationRequestService.doPost >> Falha ao realizar o envio do payload. id: [${externalAuthorizationRequestId}]", throwable)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerExternalAuthorizationRequestService.doPost >> Falha ao realizar o envio do payload. id: [${externalAuthorizationRequestId}]", exception)
        }

        return [:]
    }

    private Map buildRequestData(CustomerExternalAuthorizationRequest externalAuthorizationRequest) {
        Map requestData = [type: externalAuthorizationRequest.config.type.toString()]

        if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.TRANSFER) {
            requestData += customerExternalAuthorizationRequestTransferService.buildRequestData(externalAuthorizationRequest.id)
        }

        if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.BILL) {
            requestData += customerExternalAuthorizationRequestBillService.buildRequestData(externalAuthorizationRequest.id)
        }

        if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.MOBILE_PHONE_RECHARGE) {
            requestData += customerExternalAuthorizationRequestMobilePhoneRechargeService.buildRequestData(externalAuthorizationRequest.id)
        }

        if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.PIX_QR_CODE) {
            requestData += customerExternalAuthorizationRequestPixQrCodeService.buildRequestData(externalAuthorizationRequest.id)
        }

        return requestData
    }

    private void increaseAttempt(Long externalAuthorizationRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.get(externalAuthorizationRequestId)
            externalAuthorizationRequest.attempts += 1
            externalAuthorizationRequest.save(failOnError: true)
        })
    }

    private void setAsProcessed(Long externalAuthorizationRequestId) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.get(externalAuthorizationRequestId)
        externalAuthorizationRequest.status = CustomerExternalAuthorizationRequestStatus.PROCESSED
        externalAuthorizationRequest.save(flush: true, failOnError: true)
    }

    private void setAsFailed(Long externalAuthorizationRequestId) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.get(externalAuthorizationRequestId)
        externalAuthorizationRequest.status = CustomerExternalAuthorizationRequestStatus.FAILED
        externalAuthorizationRequest.save(flush: true, failOnError: true)
    }

    private CustomerExternalAuthorizationRequestAttempt saveAttempt(Long externalAuthorizationRequestId, Integer status, Map requestData, String responseData, String errorMessage) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.read(externalAuthorizationRequestId)
        return customerExternalAuthorizationRequestAttemptService.save(externalAuthorizationRequest.id, status, JsonOutput.toJson(requestData), responseData, externalAuthorizationRequest.config.url, errorMessage)
    }

    private Map getResponseDataMap(Map response, Long externalAuthorizationRequestId) {
        try {
            if (!response?.responseData) return null

            Map responseData = new JsonSlurper().parseText(response.responseData)
            if (!["APPROVED", "REFUSED"].contains(responseData?.status)) return null

            return responseData
        } catch (Exception e) {
            AsaasLogger.error("CustomerExternalAuthorizationRequestService.isSuccessfulResponse -> [CustomerExternalAuthorizationRequestId: ${externalAuthorizationRequestId}] [Response: ${response}]", e)
            return null
        }
    }

    private sendEmailIfNecessary(Long externalAuthorizationRequestId, CustomerExternalAuthorizationRequestAttempt attempt) {
        CustomerExternalAuthorizationRequest request = CustomerExternalAuthorizationRequest.read(externalAuthorizationRequestId)
        Boolean shouldSendMaxAttemptsExceededEmail = request.attempts >= CustomerExternalAuthorizationRequest.MAX_ATTEMPTS && request.status.isFailed()
        if (shouldSendMaxAttemptsExceededEmail) {
            customerExternalAuthorizationRequestMessageService.sendMaxAttemptsExceededMessage(request.config, attempt)
        }
    }
}
