package com.asaas.service.pix.account

import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.addresskey.PixCustomerInfoAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class HermesAccountService {

    def asyncActionService
    def hermesAccountManagerService
    def pixAddressKeyService

    public Boolean accountCanBeDisabled(Customer customer) {
        PixCustomerInfoAdapter pixCustomerInfoAdapter = pixAddressKeyService.getCustomerAddressKeyInfoList(customer)
        if (!pixCustomerInfoAdapter?.externalClaimList) return true

        Boolean hasRestrictions = pixCustomerInfoAdapter.externalClaimList.any { it.type.isOwnershipClaim() && it.status.isInProgress() }
        return !hasRestrictions
    }

    public void syncDisabledAccounts() {
        List<Map> asyncActionList = asyncActionService.listPendingDisableHermesAccount()
        List<Long> pendingDisableHermesAccountForDeleteIdList = []

        for (Map asyncActionData : asyncActionList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncAction = AsyncAction.get(asyncActionData.asyncActionId)

                Map response = hermesAccountManagerService.disable(asyncAction.getDataAsMap().customerId)
                if (!response.success) throw new RuntimeException(response.errorMessage)

            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    retryOnErrorWithLogs(asyncActionData.asyncActionId, exception)
                    hasError = true
                }
            ])

            if (!hasError) pendingDisableHermesAccountForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(pendingDisableHermesAccountForDeleteIdList)
    }

    public void syncBlockedAccounts() {
        List<Map> asyncActionList = asyncActionService.listPendingBlockHermesAccount()
        List<Long> pendingBlockHermesAccountForDeleteIdList = []

        for (Map asyncActionData : asyncActionList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncAction = AsyncAction.get(asyncActionData.asyncActionId)

                Map response = hermesAccountManagerService.block(asyncAction.getDataAsMap().customerId)
                if (!response.success) throw new RuntimeException(response.errorMessage)

            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    retryOnErrorWithLogs(asyncActionData.asyncActionId, exception)
                    hasError = true
                }
            ])

            if (!hasError) pendingBlockHermesAccountForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(pendingBlockHermesAccountForDeleteIdList)
    }

    public void syncActivatedAccounts() {
        List<Map> asyncActionList = asyncActionService.listPendingActivateHermesAccount()
        List<Long> pendingActivateHermesAccountForDeleteIdList = []

        for (Map asyncActionData : asyncActionList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncAction = AsyncAction.get(asyncActionData.asyncActionId)

                Map response = hermesAccountManagerService.activate(asyncAction.getDataAsMap().customerId)
                if (!response.success) throw new RuntimeException(response.errorMessage)

            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    retryOnErrorWithLogs(asyncActionData.asyncActionId, exception)
                    hasError = true
                }
            ])

            if (!hasError) pendingActivateHermesAccountForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(pendingActivateHermesAccountForDeleteIdList)
    }

    private void retryOnErrorWithLogs(Long asyncActionId, Exception exception) {
        AsaasLogger.warn("HermesAccountService.retryOnErrorWithLogs() -> Falha de processamento, enviando para reprocessamento se for possível [id: ${asyncActionId}]", exception)

        Utils.withNewTransactionAndRollbackOnError({
            AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionId)
            if (asyncAction.status.isCancelled()) {
                AsaasLogger.error("HermesAccountService.retryOnErrorWithLogs() -> Falha de processamento, quantidade máxima de tentativas atingida AsyncAction [id: ${asyncActionId}]")
            }
        }, [logErrorMessage: "HermesAccountService.retryOnErrorWithLogs() -> Falha ao enviar AsyncAction [id: ${asyncActionId}] para reprocessamento"])
    }
}
