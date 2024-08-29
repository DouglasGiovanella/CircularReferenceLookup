package com.asaas.service.paymentinfo

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.utils.AbTestUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentAnticipableInfoAsyncService {

    def asyncActionService
    def paymentAnticipableInfoService

    public void saveCreditCardAnticipableAsAwaitingAnalysisIfNecessary(Customer customer, Map search) {
        if (!customer.canAnticipate()) return
        if (!customer.canAnticipateCreditCard()) return

        asyncActionService.saveUpdateCreditCardAnticipableAsAwaitingAnalysis(customer.id, search + [customerId: customer.id])
    }

    public void saveBankSlipAndPixAnticipableAsAwaitingAnalysisIfNecessary(Customer customer, Map search) {
        saveBankSlipAnticipableAsAwaitingAnalysisIfNecessary(customer, search)
        savePixAnticipableAsAwaitingAnalysisIfNecessary(customer, search)
    }

    public void saveAnticipableAsAwaitingAnalysisIfNecessary(Customer customer, Map search) {
        saveCreditCardAnticipableAsAwaitingAnalysisIfNecessary(customer, search)
        saveBankSlipAndPixAnticipableAsAwaitingAnalysisIfNecessary(customer, search)
    }

    public void saveCustomerAnticipationsAsAwaitingAnalysisIfNecessary(Customer customer, BillingType billingType) {
        if (billingType.isBoleto()) {
            saveBankSlipAnticipableAsAwaitingAnalysisIfNecessary(customer, [:])
        } else if (billingType.isCreditCard()) {
            saveCreditCardAnticipableAsAwaitingAnalysisIfNecessary(customer, [:])
        } else if (billingType.isPix()) {
            savePixAnticipableAsAwaitingAnalysisIfNecessary(customer, [:])
        }
    }

    public void processUpdateAnticipableAsAwaitingAnalysisAsyncAction(AsyncActionType actionType) {
        if (!actionType.isUpdateAnticipableAsAwaitingAnalysis()) throw new RuntimeException("O tipo da asyncAction é inválido")

        final Integer maxNumberOfGroupIdPerExecution = 600
        List<String> groupIdList = AsyncAction.oldestPending([distinct: "groupId", disableSort: true, includeDeleted: true, type: actionType]).list(max: maxNumberOfGroupIdPerExecution)

        final Integer asyncActionsPerThread = 150
        final Integer flushEvery = 50
        ThreadUtils.processWithThreadsOnDemand(groupIdList, asyncActionsPerThread, { List<String> subGroupIdList ->
            List<Map> asyncActionDataList = asyncActionService.listPending(actionType, subGroupIdList, asyncActionsPerThread)
            Utils.forEachWithFlushSession(asyncActionDataList, flushEvery, { Map asyncActionData ->
                Utils.withNewTransactionAndRollbackOnError({
                    List<String> allowedFilters = ["paymentCustomerAccountId", "value[gt]", "value[le]"]
                    Map search = asyncActionData.subMap(allowedFilters)
                    search.customer = Customer.read(asyncActionData.customerId)

                    if (actionType.isUpdateBankSlipAnticipableAsAwaitingAnalysis()) {
                        paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysis(BillingType.BOLETO, search)
                    } else if (actionType.isUpdateCreditCardAnticipableAsAwaitingAnalysis()) {
                        paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysis(BillingType.MUNDIPAGG_CIELO, search)
                    } else if (actionType.isUpdatePixAnticipableAsAwaitingAnalysis()) {
                        paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysis(BillingType.PIX, search)
                    }

                    asyncActionService.delete(asyncActionData.asyncActionId)
                }, [logErrorMessage: "PaymentAnticipableInfoAsyncService.processAnticipableAwaitingAnalysisAsyncAction >> Falha ao processar asyncAction de atualização do paymentAnticipableInfo [${asyncActionData.asyncActionId}]",
                    onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) } ])
            })
        })
    }

    private void saveBankSlipAnticipableAsAwaitingAnalysisIfNecessary(Customer customer, Map search) {
        if (!customer.canAnticipate()) return
        if (!customer.canAnticipateBoleto()) return

        asyncActionService.saveUpdateBankSlipAnticipableAsAwaitingAnalysis(customer.id, search + [customerId: customer.id])
    }

    private void savePixAnticipableAsAwaitingAnalysisIfNecessary(Customer customer, Map search) {
        if (!AbTestUtils.hasPixAnticipation(customer)) return
        if (!customer.canAnticipate()) return
        if (!customer.canAnticipateBoleto()) return

        asyncActionService.saveUpdatePixAnticipableAsAwaitingAnalysis(customer.id, search + [customerId: customer.id])
    }
}
