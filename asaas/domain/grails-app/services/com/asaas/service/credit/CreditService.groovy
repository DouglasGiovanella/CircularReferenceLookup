package com.asaas.service.credit

import com.asaas.credit.CreditType
import com.asaas.domain.credit.Credit
import com.asaas.domain.customer.Customer
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CreditService {

    def financialTransactionService

    def customerInteractionService

    public Credit save(Long customerId, CreditType type, String description, BigDecimal value, Date effectiveDate) {
        Customer customer = Customer.get(customerId)

        BusinessValidation validatedBusiness = validateSave(customer, type, description, value)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new Credit(), validatedBusiness.getFirstErrorMessage())

        Credit credit = new Credit(customer: customer, type: type, description: description, value: value, derivative: false)
        credit.save(failOnError: true)

        financialTransactionService.saveCredit(credit, null)

        String interactionDescription = "Lançamento de crédito de ${FormUtils.formatCurrencyWithMonetarySymbol(value)} referente a ${Utils.getEnumLabel(type)}.\nDescrição: ${description}."
        customerInteractionService.save(customer, interactionDescription,  UserUtils.getCurrentUser())

        return credit
    }

    private BusinessValidation validateSave(Customer customer, CreditType type, String description, BigDecimal value) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!customer) validatedBusiness.addError("default.null.message", ["cliente"])

        if (!value) validatedBusiness.addError("default.null.message", ["valor"])

        if (value < 0) validatedBusiness.addError("default.error.minValue", ["valor", "R\$ 0,00"])

        if (!type) validatedBusiness.addError("default.null.message", ["tipo de crédito"])

        if (!description) validatedBusiness.addError("default.null.message", ["descrição"])

        return validatedBusiness
    }
}
