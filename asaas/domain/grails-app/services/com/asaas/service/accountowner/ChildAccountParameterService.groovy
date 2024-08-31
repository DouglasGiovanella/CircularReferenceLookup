package com.asaas.service.accountowner

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ChildAccountParameterService {

    public static final List<String> MANDATORY_FILTER_IGNORE_CUSTOMER_DOMAIN_LIST = [CustomerCriticalActionConfig.simpleName]

    public static final List<String> PARAMETERS_ALLOWED_EMPTY_VALUE_LIST = ["creditCardPercentage"]

    def childAccountParameterParserService

    public Map buildQueryParamsForReplication(Long accountOwnerId, String fieldName, value, String type) {
        Map search = [column: "customer.id", accountOwnerId: accountOwnerId]

        if (MANDATORY_FILTER_IGNORE_CUSTOMER_DOMAIN_LIST.contains(type)) {
            search.ignoreCustomer = true
        }

        search."${fieldName}[ne]" = buildValueToCompareForReplication(accountOwnerId, fieldName, type, value)

        return search
    }

    public Map buildChildAccountParameterData(Long accountOwnerId, String type) {
        Map childAccountParameterData = [:]

        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: type]).list()

        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            childAccountParameterData."${childAccountParameter.name}" = childAccountParameterParserService.parse(childAccountParameter)
        }

        return childAccountParameterData
    }

    public BusinessValidation canSetChildAccountParameterForAllChildAccounts(Long customerId, String type, String fieldName) {
        BusinessValidation businessValidation = new BusinessValidation()
        Map search = [accountOwnerId: customerId, type: type, exists: true, name: fieldName]

        if (!ChildAccountParameter.query(search).get().asBoolean()) {
            businessValidation.addError("customer.childAccountParameter.notFound")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation canSetChildAccountParameterTypeForAllChildAccounts(Long customerId, String type, String product) {
        BusinessValidation businessValidation = new BusinessValidation()
        Map search = [accountOwnerId: customerId, type: type, exists: true]

        if (!ChildAccountParameter.query(search).get().asBoolean()) {
            businessValidation.addError("customer.childAccountParameter.notFound")
            return businessValidation
        }

        if (product) {
            Boolean hasCustomerDealInfoFeeConfig = CustomerDealInfoFeeConfigGroupRepository.query([customerId: customerId, product: FeeNegotiationProduct.convert(product), "replicationType[in]": FeeNegotiationReplicationType.applicableForChildAccount()]).exists()
            if (hasCustomerDealInfoFeeConfig) {
                businessValidation.addError("feeConfig.hasCustomerDealInfoFeeConfig.message")
                return businessValidation
            }
        }

        return businessValidation
    }

    public ChildAccountParameter saveOrUpdate(Customer accountOwner, String type, String name, value) {
        ChildAccountParameter currentChildAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: type, name: name]).get()

        Boolean hasNewParameterToSave = !currentChildAccountParameter && !Utils.isEmptyOrNull(value)
        if (hasNewParameterToSave) return save(accountOwner, type, name, value)

        Boolean shouldDeleteParameterForBankSlipConfig = currentChildAccountParameter && Utils.isEmptyOrNull(value)
        if (shouldDeleteParameterForBankSlipConfig) return delete(currentChildAccountParameter)

        Boolean withoutUpdateBankSlipFeeConfig = Utils.isEmptyOrNull(value)
        if (withoutUpdateBankSlipFeeConfig) return

        currentChildAccountParameter.value = value
        currentChildAccountParameter.save(failOnError: true)

        return currentChildAccountParameter
    }

    public ChildAccountParameter validate(Customer accountOwner, String fieldName, value) {
        ChildAccountParameter validatedParameter = new ChildAccountParameter()

        BusinessValidation businessValidation = canCreateChildAccountParameter(accountOwner)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(validatedParameter, businessValidation.getFirstErrorMessage())
            return validatedParameter
        }

        if (!fieldName) {
            DomainUtils.addError(validatedParameter, "Informe o nome do campo para configuração.")
            return validatedParameter
        }

        if (Utils.isEmptyOrNull(value) && !PARAMETERS_ALLOWED_EMPTY_VALUE_LIST.contains(fieldName)) {
            DomainUtils.addError(validatedParameter, "Informe um valor válido.")
            return validatedParameter
        }

        return validatedParameter
    }

    public ChildAccountParameter delete(ChildAccountParameter childAccountParameter) {
        childAccountParameter.deleted = true
        childAccountParameter.save(failOnError: true)

        return childAccountParameter
    }

    private BusinessValidation canCreateChildAccountParameter(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customer.accountOwner) {
            businessValidation.addError("customer.setChildAccountParameter.alreadyHasAccountOwner")
            return businessValidation
        }

        return businessValidation
    }

    private ChildAccountParameter save(Customer accountOwner, String type, String name, value) {
        ChildAccountParameter childAccountParameter = new ChildAccountParameter()
        childAccountParameter.accountOwner = accountOwner
        childAccountParameter.type = type
        childAccountParameter.name = name
        childAccountParameter.value = value
        childAccountParameter.save(failOnError: true)

        return childAccountParameter
    }

    private Object buildValueToCompareForReplication(Long accountOwnerId, String fieldName, String type, Object value) {
        if (PixCreditFee.OVERPRICE_COMMISSIONABLE_FIELD_LIST.contains(fieldName) && type == PixCreditFee.simpleName) {
            return buildValueToCompareForPixReplication(accountOwnerId, fieldName, value)
        }

        if (BankSlipFee.OVERPRICE_COMMISSIONABLE_FIELD_LIST.contains(fieldName) && type == BankSlipFee.simpleName) {
            return buildValueToCompareForBankSlipReplication(accountOwnerId, fieldName, value)
        }

        if (CustomerFee.OVERPRICE_COMMISSIONABLE_FIELD_LIST.contains(fieldName) && type == CustomerFee.simpleName) {
            return buildValueToCompareForCustomerFeeReplication(accountOwnerId, fieldName, value)
        }

        return value
    }

    private BigDecimal buildValueToCompareForPixReplication(Long accountOwnerId, String fieldName, Object value) {
        BigDecimal convertedValue = new BigDecimal(value.toString())
        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.query([customerId: accountOwnerId, readOnly: true]).get()
        if (fieldName == "percentageFee") return convertedValue + (customerCommissionConfig?.pixFeePercentageWithOverprice ?: 0.0)

        if (fieldName == "minimumFee") return convertedValue + (customerCommissionConfig?.pixMinimumFee ?: 0.0)

        if (fieldName == "maximumFee") return convertedValue + (customerCommissionConfig?.pixMaximumFee ?: 0.0)

        if (fieldName == "fixedFee") return convertedValue + (customerCommissionConfig?.pixFeeFixedValueWithOverprice ?: 0.0)

        if (fieldName == "fixedFeeWithDiscount") return convertedValue + (customerCommissionConfig?.pixFeeFixedValueWithOverprice ?: 0.0)

        return value
    }

    private BigDecimal buildValueToCompareForBankSlipReplication(Long accountOwnerId, String fieldName, Object value) {
        BigDecimal convertedValue = new BigDecimal(value.toString())
        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.query([customerId: accountOwnerId, readOnly: true]).get()
        if (fieldName == "defaultValue") {
            return convertedValue + (customerCommissionConfig?.bankSlipFeeFixedValueWithOverprice ?: 0.0)
        }

        if (fieldName == "discountValue") {
            return convertedValue + (customerCommissionConfig?.bankSlipFeeFixedValueWithOverprice ?: 0.0)
        }

        return value
    }

    private BigDecimal buildValueToCompareForCustomerFeeReplication(Long accountOwnerId, String fieldName, Object value) {
        BigDecimal convertedValue = new BigDecimal(value.toString())
        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.query([customerId: accountOwnerId, readOnly: true]).get()

        if (!customerCommissionConfig) return convertedValue

        if (fieldName == "invoiceValue") return convertedValue + (customerCommissionConfig.invoiceFeeFixedValueWithOverprice ?: 0.0)

        if (fieldName == "dunningCreditBureauFeeValue") return convertedValue + (customerCommissionConfig.dunningCreditBureauFeeFixedValueWithOverprice ?: 0.0)

        return convertedValue
    }
}
