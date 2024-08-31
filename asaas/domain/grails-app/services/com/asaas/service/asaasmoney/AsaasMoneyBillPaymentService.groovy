package com.asaas.service.asaasmoney

import com.asaas.api.ApiBillParser
import com.asaas.domain.bill.Bill
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.linhadigitavel.InvalidLinhaDigitavelException
import grails.transaction.Transactional

@Transactional
class AsaasMoneyBillPaymentService {

    def billService

    public Bill save(Map params, String payerCustomerPublicId) {
        if (!payerCustomerPublicId) throw new BusinessException("Pagador não informado.")

        Customer customer = Customer.query([publicId: payerCustomerPublicId]).get()
        if (!customer) throw new BusinessException("O pagador não foi encontrado.")

        try {
            Map linhaDigitavelMap = billService.getAndValidateLinhaDigitavelInfo(params.identificationField, null)
            params = ApiBillParser.parseSaveParams(params, linhaDigitavelMap)
        } catch (InvalidLinhaDigitavelException e) {
            throw new BusinessException(e.message)
        }

        return billService.save(customer, params)
    }

    public cancel(String id, String payerCustomerPublicId) {
        if (!payerCustomerPublicId) throw new BusinessException("Pagador não informado.")

        Customer customer = Customer.query([publicId: payerCustomerPublicId]).get()
        if (!customer) throw new BusinessException("O pagador não foi encontrado.")

        return billService.cancel(customer, id)
    }
}
