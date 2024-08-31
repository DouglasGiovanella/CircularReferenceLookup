package com.asaas.service.accountowner

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.internalloan.InternalLoanConfig
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.utils.Utils
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class ChildAccountParameterParserService {

    def bankSlipFeeParameterService
    def customerAttributesParameterService
    def customerConfigParameterService
    def customerFeeParameterService
    def customerParameterConfigService
    def pixCreditFeeParameterService
    def creditCardFeeConfigParameterService

    public Object parse(ChildAccountParameter childAccountParameter) {
        switch (childAccountParameter.type) {
            case BankSlipFee.simpleName:
                return bankSlipFeeParameterService.parseBankSlipFeeConfigValueForApply(childAccountParameter.name, childAccountParameter.value)
            case PixCreditFee.simpleName:
                return pixCreditFeeParameterService.parsePixCreditFeeConfigValueForApply(childAccountParameter.name, childAccountParameter.value)
            case CustomerReceivableAnticipationConfig.simpleName:
                BigDecimal bigDecimalValue = Utils.toBigDecimal(childAccountParameter.value)
                if (bigDecimalValue instanceof BigDecimal) return bigDecimalValue
                return Boolean.valueOf(childAccountParameter.value)
            case PixTransactionCheckoutLimit.simpleName:
                return Utils.toBigDecimal(childAccountParameter.value)
            case CustomerParameter.simpleName:
                CustomerParameterName convertedChildAccountParameter = CustomerParameterName.convert(childAccountParameter.name)
                return customerParameterConfigService.parseCustomerParameterValue(convertedChildAccountParameter.valueType, childAccountParameter.value)
            case CustomerCriticalActionConfig.simpleName:
            case InternalLoanConfig.simpleName:
                return childAccountParameter.value ? Boolean.valueOf(childAccountParameter.value) : childAccountParameter.value
            case Customer.simpleName:
                return customerAttributesParameterService.parseCustomerAttributesConfigValueForApply(childAccountParameter.name, childAccountParameter.value)
            case CustomerFee.simpleName:
                return customerFeeParameterService.parseParameterValue(childAccountParameter.name, childAccountParameter.value)
            case CreditCardFeeConfig.simpleName:
                return creditCardFeeConfigParameterService.parseCreditCardFeeConfigValueForApply(childAccountParameter.name, childAccountParameter.value)
            case CustomerConfig.simpleName:
                return customerConfigParameterService.parseParameterValue(childAccountParameter.name, childAccountParameter.value)
            case CustomerFeature.simpleName:
                return childAccountParameter.value ? Boolean.valueOf(childAccountParameter.value) : childAccountParameter.value
            default:
                throw new NotImplementedException()
        }
    }
}
