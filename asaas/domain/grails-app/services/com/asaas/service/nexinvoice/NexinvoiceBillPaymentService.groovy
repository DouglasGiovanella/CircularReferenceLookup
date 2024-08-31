package com.asaas.service.nexinvoice

import com.asaas.domain.bill.Bill
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.integration.nexinvoice.adapter.BillAdapter
import com.asaas.linhadigitavel.InvalidLinhaDigitavelException

import grails.transaction.Transactional

@Transactional
class NexinvoiceBillPaymentService {

    def billService

    public Bill save(BillAdapter billAdapter) {
        try {
            Map linhaDigitavelMap = billService.getAndValidateLinhaDigitavelInfo(billAdapter.linhaDigitavel, null)
            billAdapter = billAdapter.processLinhaDigitavelInfo(linhaDigitavelMap)
        } catch (InvalidLinhaDigitavelException e) {
            throw new BusinessException(e.message)
        }

        return billService.save(Customer.get(billAdapter.customerId), billAdapter.properties)
    }
}
