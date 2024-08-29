package com.asaas.service.judicialprocessmanualbalanceblock

import com.asaas.debit.DebitType
import com.asaas.domain.customer.Customer
import com.asaas.domain.debit.Debit
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class JudicialProcessManualBalanceBlockService {

    def debitService

    public void save(Customer customer, BigDecimal value, String description) {
        BusinessValidation businessValidation = validateSave(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        Debit debit = debitService.save(customer, value, DebitType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK, description, null)

        if (debit.hasErrors()) throw new BusinessException(Utils.getMessageProperty(debit.errors.allErrors.first()))
    }

    public void reverse(Customer customer) {
        BusinessValidation businessValidation = validateReverse(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        debitService.reverse(getActiveBalanceBlockId(customer))
    }

    public BusinessValidation validateSave(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean hasActiveBalanceBlock = getActiveBalanceBlockId(customer).asBoolean()
        if (hasActiveBalanceBlock) {
            businessValidation.addError("judicialProcessManualBalanceBlockService.error.hasActiveBalanceBlock")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation validateReverse(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean hasActiveBalanceBlock = getActiveBalanceBlockId(customer).asBoolean()
        if (!hasActiveBalanceBlock) {
            businessValidation.addError("judicialProcessManualBalanceBlockService.error.hasNotActiveBalanceBlock")
            return businessValidation
        }

        return businessValidation
    }

    private Long getActiveBalanceBlockId(Customer customer) {
        Map search = [:]
        search.customer = customer
        search.type = DebitType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK
        search.exists = true

        return Debit.query(search).get()
    }
}
