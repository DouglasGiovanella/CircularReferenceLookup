package com.asaas.service.credittransferrequest

import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.transfer.Transfer
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class ScheduledCreditTransferRequestProcessService {

    def creditTransferRequestAdminService
    def creditTransferRequestService
    def customerAlertNotificationService
    def mobilePushNotificationService
    def transferService

    public void process() {
        List<Long> scheduledCreditTransferRequestIdList = CreditTransferRequest.query([column: "id", scheduledDate: new Date().clearTime(), "status": CreditTransferRequestStatus.SCHEDULED, awaitingCriticalActionAuthorization: false]).list(max: 100)

        for (Long scheduledCreditTransferRequestId : scheduledCreditTransferRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(scheduledCreditTransferRequestId)

                processScheduledCreditTransferRequest(creditTransferRequest)
            }, [logErrorMessage: "ScheduledCreditTransferRequestProcessService.process -> Erro ao processar CreditTransferRequest agendado. [${scheduledCreditTransferRequestId}]"])
        }
    }

    public void processCancellation() {
        List<Long> scheduledCreditTransferRequestNotAuthorizedIdList = CreditTransferRequest.query([column: "id", scheduledDate: new Date().clearTime(), status: CreditTransferRequestStatus.SCHEDULED, awaitingCriticalActionAuthorization: true]).list(max: 50)
        scheduledCreditTransferRequestNotAuthorizedIdList.addAll(CreditTransferRequest.query([column: "id", scheduledDate: new Date().clearTime(), status: CreditTransferRequestStatus.AWAITING_EXTERNAL_AUTHORIZATION]).list(max: 50))

        for (Long scheduledCreditTransferRequestNotAuthorizedId : scheduledCreditTransferRequestNotAuthorizedIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(scheduledCreditTransferRequestNotAuthorizedId)

                String refusalReasonDescription = "Transferência não foi autorizada até o horário limite"
                cancel(creditTransferRequest, refusalReasonDescription)
            }, [logErrorMessage: "ScheduledCreditTransferRequestProcessService.processCancellation -> Erro ao processar cancelamento CreditTransferRequest agendado. [creditTransferRequest.id: ${scheduledCreditTransferRequestNotAuthorizedId}]"])
        }
    }

    public void processScheduledCreditTransferRequest(CreditTransferRequest creditTransferRequest) {
        AsaasError asaasError = validateProcessing(creditTransferRequest)
        if (asaasError) {
            cancel(creditTransferRequest, asaasError.getMessage())
            return
        }

        Map transferFeeMap = creditTransferRequestService.calculateTransferFee(creditTransferRequest.provider, creditTransferRequest.bankAccountInfo, creditTransferRequest.value)
        BigDecimal transferFee = 0
        if (transferFeeMap.fee) transferFee = transferFeeMap.fee
        creditTransferRequest.transferFee = transferFee

        creditTransferRequestAdminService.authorize(creditTransferRequest)
        creditTransferRequestService.processCurrentDayTransfer(creditTransferRequest)
    }

    private AsaasError validateProcessing(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.awaitingCriticalActionAuthorization) return new AsaasError("transfer.denied.creditTransferRequest.scheduledProcess.awaitingCriticalAction")

        AsaasError asaasError = creditTransferRequestService.getDenialReasonIfExists(creditTransferRequest.provider, creditTransferRequest.bankAccountInfo.id, creditTransferRequest.value, [:])
        if (asaasError) return asaasError

        return null
    }

    private void cancel(CreditTransferRequest creditTransferRequest, String refusalReasonDescription) {
        creditTransferRequest.observations = "Cancelamento efetuado pela validação ao processar agendamento. Motivo: ${refusalReasonDescription}"
        creditTransferRequest.status = CreditTransferRequestStatus.CANCELLED

        creditTransferRequestService.saveCancelledOrFailedTransfer(creditTransferRequest, false)
        transferService.setAsCancelled(creditTransferRequest.transfer)

        String transferPublicId = Transfer.query([column: 'publicId', creditTransferRequest: creditTransferRequest]).get()
        customerAlertNotificationService.notifyScheduledCreditTransferRequestProcessingRefused(creditTransferRequest, transferPublicId)
        mobilePushNotificationService.notifyScheduledCreditTransferRequestProcessingRefused(creditTransferRequest, transferPublicId)
    }
}
