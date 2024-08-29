package com.asaas.service.invoice

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.invoice.Invoice
import com.asaas.invoice.InvoiceOriginType
import com.asaas.invoice.InvoiceProvider
import com.asaas.invoice.InvoiceStatus
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class AsaasErpCustomerInvoiceService {

    private static final InvoiceOriginType INVOICE_ORIGIN_TYPE = InvoiceOriginType.DETACHED

    def customerInvoiceService
    def customerProductService
    def invoiceFileService
    def invoiceService
    def paymentCustomerProductService

    public Invoice saveAuthorizedInvoice(Customer customer, Map params) {
        String externalId = "asaas_erp_${params.externalReference}"

        Boolean externalIdAlreadyExists = Invoice.query([column: "id", externalId:externalId ]).get().asBoolean()
        if (externalIdAlreadyExists) {
            Invoice invoiceWithError = new Invoice()
            DomainUtils.addError(invoiceWithError, "Nota fiscal já cadastrada!")
            return invoiceWithError
        }

        params.originType = AsaasErpCustomerInvoiceService.INVOICE_ORIGIN_TYPE
        params.invoiceProvider = InvoiceProvider.ASAAS_ERP
        params.customerAccount = CustomerAccount.find(params.customerAccountId, customer.id)

        CustomerProduct customerProduct = customerProductService.findOrSave(customer, params.customerProductVO)
        params.paymentCustomerProduct = paymentCustomerProductService.save(customerProduct)

        Invoice invoice = customerInvoiceService.save(customer, params.invoiceFiscalVO, params)
        if (invoice.hasErrors()) return invoice

        invoice.status = InvoiceStatus.AUTHORIZED
        invoice.externalId = externalId
        invoice.pdfUrl = params.pdfUrl
        invoice.xmlUrl = params.xmlUrl
        invoice.number = params.number
        invoice.validationCode = params.validationCode
        invoice.rpsSerie = params.rpsSerie
        invoice.rpsNumber = params.rpsNumber
        invoice.save(failOnError: true)

        invoiceFileService.downloadCustomerInvoiceFiles(invoice)

        customerInvoiceService.applyFeeIfNecessary(invoice)

        invoiceService.notifyInvoiceStatusChange(invoice)

        return invoice
    }

    public Invoice cancelAuthorizedInvoice(Long customerId, Map params) {
        Invoice invoice = Invoice.query([customerId: customerId, externalReference: params.externalReference, status: InvoiceStatus.AUTHORIZED]).get()
        if (!invoice) {
            Invoice invoiceWithError = new Invoice()
            DomainUtils.addError(invoiceWithError, "Nota fiscal não encontrada.")
            return invoiceWithError
        }

        invoiceService.setAsCanceled(invoice)

        invoice.pdfUrl = params.pdfUrl
        invoice.xmlUrl = params.xmlUrl
        invoice.save(failOnError: true)

        invoiceFileService.downloadCustomerInvoiceFiles(invoice)

        return invoice
    }

    public BusinessValidation canRequest(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        BigDecimal customerRequiredBalanceToInvoice = Invoice.getCustomerRequiredBalanceToInvoice(customer, AsaasErpCustomerInvoiceService.INVOICE_ORIGIN_TYPE)
        if (!customer.hasSufficientBalance(customerRequiredBalanceToInvoice)) {
            businessValidation.addError("customer.invoice.validation.error.hasSufficientBalance")
            return businessValidation
        }

        return businessValidation
    }
}
