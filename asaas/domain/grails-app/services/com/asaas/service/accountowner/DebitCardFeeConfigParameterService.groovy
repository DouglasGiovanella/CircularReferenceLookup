package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class DebitCardFeeConfigParameterService {

    def childAccountParameterService
    def childAccountParameterInteractionService

    public Map parseDebitCardFeeConfigForSave(String debitCardFixedFee, String debitCardFee) {
        Map debitCardFeeConfig = [
            "debitCardFixedFee": Utils.toBigDecimal(debitCardFixedFee),
            "debitCardFee": Utils.toBigDecimal(debitCardFee),
        ]

        return debitCardFeeConfig
    }

    public void saveParameter(Long accountOwnerId, Map debitCardFeeConfig) {
        Customer accountOwner = Customer.read(accountOwnerId)

        ChildAccountParameter validatedParameters = validateParameters(accountOwner, debitCardFeeConfig)
        if (validatedParameters.hasErrors()) throw new ValidationException("Não foi possível salvar as configurações de taxa de cartão de débito", validatedParameters.errors)

        debitCardFeeConfig.each { childAccountParameterService.saveOrUpdate(accountOwner, CreditCardFeeConfig.simpleName, it.key, it.value) }
        childAccountParameterInteractionService.saveCreditCardFee(accountOwner, debitCardFeeConfig)
    }

    private ChildAccountParameter validateParameters(Customer customer, Map debitCardFeeConfig) {
        ChildAccountParameter validatedParameters = new ChildAccountParameter()

        if (customer.accountOwner) {
            return DomainUtils.addError(validatedParameters, Utils.getMessageProperty("customer.setChildAccountParameter.alreadyHasAccountOwner"))
        }

        List<String> allowedFieldList = CreditCardFeeConfig.ALLOWED_DEBIT_CARD_FEE_CONFIG_FIELD_LIST
        for (fieldName in debitCardFeeConfig.keySet()) {
            if (!allowedFieldList.contains(fieldName)) {
                return DomainUtils.addError(validatedParameters, "Não é permitida a configuração do campo [${Utils.getMessageProperty("creditCardFeeConfig.${fieldName}.label")}].")
            }
        }

        if ([debitCardFeeConfig.debitCardFee, debitCardFeeConfig.debitCardFixedFee].any { !it }) {
            DomainUtils.addError(validatedParameters, "Informe um valor válido.")
        }

        return validatedParameters
    }
}
