package com.asaas.service.invoice

import grails.transaction.Transactional
import com.asaas.domain.payment.Payment
import com.asaas.invoice.InvoiceCreationPeriod
import com.asaas.domain.invoice.Invoice
import com.asaas.invoice.InvoiceFiscalConfigVO
import com.asaas.domain.invoice.InvoiceFiscalConfig

@Transactional
class InvoiceFiscalConfigService {

	public InvoiceFiscalConfig save(Invoice invoice, InvoiceFiscalConfigVO invoiceFiscalConfigVo) {
        InvoiceFiscalConfig invoiceFiscalConfig = build(invoiceFiscalConfigVo)
        
        if (invoiceFiscalConfig.invoiceFirstPaymentOnCreation) {
            invoiceFiscalConfig.invoiceCreationPeriod = InvoiceCreationPeriod.ON_PAYMENT_CREATION
        }

        if (invoiceFiscalConfig.invoiceCreationPeriod != InvoiceCreationPeriod.BEFORE_PAYMENT_DUE_DATE) {
            invoiceFiscalConfig.daysBeforeDueDate = 0
        }

        invoiceFiscalConfig.invoice = invoice
        invoiceFiscalConfig.save(failOnError: false)
        invoice.fiscalConfig = invoiceFiscalConfig

        return invoiceFiscalConfig
    }

    private InvoiceFiscalConfig build(InvoiceFiscalConfigVO invoiceFiscalConfigVo) {
        InvoiceFiscalConfig invoiceFiscalConfig = new InvoiceFiscalConfig()

        invoiceFiscalConfig.invoiceCreationPeriod = invoiceFiscalConfigVo.invoiceCreationPeriod
        invoiceFiscalConfig.receivedOnly = invoiceFiscalConfigVo.receivedOnly
        invoiceFiscalConfig.daysBeforeDueDate = invoiceFiscalConfigVo.daysBeforeDueDate
        invoiceFiscalConfig.invoiceOnceForAllPayments = invoiceFiscalConfigVo.invoiceOnceForAllPayments
        invoiceFiscalConfig.invoiceFirstPaymentOnCreation = invoiceFiscalConfigVo.invoiceFirstPaymentOnCreation

        return invoiceFiscalConfig
    }
}
