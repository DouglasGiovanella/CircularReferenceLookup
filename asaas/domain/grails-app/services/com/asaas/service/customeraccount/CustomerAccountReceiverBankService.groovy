package com.asaas.service.customeraccount

import com.asaas.domain.customeraccount.CustomerAccountReceiverBank

import grails.transaction.Transactional

@Transactional
class CustomerAccountReceiverBankService {

    public CustomerAccountReceiverBank save(String cpfCnpj, String receiverBankCode) {
        if (!cpfCnpj || !receiverBankCode) return

        CustomerAccountReceiverBank customerAccountReceiverBank = CustomerAccountReceiverBank.query([cpfCnpj: cpfCnpj]).get()
        if (!customerAccountReceiverBank) customerAccountReceiverBank = new CustomerAccountReceiverBank(cpfCnpj: cpfCnpj)

        customerAccountReceiverBank.bankCode = receiverBankCode
        customerAccountReceiverBank.lastPaymentDate = new Date().clearTime()
        customerAccountReceiverBank.save(failOnError: true)

        return customerAccountReceiverBank
    }

}