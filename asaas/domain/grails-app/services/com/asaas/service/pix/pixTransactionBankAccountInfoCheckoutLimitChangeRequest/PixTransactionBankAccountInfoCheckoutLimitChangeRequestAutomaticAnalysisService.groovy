package com.asaas.service.pix.pixTransactionBankAccountInfoCheckoutLimitChangeRequest

import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequest
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionBankAccountInfoCheckoutLimitChangeRequestAutomaticAnalysisService {

    def pixTransactionBankAccountInfoCheckoutLimitChangeRequestService

    public void approveLowRisk() {
        List<Long> pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.requestedLowRisk([column: "id"]).list(max: 500)
        approvePendingRequests(pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList)
    }

    public void approveMediumRisk() {
        List<Long> pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.requestedMediumRiskAllowsToApproval([column: "id"]).list(max: 500)
        approvePendingRequests(pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList)
    }

    public void processExpired() {
        List<Long> pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.requestedMediumRiskAllowsToApproval([column: "id", "dateCreated[le]": CustomDateUtils.sumHours(new Date(), PixTransactionCheckoutLimitChangeRequest.MAXIMUM_HOURS_FOR_ANALYSIS * -1)]).list(max: 500)
        denyPendingRequests(pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList)
    }

    private void approvePendingRequests(List<Long> pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList) {
        for (Long id : pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.get(id)

                pixTransactionBankAccountInfoCheckoutLimitChangeRequest = pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.approveAutomatically(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
                if (pixTransactionBankAccountInfoCheckoutLimitChangeRequest.hasErrors()) {
                    pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.denyAutomatically(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, DomainUtils.getFirstValidationMessage(pixTransactionBankAccountInfoCheckoutLimitChangeRequest))
                }
            }, [
                logErrorMessage: "${this.getClass().getSimpleName()}.approvePendingRequests >> Erro ao tentar aprovar automaticamente a solicitação [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${id}]",
                onError: {
                    setAsErrorWithNewTransaction(id)
                }
            ])
        }
    }

    private void denyPendingRequests(List<Long> pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList) {
        for (Long id : pixTransactionBankAccountInfoCheckoutLimitChangeRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.get(id)

                pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.denyAutomatically(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, "Solicitação expirada")
            }, [
                logErrorMessage: "${this.getClass().getSimpleName()}.denyPendingRequests >> Erro ao tentar reprovar automaticamente a solicitação [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${id}]",
                onError: {
                    setAsErrorWithNewTransaction(id)
                }
            ])
        }
    }

    private void setAsErrorWithNewTransaction(Long pixTransactionBankAccountInfoCheckoutLimitChangeRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.get(pixTransactionBankAccountInfoCheckoutLimitChangeRequestId)

            pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.setAsError(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
        }, [logErrorMessage: "${this.getClass().getSimpleName()}.setAsErrorWithNewTransaction >> Erro ao tentar atualizar status para error [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${pixTransactionBankAccountInfoCheckoutLimitChangeRequestId}]"])
    }
}
