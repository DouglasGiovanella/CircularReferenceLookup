package com.asaas.service.sage

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class SageAccountParameterService {

    def asyncActionService
    def sageManagerService

    public void processPendingUpdateAccountParameter() {
        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.UPDATE_SAGE_ACCOUNT_PARAMETER, 500)

        List<Long> pendingUpdateAccountParameterForDeleteIdList = []
        for (Map asyncActionData : asyncActionList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                sageManagerService.saveAccountFromCustomer(customer)
            }, [ignoreStackTrace: true,
                onError         : { Exception exception ->
                    Utils.withNewTransactionAndRollbackOnError({
                        AsaasLogger.warn("SageCustomerService.processPendingUpdateAccountParameter >> Falha de processamento [customerId ${asyncActionData.customerId}], enviando para reprocessamento se for possível [asyncActionId: ${asyncActionData.asyncActionId}]", exception)
                        AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        if (asyncAction.status.isCancelled()) {
                            AsaasLogger.error("SageCustomerService.processPendingUpdateAccountParameter >> Falha de processamento [customerId ${asyncActionData.customerId}], quantidade máxima de tentativas atingida [asyncActionId: ${asyncActionData.asyncActionId}]")
                        }
                    }, [logErrorMessage: "SageCustomerService.processPendingUpdateAccountParameter >> Falha ao enviar AsyncAction [id: ${asyncActionData.asyncActionId}] para reprocessamento"])

                    hasError = true
                }
            ])

            if (!hasError) pendingUpdateAccountParameterForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(pendingUpdateAccountParameterForDeleteIdList)
    }

    public void onCustomerParameterUpdated(CustomerParameter parameter) {
        Boolean isSageAccountParameter = CustomerParameterName.listSageAccountParameters().contains(parameter.name)
        if (!isSageAccountParameter) return

        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters([customerId: parameter.customer.id], AsyncActionType.UPDATE_SAGE_ACCOUNT_PARAMETER)
        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.UPDATE_SAGE_ACCOUNT_PARAMETER, [customerId: parameter.customer.id])
    }
}
