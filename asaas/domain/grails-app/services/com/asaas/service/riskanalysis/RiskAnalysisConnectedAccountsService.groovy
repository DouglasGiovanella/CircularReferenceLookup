package com.asaas.service.riskanalysis

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customergeneralanalysis.CustomerGeneralAnalysisRejectReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.customergeneralanalysis.CustomerGeneralAnalysis
import com.asaas.integration.sauron.enums.ConnectedAccountInfoGroupType
import com.asaas.log.AsaasLogger
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class RiskAnalysisConnectedAccountsService {

    def asyncActionService
    def connectedAccountService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void createRiskAnalysisToConnectedAccounts() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CONNECTED_TO_FRAUD_CONFIRMED_ACCOUNT
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.RISK_ANALYSIS_CONNECTED_ACCOUNTS, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Long customerId = Long.valueOf(asyncActionData.customerId)
                createToConnectedAccounts(customerId)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "RiskAnalysisConnectedAccountsService.createRiskAnalysisToConnectedAccounts >> Erro ao salvar análise de risco para contas conectadas. AsyncActionId: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void createIfNecessary(CustomerGeneralAnalysis customerGeneralAnalysis) {
        CustomerGeneralAnalysisRejectReason rejectReason = customerGeneralAnalysis.generalAnalysisRejectReason
        if (!rejectReason) return

        if (!rejectReason.rejectReasonDescription.isFraudRelated()) return

        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CONNECTED_TO_FRAUD_CONFIRMED_ACCOUNT
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        saveAsyncActionIfPossible(customerGeneralAnalysis.customer)
    }

    public void createRiskAnalysisByConnectedAccountIfNecessary(Long customerId, List<Long> connectedAccountIdList) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CONNECTED_TO_FRAUD_CONFIRMED_REJECTED_ACCOUNT
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        if (!connectedAccountIdList) return

        Customer customer = Customer.read(customerId)
        if (customer.hasUserWithSysAdminRole()) return
        if (customer.suspectedOfFraud) return

        List<CustomerGeneralAnalysisRejectReason> fraudRejectReasonList =
            CustomerGeneralAnalysisRejectReason.getValidValues().findAll { it.rejectReasonDescription.isFraudRelated() }

        Map queryParams = [
            exists: true,
            disableSort: true,
            "customerId[in]": connectedAccountIdList,
            "generalAnalysisRejectReason[in]": fraudRejectReasonList
        ]

        Boolean hasRelatedCustomerSuspectedOfFraud = CustomerGeneralAnalysis.query(queryParams).get().asBoolean()

        if (!hasRelatedCustomerSuspectedOfFraud) return
        riskAnalysisRequestService.save(customer, riskAnalysisReason, null)
    }

    private void saveAsyncActionIfPossible(Customer customer) {
        Map asyncActionData = [customerId: customer.id]
        AsyncActionType asyncActionType = AsyncActionType.RISK_ANALYSIS_CONNECTED_ACCOUNTS

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    private void createToConnectedAccounts(Long customerId) {
        final Integer connectionLevelToConsider = 1

        List<ConnectedAccountInfoGroupType> groupTypeList = ConnectedAccountInfoGroupType.values()
        List<Long> connectedAccountIdList = connectedAccountService.buildDistinctConnectedAccountIdList(customerId, groupTypeList, connectionLevelToConsider, true)
        if (!connectedAccountIdList) return

        final Integer limitRiskAnalysisToCreate = 20
        Map riskAnalysisParams = [ object: Customer.getSimpleName(), objectId: customerId.toString() ]

        if (connectedAccountIdList.size() > limitRiskAnalysisToCreate) {
            riskAnalysisParams.additionalInfo = "Atenção! Análise foi criada a partir de uma amostragem das associações com a conta ${customerId}."
            AsaasLogger.warn("RiskAnalysisConnectedAccountsService.createToConnectedAccounts >> limite de criação de análises excedido. Customer [${customerId}]")
        }

        final Integer flushEvery = 5
        Integer countRiskAnalysisCreated = 0

        Utils.forEachWithFlushSession(connectedAccountIdList, flushEvery, { Long connectedCustomerId ->
            if (countRiskAnalysisCreated > limitRiskAnalysisToCreate) return

            Utils.withNewTransactionAndRollbackOnError({
                Customer connectedCustomer = Customer.read(connectedCustomerId)
                riskAnalysisRequestService.save(connectedCustomer, RiskAnalysisReason.CONNECTED_TO_FRAUD_CONFIRMED_ACCOUNT, riskAnalysisParams)
                countRiskAnalysisCreated++
            }, [logErrorMessage: "RiskAnalysisConnectedAccountsService.createToConnectedAccounts >> Erro ao criar análise de risco para conta conectada. Conta origem [${customerId}] Conta conectada [${connectedCustomerId}]"])
        })
    }
}
