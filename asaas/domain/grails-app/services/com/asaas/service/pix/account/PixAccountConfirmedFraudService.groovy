package com.asaas.service.pix.account

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.accountConfirmedFraud.SaveAccountConfirmedFraudAdapter
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixAccountConfirmedFraudService {

    def asyncActionService
    def hermesAccountConfirmedFraudManagerService

    public void processSavePixAccountConfirmedFraud() {
        final Integer maxPendingItems = 500
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SAVE_PIX_ACCOUNT_CONFIRMED_FRAUD, maxPendingItems)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncAction = AsyncAction.get(asyncActionData.asyncActionId)

                Map response = save(new SaveAccountConfirmedFraudAdapter(asyncAction.getDataAsMap()))
                if (!response.success) throw new RuntimeException(response.errorMessage)

                asyncActionService.setAsDone(asyncActionData.asyncActionId)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    retryOnErrorWithLogs(asyncActionData.asyncActionId, exception)
                }
            ])
        }
    }

    public Map save(SaveAccountConfirmedFraudAdapter saveAccountConfirmedFraudAdapter) {
        BusinessValidation validateSave = validateSave(saveAccountConfirmedFraudAdapter)
        if (!validateSave.isValid()) return [success: false, errorMessage: validateSave.getFirstErrorMessage()]

        Map response = hermesAccountConfirmedFraudManagerService.save(saveAccountConfirmedFraudAdapter)
        if (!response.success) return [success: false, errorMessage: response.errorMessage]

        return [success: true]
    }

    public void processCancelPixAccountConfirmedFraud() {
        final Integer maxPendingItems = 500
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.CANCEL_PIX_ACCOUNT_CONFIRMED_FRAUD, maxPendingItems)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncAction = AsyncAction.get(asyncActionData.asyncActionId)

                Map response = cancel(asyncAction.getDataAsMap().customerId)
                if (!response.success) throw new RuntimeException(response.errorMessage)

                asyncActionService.setAsDone(asyncActionData.asyncActionId)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    retryOnErrorWithLogs(asyncActionData.asyncActionId, exception)
                }
            ])
        }
    }

    public Map cancel(Long customerId) {
        return hermesAccountConfirmedFraudManagerService.cancel(customerId)
    }

    private BusinessValidation validateSave(SaveAccountConfirmedFraudAdapter saveAccountConfirmedFraudAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()
        if (!saveAccountConfirmedFraudAdapter.customer) {
            businessValidation.addError("pixAccountConfirmedFraud.error.save.customerNotInformed")
            return businessValidation
        }

        if (!saveAccountConfirmedFraudAdapter.fraudType) {
            businessValidation.addError("pixAccountConfirmedFraud.error.save.fraudTypeNotInformed")
            return businessValidation
        }

        if (saveAccountConfirmedFraudAdapter.hasInformedPixKey && !saveAccountConfirmedFraudAdapter.pixKey) {
            businessValidation.addError("pixAccountConfirmedFraud.error.save.invalidPixKey")
            return businessValidation
        }

        return businessValidation
    }

    private void retryOnErrorWithLogs(Long asyncActionId, Exception exception) {
        AsaasLogger.warn("PixAccountConfirmedFraudService.retryOnErrorWithLogs() -> Falha de processamento, enviando para reprocessamento se for possível [id: ${asyncActionId}]", exception)

        Utils.withNewTransactionAndRollbackOnError({
            AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionId)
            if (asyncAction.status.isCancelled()) {
                AsaasLogger.error("PixAccountConfirmedFraudService.retryOnErrorWithLogs() -> Falha de processamento, quantidade máxima de tentativas atingida AsyncAction [id: ${asyncActionId}]")
            }
        }, [logErrorMessage: "PixAccountConfirmedFraudService.retryOnErrorWithLogs() -> Falha ao enviar AsyncAction [id: ${asyncActionId}] para reprocessamento"])
    }
}
