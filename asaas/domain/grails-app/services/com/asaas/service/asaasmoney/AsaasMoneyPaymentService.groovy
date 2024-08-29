package com.asaas.service.asaasmoney

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasMoneyPaymentService {

    def asyncActionService
    def asaasMoneyManagerService

    public void updateStatusIfNecessary(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        if (!asaasMoneyTransactionInfo.type.isBill() && !asaasMoneyTransactionInfo.type.isCreditCard()) return

        AsyncActionType asyncActionType = AsyncActionType.ASAAS_MONEY_PAYMENT_STATUS_CHANGE
        Map asyncActionData = [paymentId: asaasMoneyTransactionInfo.getRelatedPayment().publicId, installmentId: asaasMoneyTransactionInfo.destinationCreditCardInstallment?.publicId, status: asaasMoneyTransactionInfo.getRelatedPayment().status]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void processStatusChangeAsyncAction() {
        for (Map asyncActionData : asyncActionService.listPendingAsaasMoneyPaymentStatusChange()) {
            Utils.withNewTransactionAndRollbackOnError({
                asaasMoneyManagerService.updatePaymentStatus(asyncActionData.paymentId, asyncActionData.installmentId, asyncActionData.status)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AsaasMoneyService.processStatusChangeAsyncAction >> Erro ao processar asyncAction de troca de status de Pagamento [${asyncActionData.id}] para o Asaas Money. ID: [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }
}
