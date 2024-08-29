package com.asaas.service.internalloan

import com.asaas.domain.customer.Customer
import com.asaas.domain.internalloan.InternalLoanConfig
import grails.transaction.Transactional

@Transactional
class InternalLoanConfigService {

    def internalLoanService

    public InternalLoanConfig saveOrUpdate(Customer guarantor, Customer debtor, Boolean enabled) {
        InternalLoanConfig internalLoanConfig = InternalLoanConfig.query([guarantor: guarantor, debtor: debtor]).get()

        Boolean isDisablingInternalLoan = internalLoanConfig && !enabled
        if (isDisablingInternalLoan) internalLoanService.cancelAllPendingLoansByDebtorAndGuarantor(debtor, guarantor)

        if (!internalLoanConfig) {
            internalLoanConfig = new InternalLoanConfig()
            internalLoanConfig.guarantor = guarantor
            internalLoanConfig.debtor = debtor
        }
        internalLoanConfig.enabled = enabled
        internalLoanConfig.save(failOnError: true)

        return internalLoanConfig
    }

    public InternalLoanConfig delete(Customer guarantor, Customer debtor) {
        InternalLoanConfig internalLoanConfig = InternalLoanConfig.query([guarantor: guarantor, debtor: debtor]).get()
        internalLoanConfig.deleted = true
        internalLoanConfig.save(failOnError: true)

        return internalLoanConfig
    }
}
