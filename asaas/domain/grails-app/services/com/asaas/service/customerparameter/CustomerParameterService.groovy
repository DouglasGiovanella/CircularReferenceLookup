package com.asaas.service.customerparameter

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.customer.CustomerParameterName
import com.asaas.customerstatistic.CustomerStatisticName
import com.asaas.customer.CustomerSegment
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.exception.BusinessException
import com.asaas.plan.CustomerPlanName
import com.asaas.status.Status
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerParameterService {

    def customerInteractionService
    def customerParameterCacheService
    def customerParameterValidationService
    def notificationDispatcherCustomerOutboxService
    def sageAccountParameterService

    public CustomerParameter save(Customer customer, CustomerParameterName name, value) {
        CustomerParameter parameter = CustomerParameter.findOrCreateWhere(customer: customer, name: name)

        if (parameter.value == value || parameter.stringValue == value || parameter.numericValue == value) {
            DomainUtils.addError(parameter, "Não houve alteração entre o valor atual e o novo valor do parâmetro. Valor: ${value}")
            return parameter
        }

        if (name.getValueType() != value.getClass()) {
            DomainUtils.addError(parameter, "O tipo do valor do parâmetro enviado [${value.getClass()}] é diferente do tipo do valor suportado pelo CustomerParameter [${name.getValueType()}]")
            return parameter
        }

        BusinessValidation businessValidation = validateParameterValue(customer.segment, name, value)
        if (!businessValidation.isValid()) {
            return DomainUtils.addError(parameter, businessValidation.getFirstErrorMessage())
        }

        String valueDescription
        String previousValueDescription

        switch (value.getClass()) {
            case BigDecimal:
                previousValueDescription = "${parameter.numericValue ?: '0,00'}"
                parameter.numericValue = value
                valueDescription = "${value ?: '0,00'}"
                break
            case Boolean:
                previousValueDescription = "${parameter.value  ? 'habilitada' : 'desabilitada'}"
                parameter.value = value
                valueDescription = "${value ? 'habilitada' : 'desabilitada'}"
                break
            case String:
                previousValueDescription = "${parameter.stringValue}"
                parameter.stringValue = value
                valueDescription = "${value}"
                break
            default:
                DomainUtils.addError(parameter, "Parâmetro do tipo [${value.class.simpleName}] não é suportado pelo CustomerParameter.")
                return parameter
        }

        parameter.lastUpdated = new Date()
        parameter.save(failOnError: true)

        if (name.shouldSaveInteractionOnChange()) {
            customerInteractionService.saveCustomerParameterChange(customer, name, valueDescription, previousValueDescription)
        }

        customerParameterCacheService.evict(customer, name)
        notificationDispatcherCustomerOutboxService.onCustomerParameterUpdated(parameter)
        sageAccountParameterService.onCustomerParameterUpdated(parameter)

        return parameter
    }

    public CustomerParameter ignoreProviderStatusOnNextTransfer(Customer customer) {
		if (!customer.bankAccountInfoApprovalIsNotRequired() && customer.customerRegisterStatus.bankAccountInfoStatus != Status.APPROVED) {
			throw new BusinessException("O fornecedor precisa ter a conta bancária aprovada.")
		}

		return save(customer, CustomerParameterName.IGNORE_PROVIDER_REGISTER_STATUS_ON_NEXT_TRANSFER, true)
	}

    public void setAsLargeCustomerBaseIfNecessary(Customer customer) {
        Boolean hasLargeBase = CustomerParameter.getValue(customer, CustomerParameterName.LARGE_CUSTOMER_BASE)

        if (!hasLargeBase) {
            Long customerAccountCount = CustomerStatistic.getIntegerValue(customer, CustomerStatisticName.TOTAL_CUSTOMER_ACCOUNT_COUNT) + 1
            if (customerAccountCount != AsaasApplicationHolder.config.asaas.customer.largeBase.customerAccountCount) return

            save(customer, CustomerParameterName.LARGE_CUSTOMER_BASE, true)
        }
    }

    public List<CustomerParameterName> getDefaultFeaturesForCustomerPlan(CustomerPlanName customerPlanName) {
        switch (customerPlanName) {
            case CustomerPlanName.ADVANCED:
                return CustomerParameterName.getDefaultParametersForPlanStandard() +
                    CustomerParameterName.getDefaultParametersForPlanAdvanced()
            case CustomerPlanName.STANDARD:
                return CustomerParameterName.getDefaultParametersForPlanStandard()
            case CustomerPlanName.LITE:
                return []
            default:
                return []
        }
    }

    public BusinessValidation validateParameterValue(CustomerSegment customerSegment, CustomerParameterName customerParameterName, value) {
        BusinessValidation businessValidation = new BusinessValidation()

        businessValidation = customerParameterValidationService.validateMinimumValueToForceRiskValidation(customerParameterName, value)
        if (!businessValidation.isValid()) return businessValidation

        businessValidation = customerParameterValidationService.validateCustomDaysToCreateSubscriptionPayments(customerParameterName, value)
        if (!businessValidation.isValid()) return businessValidation

        businessValidation = customerParameterValidationService.validateIgnoreCustomerSegmentOnCreditCardRiskValidationRule(customerSegment, customerParameterName)
        if (!businessValidation.isValid()) return businessValidation

        businessValidation = customerParameterValidationService.validateCustomCreditCardSettlementDays(customerParameterName, value)
        if (!businessValidation.isValid()) return businessValidation

        businessValidation = customerParameterValidationService.validateMinimumDetachedBankSlipAndPixValue(customerParameterName, value)
        if (!businessValidation.isValid()) return businessValidation

        businessValidation = customerParameterValidationService.validateMinimumDetachedCreditCardValue(customerParameterName, value)
        if (!businessValidation.isValid()) return businessValidation

        return businessValidation
    }

    public void saveAccountOwnerWhiteLabelParameters(Long customerId, Boolean value) {
        Customer customer = Customer.get(customerId)

        List<CustomerParameterName> ownerParameterList = CustomerParameterName.listAccountOwnerWhiteLabelParameter()

        for (CustomerParameterName parameter : ownerParameterList) {
            save(customer, parameter, value)
        }
    }

    public Object parseParameterValue(CustomerParameterName name, value) {
        Boolean isValidValue = validateToParseValue(name, value)
        if (!isValidValue) throw new BusinessException ("O parametro ${name} só permite valor do tipo ${name.getValueType()}")

        switch (name.getValueType()) {
            case Boolean:
                return Utils.toBoolean(value)
            case BigDecimal:
                return Utils.toBigDecimal(value)
            case String:
                return String.valueOf(value)
            default:
                throw new NotImplementedException()
        }
    }

    public void saveInternalUserParameters(Customer customer) {
        List<CustomerParameterName> internalUserCustomerParameters = CustomerParameterName.listInternalUserParameters()

        for (CustomerParameterName parameter : internalUserCustomerParameters) {
            save(customer, parameter, true)
        }
    }

    private Boolean validateToParseValue(CustomerParameterName name, value) {
        switch (name.getValueType()) {
            case Boolean:
                return Utils.isValidBooleanValue(value)
            case BigDecimal:
                return Utils.isValidBigDecimalValue(value)
            case String:
                return !Utils.isEmptyOrNull(value)
            default:
                throw new NotImplementedException()
        }
    }
}
