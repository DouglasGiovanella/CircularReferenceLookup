package com.asaas.service.invoice

import com.asaas.consumerinvoice.productinvoice.ConsumerInvoiceProvider
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.ConsumerInvoice
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ConsumerInvoiceService {

    def chargedFeeConsumerInvoiceService
    def chargedFeeService

    public ConsumerInvoice save(Customer customer, ConsumerInvoiceProvider consumerInvoiceProvider, Map params) {
        ConsumerInvoice validateConsumerInvoice = validateSave(customer, consumerInvoiceProvider, params)
        if (validateConsumerInvoice.hasErrors()) return validateConsumerInvoice

        ConsumerInvoice consumerInvoice = new ConsumerInvoice()
        consumerInvoice.customer = customer
        consumerInvoice.consumerInvoiceProvider = consumerInvoiceProvider
        consumerInvoice.externalId = params.externalId
        consumerInvoice.effectiveDate = params.effectiveDate
        consumerInvoice.number = params.number
        consumerInvoice.value = params.value
        consumerInvoice.pdfUrl = params.pdfUrl
        consumerInvoice.xmlUrl = params.xmlUrl
        consumerInvoice.authorizationDate = new Date()

        consumerInvoice.save(failOnError: true)

        ChargedFee chargedFee = chargedFeeService.saveConsumerInvoiceFee(consumerInvoice)

        chargedFeeConsumerInvoiceService.save(consumerInvoice, chargedFee)

        return consumerInvoice
    }

    public BusinessValidation canRequest(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        BigDecimal requiredBalance = ConsumerInvoice.CUSTOMER_MINIMUM_REQUIRED_BALANCE

        if (customer.customerConfig.maxNegativeBalance) {
            requiredBalance = BigDecimalUtils.negate(customer.customerConfig.maxNegativeBalance)
        }

        if (!customer.hasSufficientBalance(requiredBalance)) {
            businessValidation.addError("customer.invoice.validation.error.hasSufficientBalance")
            return businessValidation
        }

        return businessValidation
    }

    private ConsumerInvoice validateSave(Customer customer, ConsumerInvoiceProvider consumerInvoiceProvider, Map params) {
        ConsumerInvoice consumerInvoice = new ConsumerInvoice()

        Boolean alreadyExists = ConsumerInvoice.query([
            customer               : customer,
            consumerInvoiceProvider: consumerInvoiceProvider,
            externalId             : params.externalId,
            exists                 : true,
        ]).get().asBoolean()

        if (alreadyExists) {
            String errorMessage = Utils.getMessageProperty('customer.consumerInvoice.validation.error.alreadyExists', [params.externalId])
            DomainUtils.addError(consumerInvoice, errorMessage)
        }

        return consumerInvoice
    }
}
