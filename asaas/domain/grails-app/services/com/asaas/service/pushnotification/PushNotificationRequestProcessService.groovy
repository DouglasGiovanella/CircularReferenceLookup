package com.asaas.service.pushnotification

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiBillParser
import com.asaas.api.ApiCustomerInvoiceParser
import com.asaas.api.ApiMyAccountParser
import com.asaas.api.ApiReceivableAnticipationParser
import com.asaas.api.ApiTransferParser
import com.asaas.domain.bill.Bill
import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.domain.pushnotification.PushNotificationRequestBill
import com.asaas.domain.pushnotification.PushNotificationRequestMobilePhoneRechargeEvent
import com.asaas.domain.pushnotification.PushNotificationRequestPaymentEvent
import com.asaas.domain.pushnotification.PushNotificationRequestReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.transfer.Transfer
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.pushnotification.PushNotificationUtils
import com.asaas.pushnotification.cache.PushNotificationConfigVO
import com.asaas.utils.Utils

import grails.transaction.Transactional

import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import groovyx.net.http.ContentType

import org.springframework.util.StopWatch

import wslite.http.HTTPClient
import wslite.http.HTTPClientException
import wslite.rest.RESTClient

@Transactional
class PushNotificationRequestProcessService {

    private static final Integer TOTAL_REQUESTS_MAXIMUM_TIME = 30000
    private static final Integer REQUEST_TIMEOUT = 8000

    def pushNotificationConfigCacheService
    def pushNotificationConfigWithPendingRequestCacheService
    def pushNotificationRequestAttemptService
    def pushNotificationRequestService

    public String convertToJson(Integer apiVersion, Map data) {
        if (apiVersion >= 3) return JsonOutput.toJson(data)

        return new JsonBuilder(data).toPrettyString()
    }

    public String ensureHasProtocol(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "http://" + url
        }

