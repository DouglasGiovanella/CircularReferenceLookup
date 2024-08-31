package com.asaas.service.accountnumber

import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class AccountNumberService {

    public AccountNumber saveIfNotExists(Customer customer) {
        AccountNumber accountNumber = customer.getAccountNumber()
        if (accountNumber) return accountNumber

        return save(customer)
    }

    public AccountNumber save(Customer customer) {
        AccountNumber accountNumber = new AccountNumber()
        accountNumber.customer = customer
        accountNumber.account = customer.id
        accountNumber.agency = AccountNumber.DEFAULT_AGENCY
        accountNumber.accountDigit = AccountNumber.buildAccountDigit(accountNumber.account.toString())
        accountNumber.save(failOnError: true)
        return accountNumber
    }
}
