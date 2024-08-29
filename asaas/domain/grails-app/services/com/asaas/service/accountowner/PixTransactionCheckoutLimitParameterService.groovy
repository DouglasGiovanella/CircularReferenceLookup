package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class PixTransactionCheckoutLimitParameterService {

    private static final List<String> ALLOWED_PIX_CHECKOUT_LIMIT_CONFIG_FIELD_LIST = ["daytimeLimitPerTransaction", "nightlyLimitPerTransaction", "daytimeLimit", "nightlyLimit"]

    def childAccountParameterService
    def childAccountParameterInteractionService
    def pixTransactionCheckoutLimitService
    def childAccountParameterParserService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: PixTransactionCheckoutLimit.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        BigDecimal value = childAccountParameterParserService.parse(childAccountParameter)
        pixTransactionCheckoutLimitService.save(childAccount.id, [(childAccountParameter.name): value], false, true)
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Object value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, PixTransactionCheckoutLimit.simpleName, fieldName, value)

        if (!childAccountParameter.hasErrors()) childAccountParameterInteractionService.savePixTransactionCheckoutLimit(accountOwner, fieldName, value)

        return childAccountParameter
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, String fieldName, Object value) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        if (!ALLOWED_PIX_CHECKOUT_LIMIT_CONFIG_FIELD_LIST.contains(fieldName)) {
            return DomainUtils.addError(validatedParameter, "Não é permitida a configuração desse campo.")
        }

        return validatedParameter
    }
}
