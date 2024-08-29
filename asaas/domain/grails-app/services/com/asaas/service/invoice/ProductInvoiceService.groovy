package com.asaas.service.invoice

import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.ProductInvoice
import com.asaas.productinvoice.ProductInvoiceProvider
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ProductInvoiceService {

    def chargedFeeService

    public ProductInvoice save(Customer customer, ProductInvoiceProvider productInvoiceProvider, Map params) {
        ProductInvoice validateProductInvoice = validateSave(customer, productInvoiceProvider, params)
        if (validateProductInvoice.hasErrors()) return validateProductInvoice

        ProductInvoice productInvoice = new ProductInvoice()
        productInvoice.customer = customer
        productInvoice.productInvoiceProvider = productInvoiceProvider
        productInvoice.externalId = params.externalId
        productInvoice.effectiveDate = params.effectiveDate
        productInvoice.number = params.number
        productInvoice.value = params.value
        productInvoice.pdfUrl = params.pdfUrl
        productInvoice.xmlUrl = params.xmlUrl
        productInvoice.authorizationDate = new Date()

        productInvoice.save(failOnError: true)

        chargedFeeService.saveProductInvoiceFee(productInvoice)

        return productInvoice
    }

    public BusinessValidation canRequest(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        BigDecimal requiredBalance = ProductInvoice.CUSTOMER_REQUIRED_BALANCE
        if (customer.customerConfig.maxNegativeBalance) requiredBalance = customer.customerConfig.maxNegativeBalance * -1

        if (!customer.hasSufficientBalance(requiredBalance)) {
            businessValidation.addError("customer.invoice.validation.error.hasSufficientBalance")
            return businessValidation
        }

        return businessValidation
    }

    private ProductInvoice validateSave(Customer customer, ProductInvoiceProvider productInvoiceProvider, Map params) {
        ProductInvoice productInvoice = new ProductInvoice()

        Boolean alreadyExists = ProductInvoice.query([customer: customer, productInvoiceProvider: productInvoiceProvider, externalId: params.externalId]).get().asBoolean()
        if (alreadyExists) {
            DomainUtils.addError(productInvoice, Utils.getMessageProperty('customer.productInvoice.validation.error.alreadyExists', [params.externalId]))
        }

        return productInvoice
    }
}
