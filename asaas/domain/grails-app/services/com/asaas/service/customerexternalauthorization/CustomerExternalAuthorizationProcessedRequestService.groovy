package com.asaas.service.customerexternalauthorization

import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationProcessedRequestRefusalReason
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationProcessedRequestStatus
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationProcessedRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestBill
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestMobilePhoneRecharge
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestPixQrCode
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestTransfer
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerExternalAuthorizationProcessedRequestService {

    def customerExternalAuthorizationRequestBillService
    def customerExternalAuthorizationRequestMobilePhoneRechargeService
    def customerExternalAuthorizationRequestPixQrCodeService
    def customerExternalAuthorizationRequestTransferService

    public void save(Long externalAuthorizationRequestId, Boolean isApproved, String refusalDescription, CustomerExternalAuthorizationProcessedRequestRefusalReason refusalReason) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.read(externalAuthorizationRequestId)

        CustomerExternalAuthorizationProcessedRequest externalAuthorizationProcessedRequest = new CustomerExternalAuthorizationProcessedRequest()
        externalAuthorizationProcessedRequest.externalAuthorizationRequest = externalAuthorizationRequest
        externalAuthorizationProcessedRequest.approved = isApproved
        externalAuthorizationProcessedRequest.customer = externalAuthorizationRequest.config.customer
        externalAuthorizationProcessedRequest.refusalDescription = refusalDescription
        externalAuthorizationProcessedRequest.refusalReason = refusalReason
        externalAuthorizationProcessedRequest.save(failOnError: true)
    }

    public void consumeQueue() {
        List<Long> customerWithPendingProcessedRequestIdList = CustomerExternalAuthorizationProcessedRequest.pending([distinct: "customer.id", disableSort: true]).list()
        if (!customerWithPendingProcessedRequestIdList) return

        Utils.processWithThreads(customerWithPendingProcessedRequestIdList, 4, { List<Long> customerIdList ->
            Utils.forEachWithFlushSession(customerIdList, 25, { Long customerId ->
                List<Long> processedRequestIdList = CustomerExternalAuthorizationProcessedRequest.pending([customerId: customerId, column: "id", order: "asc"]).list(max: 10)

                for (Long externalAuthorizationProcessedRequestId : processedRequestIdList) {
                    processQueueItem(externalAuthorizationProcessedRequestId)
                }
            })
        })
    }

    private void processQueueItem(Long externalAuthorizationProcessedRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            CustomerExternalAuthorizationProcessedRequest externalAuthorizationProcessedRequest = CustomerExternalAuthorizationProcessedRequest.get(externalAuthorizationProcessedRequestId)
            CustomerExternalAuthorizationRequest externalAuthorizationRequest = externalAuthorizationProcessedRequest.externalAuthorizationRequest

            if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.TRANSFER) {
                CustomerExternalAuthorizationRequestTransfer externalAuthorizationTransfer = CustomerExternalAuthorizationRequestTransfer.query([externalAuthorizationRequest: externalAuthorizationRequest]).get()

                BusinessValidation validatedBusiness = customerExternalAuthorizationRequestTransferService.validateStatus(externalAuthorizationTransfer)
                if (!validatedBusiness.isValid()) {
                    setRefusalReason(externalAuthorizationProcessedRequestId, CustomerExternalAuthorizationProcessedRequestRefusalReason.INVALID_STATUS, validatedBusiness.getFirstErrorMessage())
                    return
                }

                if (externalAuthorizationProcessedRequest.approved) {
                    customerExternalAuthorizationRequestTransferService.approve(externalAuthorizationTransfer)
                } else {
                    customerExternalAuthorizationRequestTransferService.refuse(externalAuthorizationTransfer)
                }
            }

            if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.BILL) {
                CustomerExternalAuthorizationRequestBill externalAuthorizationBill = CustomerExternalAuthorizationRequestBill.query([externalAuthorizationRequest: externalAuthorizationRequest]).get()

                if (externalAuthorizationProcessedRequest.approved) {
                    customerExternalAuthorizationRequestBillService.approve(externalAuthorizationBill)
                } else {
                    customerExternalAuthorizationRequestBillService.refuse(externalAuthorizationBill)
                }
            }

            if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.MOBILE_PHONE_RECHARGE) {
                CustomerExternalAuthorizationRequestMobilePhoneRecharge externalAuthorizationMobilePhoneRecharge = CustomerExternalAuthorizationRequestMobilePhoneRecharge.query([externalAuthorizationRequest: externalAuthorizationRequest]).get()

                if (externalAuthorizationProcessedRequest.approved) {
                    customerExternalAuthorizationRequestMobilePhoneRechargeService.approve(externalAuthorizationMobilePhoneRecharge)
                } else {
                    customerExternalAuthorizationRequestMobilePhoneRechargeService.refuse(externalAuthorizationMobilePhoneRecharge)
                }
            }

            if (externalAuthorizationRequest.config.type == CustomerExternalAuthorizationRequestConfigType.PIX_QR_CODE) {
                CustomerExternalAuthorizationRequestPixQrCode externalAuthorizationPixQrCode = CustomerExternalAuthorizationRequestPixQrCode.query([externalAuthorizationRequest: externalAuthorizationRequest]).get()

                BusinessValidation validatedBusiness = customerExternalAuthorizationRequestPixQrCodeService.validateStatus(externalAuthorizationPixQrCode)
                if (!validatedBusiness.isValid()) {
                    setRefusalReason(externalAuthorizationProcessedRequestId, CustomerExternalAuthorizationProcessedRequestRefusalReason.INVALID_STATUS, validatedBusiness.getFirstErrorMessage())
                    return
                }

                if (externalAuthorizationProcessedRequest.approved) {
                    customerExternalAuthorizationRequestPixQrCodeService.approve(externalAuthorizationPixQrCode)
                } else {
                    customerExternalAuthorizationRequestPixQrCodeService.refuse(externalAuthorizationPixQrCode)
                }
            }

            setAsProcessed(externalAuthorizationProcessedRequestId)
        }, [onError: { Exception e ->
            AsaasLogger.error("CustomerExternalAuthorizationProcessedRequestService.processQueueItem [${externalAuthorizationProcessedRequestId}]", e)

            Utils.withNewTransactionAndRollbackOnError({
                setAsFailed(externalAuthorizationProcessedRequestId)
            })
        }])
    }

    private void setAsProcessed(Long externalAuthorizationProcessedRequestId) {
        CustomerExternalAuthorizationProcessedRequest externalAuthorizationProcessedRequest = CustomerExternalAuthorizationProcessedRequest.get(externalAuthorizationProcessedRequestId)
        externalAuthorizationProcessedRequest.status = CustomerExternalAuthorizationProcessedRequestStatus.PROCESSED
        externalAuthorizationProcessedRequest.save(failOnError: true)
    }

    private void setAsFailed(Long externalAuthorizationProcessedRequestId) {
        CustomerExternalAuthorizationProcessedRequest externalAuthorizationProcessedRequest = CustomerExternalAuthorizationProcessedRequest.get(externalAuthorizationProcessedRequestId)
        externalAuthorizationProcessedRequest.status = CustomerExternalAuthorizationProcessedRequestStatus.FAILED
        externalAuthorizationProcessedRequest.save(failOnError: true)
    }

    private void setRefusalReason(Long externalAuthorizationProcessedRequestId, CustomerExternalAuthorizationProcessedRequestRefusalReason refusalReason, String refusalDescription) {
        CustomerExternalAuthorizationProcessedRequest externalAuthorizationProcessedRequest = CustomerExternalAuthorizationProcessedRequest.get(externalAuthorizationProcessedRequestId)
        externalAuthorizationProcessedRequest.status = CustomerExternalAuthorizationProcessedRequestStatus.PROCESSED
        externalAuthorizationProcessedRequest.refusalReason = refusalReason
        externalAuthorizationProcessedRequest.refusalDescription = refusalDescription
        externalAuthorizationProcessedRequest.save(failOnError: true)
    }
}
