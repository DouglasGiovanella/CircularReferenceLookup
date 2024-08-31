package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.customer.Customer
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerAttributesParameterService {
    private static final List<String> ALLOWED_CUSTOMER_ATTRIBUTE_CONFIG_FIELD_LIST = ["boletoBankId", "softDescriptorText"]

    def childAccountParameterParserService
    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerAdminService
    def customerService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: Customer.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        switch (childAccountParameter.name) {
            case "softDescriptorText":
                customerAdminService.updateSoftDescriptor(childAccount.id, childAccountParameter.value)
                break
            case "boletoBankId":
                final String customerBoletoBankInfoReason = "Copiando dados da conta pai"
                customerService.changeToRegisteredBoleto(childAccount.id, childAccountParameterParserService.parse(childAccountParameter), customerBoletoBankInfoReason, false)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Object value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, Customer.simpleName, fieldName, value)

        if (!childAccountParameter.hasErrors()) childAccountParameterInteractionService.saveCustomerAttributesConfig(accountOwner, childAccountParameter)

        return childAccountParameter
    }

    public Object parseCustomerAttributesConfigValueForApply(String fieldName, value) {
        switch (fieldName) {
            case "boletoBankId":
                return Utils.toLong(value)
            case "softDescriptorText":
                return String.valueOf(value)
            default:
                throw new NotImplementedException()
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, String fieldName, Object value) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        if (!ALLOWED_CUSTOMER_ATTRIBUTE_CONFIG_FIELD_LIST.contains(fieldName)) return DomainUtils.addError(validatedParameter, "Não é permitida a configuração desse campo.")

        if (fieldName == "boletoBankId") {
            BoletoBank boletoBank = BoletoBank.read(value)
            if (!boletoBank) return DomainUtils.addError(validatedParameter, "Informe um banco emissor de boletos válido.")
        }

        return validatedParameter
    }
}
