package com.asaas.service.fraudtrackingaccount

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CompanyType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.knowyourcustomerinfo.KnowYourCustomerInfo
import com.asaas.integration.heimdall.dto.dataenhancement.get.legal.LegalDataEnhancementDTO
import com.asaas.integration.heimdall.dto.dataenhancement.get.natural.NaturalDataEnhancementDTO
import com.asaas.integration.sauron.adapter.fraudtracking.FraudTrackingAccountAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FraudTrackingAccountService {

    def asyncActionService
    def fraudTrackingAccountManagerService
    def dataEnhancementManagerService

    public void saveFraudTrackingAccountIfPossible(Customer customer) {
        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return

        Map asyncActionData = [customerId: customer.id]
        AsyncActionType asyncActionType = AsyncActionType.SAVE_FRAUD_TRACKING_ACCOUNT

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void processSaveFraudTrackingAccount() {
        final Integer maxItemsPerCycle = 50

        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.SAVE_FRAUD_TRACKING_ACCOUNT, maxItemsPerCycle)) {
            Utils.withNewTransactionAndRollbackOnError({
                fraudTrackingAccountManagerService.save(asyncActionData.customerId)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "FraudTrackingAccountService.processSaveFraudTrackingAccount >> Erro ao salvar conta para monitoramento. AsyncActionId: ${asyncActionData.asyncActionId} | CustomerId: ${asyncActionData.customerId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public FraudTrackingAccountAdapter getAccountData(Long customerId) {
        Customer customer = Customer.read(customerId)
        FraudTrackingAccountAdapter fraudTrackingAccountAdapter = buildFraudTrackingAccountAdapterBasedOnPersonType(customer)

        return fraudTrackingAccountAdapter
    }

    private FraudTrackingAccountAdapter buildFraudTrackingAccountAdapterBasedOnPersonType(Customer customer) {
        try {
            String incomeValue = customer.personType.isFisica() ? buildNaturalPersonIncomeValue(customer) : buildLegalPersonIncomeValue(customer)
            return new FraudTrackingAccountAdapter(customer, incomeValue)
        } catch (Exception exception) {
            AsaasLogger.error("FraudTrackingAccountService.buildFraudTrackingAccountAdapterBasedOnPersonType >> CustomerId: [${customer.id}]", exception)
            return new FraudTrackingAccountAdapter(customer, null)
        }
    }

    private String buildNaturalPersonIncomeValue(Customer customer) {
        final String defaultNaturalPersonIncomeValue = "50000"

        String knowYourCustomerIncomeValue = buildIncomeValueBasedOnKnowYourCustomer(customer)
        if (knowYourCustomerIncomeValue) return knowYourCustomerIncomeValue

        NaturalDataEnhancementDTO naturalDataEnhancementDTO = dataEnhancementManagerService.findNaturalDataEnhancement(customer.cpfCnpj)
        String dataEnhancementIncomeRange = naturalDataEnhancementDTO?.partnerDataRiskIncomePrediction?.predictedIncome
        if (dataEnhancementIncomeRange) return dataEnhancementIncomeRange

        return defaultNaturalPersonIncomeValue
    }

    private String buildLegalPersonIncomeValue(Customer customer) {
        String knowYourCustomerIncomeValue = buildIncomeValueBasedOnKnowYourCustomer(customer)
        if (knowYourCustomerIncomeValue) return knowYourCustomerIncomeValue

        LegalDataEnhancementDTO legalDataEnhancementDTO = dataEnhancementManagerService.findLegalDataEnhancement(customer.cpfCnpj)
        String dataEnhancementIncomeRange = legalDataEnhancementDTO?.activityIndicators?.parsedIncomeRange
        if (dataEnhancementIncomeRange) return dataEnhancementIncomeRange

        return buildIncomeRangeBasedOnCompanyType(customer.companyType)
    }

    private String buildIncomeValueBasedOnKnowYourCustomer(Customer customer) {
        BigDecimal knowYourCustomerInfoIncomeValue = KnowYourCustomerInfo.findIncomeValue(customer)
        return knowYourCustomerInfoIncomeValue ? knowYourCustomerInfoIncomeValue.toString() : null
    }

    private String buildIncomeRangeBasedOnCompanyType(CompanyType companyType) {
        switch (companyType) {
            case CompanyType.MEI:
                return "54000"
            case CompanyType.INDIVIDUAL:
                return "100000"
            case CompanyType.LIMITED:
                return "270000"
            case CompanyType.ASSOCIATION:
            case CompanyType.NON_PROFIT_ASSOCIATION:
                return "81000"
            default:
                return null
        }
    }
}