        return url
    }

    public void process(Long configId, Integer limit) {
        PushNotificationConfigVO pushNotificationConfig = pushNotificationConfigCacheService.get(configId)
        List<Long> pushNotificationRequestIdList = PushNotificationRequest.notSent([configId: configId, column: "id", order: "asc"]).list(max: limit)

        if (PushNotificationUtils.existsConfigIdRepeteadInList(pushNotificationRequestIdList)) {
            AsaasLogger.warn("PushNotificationRequestProcessService.process >> Existem elementos repetidos na lista pushNotificationRequestIdList")
        }

        process(pushNotificationConfig, pushNotificationRequestIdList)
    }

    public void process(PushNotificationConfigVO pushNotificationConfig, List<Long> pushNotificationRequestIdList) {
        String url = ensureHasProtocol(pushNotificationConfig.url)
        Integer apiVersion = pushNotificationConfig.apiVersion

        Boolean canContinueOnError = !pushNotificationConfig.sendType.isSequentially()

        StopWatch eventsListProcessingStopWatch = new StopWatch()
        for (Long pushNotificationRequestId : pushNotificationRequestIdList) {
            eventsListProcessingStopWatch.start()

            Boolean mustStopTheQueue = false

            String data
            Map response

            StringBuilder logMessage = new StringBuilder("Iniciando envio de PushNotification. id: [${pushNotificationRequestId}] pushNotificationConfigId: [${pushNotificationConfig.id}]. ")
            try {
                Utils.withNewTransactionAndRollbackOnError({
                    if (eventsListProcessingStopWatch.totalTimeMillis > TOTAL_REQUESTS_MAXIMUM_TIME) {
                        AsaasLogger.warn("PushNotificationRequestProcessService.process >> Tempo total para receber requisições excedido. pushNotificationRequestId: [${pushNotificationRequestId}] pushNotificationConfigId: [${pushNotificationConfig.id}]")
                        mustStopTheQueue = true
                        return
                    }

                    data = buildJsonStringToSend(pushNotificationRequestId, apiVersion)
                    response = doPost(url, data, apiVersion, pushNotificationConfig.accessToken, pushNotificationConfig.bypassSslValidations, pushNotificationRequestId)
                    logMessage.append("Notificação enviada. status: [${response.status}]. ")

                    PushNotificationRequestAttempt pushNotificationRequestAttempt = pushNotificationRequestAttemptService.save(pushNotificationRequestId, response.timeToProcessRequestInMilliseconds, response.status, response.receivedData, url, response.errorDetail)

                    if (pushNotificationRequestAttempt) {
                        logMessage.append("Tentativa salva. PushNotificationRequestAttemptId: [${pushNotificationRequestAttempt.id}]. ")
                    } else {
                        logMessage.append("Erro ao salvar tentativa de request. ")
                    }

                    Boolean postSucceeded = isSuccessfulResponse(response, pushNotificationConfig.bypassResponseStringValidation, apiVersion)
                    if (postSucceeded) {
                        pushNotificationRequestService.setAsSent(pushNotificationRequestId, data, url)
                        logMessage.append("Notificação atualizada para enviada. ")
                    } else {
                        pushNotificationRequestService.setAsFailed(pushNotificationRequestId, data, url)
                        logMessage.append("Notificação atualizada para falha. ")
                        if (!canContinueOnError) {
                            mustStopTheQueue = true
                        }
                    }

                }, [onError: { exception -> throw exception }, ignoreStackTrace: true])
            } catch (Exception e) {
                logMessage.append("Erro ao enviar PushNotification.")
                AsaasLogger.error("PushNotificationRequestProcessService.process >> Erro ao enviar PushNotification. id: [${pushNotificationRequestId}] pushNotificationConfigId: [${pushNotificationConfig.id}]", e)

                Utils.withNewTransactionAndRollbackOnError({
                    PushNotificationRequestAttempt pushNotificationRequestAttempt = pushNotificationRequestAttemptService.save(pushNotificationRequestId, response?.timeToProcessRequestInMilliseconds, response?.status, response?.receivedData, url, response?.errorDetail)
                    if (pushNotificationRequestAttempt) {
                        logMessage.append("Tentativa salva. PushNotificationRequestAttemptId: [${pushNotificationRequestAttempt.id}]. ")
                    } else {
                        logMessage.append("Erro ao salvar tentativa de request. ")
                    }
                }, [onError: { Exception ex ->
                    AsaasLogger.error("PushNotificationRequestProcessService.process >> Erro ao salvar attempt pushNotificationRequestId: [${pushNotificationRequestId}] pushNotificationConfigId: [${pushNotificationConfig.id}]", ex)
                }, ignoreStackTrace: true])

                Utils.withNewTransactionAndRollbackOnError({
                    pushNotificationRequestService.setAsFailed(pushNotificationRequestId, data, url)
                    logMessage.append("Notificação atualizada como falha. ")
                }, [onError: { Exception ex ->
                    AsaasLogger.error("PushNotificationRequestProcessService.process >> Erro ao marcar como falha pushNotificationRequestId: [${pushNotificationRequestId}] pushNotificationConfigId: [${pushNotificationConfig.id}]", ex)
                }, ignoreStackTrace: true])

                if (!canContinueOnError) {
                    mustStopTheQueue = true
                }

                AsaasLogger.info("PushNotificationRequestProcessService.process >> Mensagem construída na tentativa de envio da notificação: ${logMessage.toString()}")
            } finally {
                eventsListProcessingStopWatch.stop()
                if (mustStopTheQueue) break
            }
        }

        Utils.withNewTransactionAndRollbackOnError({
            Boolean hasMorePendingPushNotificationRequest = PushNotificationRequest.notSent([configId: pushNotificationConfig.id, exists: true]).get().asBoolean()
            if (hasMorePendingPushNotificationRequest) {
                pushNotificationConfigWithPendingRequestCacheService.resetTtl(pushNotificationConfig.id)
            } else {
                pushNotificationConfigWithPendingRequestCacheService.decreaseTtl(pushNotificationConfig.id)
            }
        })
    }

    private Map doPost(String url, data, Integer apiVersion, String accessToken, Boolean certificateValidation, Long pushNotificationId) {
        Map requestReturn = [:]
        StopWatch stopWatch = new StopWatch()
        Date sentDate = new Date()

        try {
            HTTPClient httpClient = new HTTPClient()
            httpClient.connectTimeout = REQUEST_TIMEOUT
            httpClient.readTimeout  = REQUEST_TIMEOUT

            def client = new RESTClient(url, httpClient)

            Map headersMap = [:]
            if (apiVersion >= 3) {
                headersMap = ["asaas-access-token": accessToken, "Content-Type": "application/json", "Accept": "application/json"]
            }
            stopWatch.start()
            def apiResponse = client.post(sslTrustAllCerts: certificateValidation, headers: headersMap) {
                if (apiVersion >= 3) {
                    type ContentType.JSON
                    text data
                } else {
                    type: ContentType.TEXT
                    urlenc data: data, accessToken: accessToken
                }
            }
            stopWatch.stop()

            requestReturn.status = apiResponse.statusCode
            requestReturn.receivedData = apiResponse.text
            requestReturn.timeToProcessRequestInMilliseconds = stopWatch.totalTimeMillis
            requestReturn.sentDate = sentDate
        } catch (HTTPClientException e) {
            requestReturn.status = e.getResponse()?.getStatusCode()

            if (e.getCause() instanceof UnknownHostException) {
                requestReturn.errorDetail = "Unknown Host: ${e.getCause().getMessage()}"
            } else {
                requestReturn.errorDetail = e.getCause()?.getMessage()
            }
        } catch (Throwable throwable) {
            AsaasLogger.error("PushNotificationRequestProcessService.doPost - Falha ao realizar o envio do payload. id: [${pushNotificationId}]", throwable)
        } catch (Exception exception) {
            AsaasLogger.error("PushNotificationRequestProcessService.doPost - Falha ao realizar o envio do payload. id: [${pushNotificationId}]", exception)
        }

        return requestReturn
    }

    private String buildJsonStringToSend(Long pushNotificationRequestId, Integer apiVersion) {
        Map requestMap = PushNotificationRequest.query([id: pushNotificationRequestId, columnList: ["event", "dateCreated"]]).get()
        PushNotificationRequestEvent event = requestMap.event

        Map data = [
            id: "evt_${event.toString().encodeAsMD5()}&${pushNotificationRequestId}",
            event: event,
            dateCreated: ApiBaseParser.formatDateWithTime(requestMap.dateCreated, apiVersion)
        ]

        if (event.isPayment()) {
            String paymentData = PushNotificationRequestPaymentEvent.query([pushNotificationRequestId: pushNotificationRequestId, column: "data"]).get()
            data += new JsonSlurper().parseText(paymentData)
        }

        if (event.isMobilePhoneRecharge()) {
            String mobilePhoneRechargeData = PushNotificationRequestMobilePhoneRechargeEvent.query([pushNotificationRequestId: pushNotificationRequestId, column: "data"]).get()
            data += new JsonSlurper().parseText(mobilePhoneRechargeData)
        }

        if (event.isAccountStatus()) {
            Customer customer = PushNotificationRequest.query([id: pushNotificationRequestId, column: "provider"]).get()
            data.accountStatus = ApiMyAccountParser.buildAccountStatusResponseMap(customer)
        }

        if (event.isBill()) {
            Bill bill = PushNotificationRequestBill.query([column: "bill", pushNotificationRequestId: pushNotificationRequestId]).get()
            data.bill = ApiBillParser.buildResponseItem(bill, [apiVersion: apiVersion])
        }

        if (event.isReceivableAnticipation()) {
            ReceivableAnticipation receivableAnticipation = PushNotificationRequestReceivableAnticipation.query([column: "receivableAnticipation", pushNotificationRequestId: pushNotificationRequestId]).get()
            data.anticipation = ApiReceivableAnticipationParser.buildResponseItem(receivableAnticipation, [apiVersion: apiVersion])
        }

        if (event.isInvoice()) {
            Invoice invoice = PushNotificationRequest.query([id: pushNotificationRequestId, column: "invoice"]).get()
            data.invoice = ApiCustomerInvoiceParser.buildResponseItem(invoice, [apiVersion: apiVersion])
        }

        if (event.isTransfer()) {
            Long transferId = PushNotificationRequest.query([id: pushNotificationRequestId, column: "transferId"]).get()
            data.transfer = ApiTransferParser.buildResponseItem(Transfer.read(transferId), [apiVersion: apiVersion])
        }

        return convertToJson(apiVersion, data)
    }

    private Boolean isSuccessfulResponse(response, Boolean bypassResponseStringValidation, Integer apiVersion) {
        return (response.receivedData?.toUpperCase()?.trim() == "SUCCESS" || bypassResponseStringValidation || apiVersion >= 3) && response.status == 200
    }
}
