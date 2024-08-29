package com.asaas.service.invoice

import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceExtraInfo

import grails.transaction.Transactional

@Transactional
class InvoiceExtraInfoService {

    public InvoiceExtraInfo saveAuthorizationDate(Invoice invoice, Date authorizationDate) {
        InvoiceExtraInfo invoiceExtraInfo = new InvoiceExtraInfo()

        invoiceExtraInfo.invoice = invoice
        invoiceExtraInfo.customer = invoice.customer
        invoiceExtraInfo.authorizationDate = authorizationDate

        return invoiceExtraInfo.save(failOnError: true)
    }

}
