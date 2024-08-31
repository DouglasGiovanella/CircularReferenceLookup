package com.asaas.service.customer.knowyourcustomerinfo

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.PersonType
import com.asaas.customer.knowyourcustomerinfo.KnowYourCustomerInfoIncomeRange
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.knowyourcustomerinfo.KnowYourCustomerInfo
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.environment.AsaasEnvironment
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class KnowYourCustomerInfoService {

    def asyncActionService
    def crypterService

    @Deprecated
    public KnowYourCustomerInfo saveWithIncomeRange(Customer customer, KnowYourCustomerInfoIncomeRange incomeRange) {
        if (!incomeRange) return null

        KnowYourCustomerInfo knowYourCustomerInfo = KnowYourCustomerInfo.query([customer: customer]).get()
        if (!knowYourCustomerInfo) {
            knowYourCustomerInfo = new KnowYourCustomerInfo()
            knowYourCustomerInfo.customer = customer
            knowYourCustomerInfo.save(failOnError: true)
        }

        knowYourCustomerInfo.incomeRange = crypterService.encryptDomainProperty(knowYourCustomerInfo, "incomeRange", incomeRange.toString())
        knowYourCustomerInfo.incomeValue = crypterService.encryptDomainProperty(knowYourCustomerInfo, "incomeValue", buildIncomeValueByIncomeRange(incomeRange).toString())
        knowYourCustomerInfo.save(failOnError: true)

        return knowYourCustomerInfo
    }

    public KnowYourCustomerInfo save(Customer customer, BigDecimal incomeValue) {
        if (!incomeValue) return null

        KnowYourCustomerInfo knowYourCustomerInfo = KnowYourCustomerInfo.query([customer: customer]).get()
        if (!knowYourCustomerInfo) {
            knowYourCustomerInfo = new KnowYourCustomerInfo()
            knowYourCustomerInfo.customer = customer
            knowYourCustomerInfo.save(failOnError: true)
        }

        knowYourCustomerInfo.incomeValue = crypterService.encryptDomainProperty(knowYourCustomerInfo, "incomeValue", incomeValue.toString())
        knowYourCustomerInfo.save(failOnError: true)

        return knowYourCustomerInfo
    }

    public List<AsaasError> validateIncomeRange(Customer customer, Map params) {
        List<AsaasError> asaasErrorList = []

        if (!validateIncomeRangeRequired(customer, params)) {
            asaasErrorList.add(new AsaasError("knowYourCustomerInfo.validate.incomeRange.isRequired"))
            return asaasErrorList
        }

        PersonType personType = CpfCnpjUtils.getPersonType(params.cpfCnpj)
        if (params.incomeRange && !validateIncomeRangeBasedOnPersonType(personType, params.incomeRange.toString())) {
            asaasErrorList.add(new AsaasError("knowYourCustomerInfo.validate.incomeRange.isInvalidByPersonType"))
            return asaasErrorList
        }

        return asaasErrorList
    }

    public List<AsaasError> validateIncomeValue(Customer customer, Map params) {
        List<AsaasError> asaasErrorList = []

        if (!validateIncomeValueRequired(customer.id, params)) {
            asaasErrorList.add(new AsaasError("knowYourCustomerInfo.validate.incomeValue.isRequired"))
            return asaasErrorList
        }

        if (Utils.toBigDecimal(params.incomeValue) <= 0) {
            asaasErrorList.add(new AsaasError("knowYourCustomerInfo.validate.incomeValue.invalidValue"))
            return asaasErrorList
        }

        return asaasErrorList
    }

    public Boolean validateIncomeRangeBasedOnPersonType(PersonType personType, String incomeRangeString) {
        if (!personType || !incomeRangeString) return false

        KnowYourCustomerInfoIncomeRange incomeRange = KnowYourCustomerInfoIncomeRange.convert(incomeRangeString)

        if (personType.isFisica() && incomeRange.isRangeForNaturalPerson()) return true

        if (personType.isJuridica() && incomeRange.isRangeForLegalPerson()) return true

        return false
    }

    public void saveLegalNamesAsyncActionData(String cnpj, String tradingName, String corporateName) {
        if (!AsaasEnvironment.isProduction()) return

        Map asyncActionData = [cnpj: cnpj, tradingName: tradingName, corporateName: corporateName]
        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SAVE_KNOW_YOUR_CUSTOMER_INFO_LEGAL_NAMES)

        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.SAVE_KNOW_YOUR_CUSTOMER_INFO_LEGAL_NAMES, asyncActionData)
    }

    public void processSaveLegalNames() {
        final Integer maxItemsPerCycle = 500
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SAVE_KNOW_YOUR_CUSTOMER_INFO_LEGAL_NAMES, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                List<Customer> customerList = Customer.query([cpfCnpj: asyncActionData.cnpj]).list(readOnly: true)
                if (!customerList) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                for (Customer customer : customerList) {
                    saveOrUpdateLegalNames(customer, asyncActionData.tradingName, asyncActionData.corporateName)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "KnowYourCustomerInfoService.processSaveLegalNames >> Erro ao salvar os nomes legais do cliente. AsyncActionID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public BigDecimal buildIncomeValueByIncomeRange(KnowYourCustomerInfoIncomeRange knowYourCustomerInfoIncomeRange) {
        switch (knowYourCustomerInfoIncomeRange) {
            case KnowYourCustomerInfoIncomeRange.UP_TO_5K:
                return 5000
            case KnowYourCustomerInfoIncomeRange.FROM_5K_TO_10K:
                return 7500
            case KnowYourCustomerInfoIncomeRange.FROM_10K_TO_20K:
                return 15000
            case KnowYourCustomerInfoIncomeRange.ABOVE_20K:
                return 20000
            case KnowYourCustomerInfoIncomeRange.UP_TO_50K:
                return 50000
            case KnowYourCustomerInfoIncomeRange.FROM_50K_TO_100K:
                return 75000
            case KnowYourCustomerInfoIncomeRange.FROM_100K_TO_250K:
                return 175000
            case KnowYourCustomerInfoIncomeRange.FROM_250K_TO_1MM:
                return 625000
            case KnowYourCustomerInfoIncomeRange.FROM_1MM_TO_5MM:
                return 3000000
            case KnowYourCustomerInfoIncomeRange.ABOVE_5MM:
                return 5000000
            default:
                throw new NotImplementedException("Faixa de renda ${knowYourCustomerInfoIncomeRange} não implementada")
        }
    }

    private void saveOrUpdateLegalNames(Customer customer, String tradingName, String corporateName) {
        KnowYourCustomerInfo knowYourCustomerInfo = KnowYourCustomerInfo.query([customer: customer]).get()
        if (!knowYourCustomerInfo) {
            knowYourCustomerInfo = new KnowYourCustomerInfo()
            knowYourCustomerInfo.customer = customer
        }

        knowYourCustomerInfo.tradingName = tradingName
        knowYourCustomerInfo.corporateName = corporateName
        knowYourCustomerInfo.save(failOnError: true)
    }

    private Boolean validateIncomeRangeRequired(Customer customer, Map params) {
        if (params.incomeRange) return true

        if (params.bypassIncomeRangeMandatoryValidation) return true

        if (CustomerPartnerApplication.hasValidApplicationToAutoApprove(customer.id)) return true

        return false
    }

    private Boolean validateIncomeValueRequired(Long customerId, Map params) {
        if (params.incomeValue) return true

        if (params.bypassIncomeRangeMandatoryValidation) return true

        if (CustomerPartnerApplication.hasValidApplicationToAutoApprove(customerId)) return true

        return false
    }
}
