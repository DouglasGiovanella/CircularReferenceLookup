package com.asaas.service.sage

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.collections.CollectionUtils

@Transactional
class SageAccountService {

    def asyncActionService
    def sageManagerService

    public void processPendingCreateAccounts() {
        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.CREATE_SAGE_ACCOUNT, 500)

        List<Long> pendingCreateSageAccountForDeleteIdList = []

        final Integer numberOfThreads = 2
        Utils.processWithThreads(asyncActionList, numberOfThreads, { List<Map> threadAsyncActionList ->
            for (Map asyncActionData : threadAsyncActionList) {
                Boolean hasError = false
                Utils.withNewTransactionAndRollbackOnError({
                    Customer customer = Customer.read(asyncActionData.customerId)
                    sageManagerService.saveAccountFromCustomer(customer)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        Utils.withNewTransactionAndRollbackOnError({
                            AsaasLogger.warn("SageAccountService.processPendingCreateAccounts >> Falha de processamento [customerId ${asyncActionData.customerId}], enviando para reprocessamento se for possível [asyncActionId: ${asyncActionData.asyncActionId}]", exception)
                            AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                            if (asyncAction.status.isCancelled()) {
                                AsaasLogger.error("SageAccountService.processPendingCreateAccounts >> Falha de processamento [customerId ${asyncActionData.customerId}], quantidade máxima de tentativas atingida [asyncActionId: ${asyncActionData.asyncActionId}]")
                            }
                        }, [logErrorMessage: "SageAccountService.processPendingCreateAccounts >> Falha ao enviar AsyncAction [id: ${asyncActionData.asyncActionId}] para reprocessamento"])

                        hasError = true
                    }
                ])

                if (!hasError) pendingCreateSageAccountForDeleteIdList.add(asyncActionData.asyncActionId)
            }
        })

        asyncActionService.deleteList(pendingCreateSageAccountForDeleteIdList)
    }

    public void processPendingUpdateAccounts() {
        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.UPDATE_SAGE_ACCOUNT, 500)

        List<Long> pendingUpdateSageAccountForDeleteIdList = []

        for (Map asyncActionData : asyncActionList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                sageManagerService.saveAccountFromCustomer(customer)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    Utils.withNewTransactionAndRollbackOnError({
                        AsaasLogger.warn("SageAccountService.processPendingUpdateAccounts >> Falha de processamento [customerId ${asyncActionData.customerId}], enviando para reprocessamento se for possível [asyncActionId: ${asyncActionData.asyncActionId}]", exception)
                        AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        if (asyncAction.status.isCancelled()) {
                            AsaasLogger.error("SageAccountService.processPendingUpdateAccounts >> Falha de processamento [customerId ${asyncActionData.customerId}], quantidade máxima de tentativas atingida [asyncActionId: ${asyncActionData.asyncActionId}]")
                        }
                    }, [logErrorMessage: "SageAccountService.processPendingUpdateAccounts >> Falha ao enviar AsyncAction [id: ${asyncActionData.asyncActionId}] para reprocessamento"])

                    hasError = true
                }
            ])

            if (!hasError) pendingUpdateSageAccountForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(pendingUpdateSageAccountForDeleteIdList)
    }

    public void processPendingInactivateAccounts() {
        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.INACTIVATE_SAGE_ACCOUNT, 500)

        List<Long> pendingInactivateSageAccountForDeleteIdList = []

        for (Map asyncActionData : asyncActionList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Long customerId = Long.valueOf(asyncActionData.customerId.toString())
                sageManagerService.inactivateAccountFromCustomerId(customerId)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    Utils.withNewTransactionAndRollbackOnError({
                        AsaasLogger.warn("SageAccountService.processPendingInactivateAccounts >> Falha de processamento [customerId ${asyncActionData.customerId}], enviando para reprocessamento se for possível [asyncActionId: ${asyncActionData.asyncActionId}]", exception)
                        AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        if (asyncAction.status.isCancelled()) {
                            AsaasLogger.error("SageAccountService.processPendingInactivateAccounts >> Falha de processamento [customerId ${asyncActionData.customerId}], quantidade máxima de tentativas atingida [asyncActionId: ${asyncActionData.asyncActionId}]")
                        }
                    }, [logErrorMessage: "SageAccountService.processPendingInactivateAccounts >> Falha ao enviar AsyncAction [id: ${asyncActionData.asyncActionId}] para reprocessamento"])

                    hasError = true
                }
            ])

            if (!hasError) pendingInactivateSageAccountForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(pendingInactivateSageAccountForDeleteIdList)
    }

    public void onCustomerUpdated(Customer customer, List<String> customerUpdatedFieldList) {
        final List<String> fieldToWatchList = ["birthDate", "cpfCnpj"]

        Boolean hasUpdatedInterestProperties = CollectionUtils.containsAny(fieldToWatchList, customerUpdatedFieldList)
        if (!hasUpdatedInterestProperties) return

        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters([customerId: customer.id], AsyncActionType.UPDATE_SAGE_ACCOUNT)
        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.UPDATE_SAGE_ACCOUNT, [customerId: customer.id])
    }

    public void onCustomerAccountOwnerUpdated(Long customerId) {
        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters([customerId: customerId], AsyncActionType.UPDATE_SAGE_ACCOUNT)
        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.UPDATE_SAGE_ACCOUNT, [customerId: customerId])
    }

    public void onCustomerInactivated(long customerId) {
        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters([customerId: customerId], AsyncActionType.INACTIVATE_SAGE_ACCOUNT)
        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.INACTIVATE_SAGE_ACCOUNT, [customerId: customerId])
    }

    public void onCustomerActivated(Long customerId) {
        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters([customerId: customerId], AsyncActionType.CREATE_SAGE_ACCOUNT)
        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.CREATE_SAGE_ACCOUNT, [customerId: customerId])
    }
}
