package com.asaas.service.customerfee

import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.domain.salespartner.SalesPartnerCustomer
import com.asaas.exception.BusinessException
import com.asaas.feeConfiguration.adapter.CustomerFeeAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerFeeService {

    def customerInteractionService

    public CustomerFee saveForNewCustomer(Customer customer) {
        if (customer.accountOwner) {
            Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: customer.accountOwner.id]).get().asBoolean()

            if (!hasCustomerDealInfo) return replicateAccountOwnerConfig(customer)
        }

        CustomerFee customerFee = new CustomerFee([customer: customer])

        SalesPartner salesPartner = SalesPartnerCustomer.query([customer: customer, column: 'salesPartner']).get()

        BigDecimal dunningCreditBureauFeeValue = customerFee.dunningCreditBureauFeeValue
        if (salesPartner && salesPartner.creditBureauDunningFee) {
            dunningCreditBureauFeeValue = salesPartner.creditBureauDunningFee
        }

        customerFee.dunningCreditBureauFeeValue = calculateDunningCreditBureauFeeValueWithOverprice(dunningCreditBureauFeeValue, customer)
        customerFee.invoiceValue = calculateInvoiceFeeValueWithOverprice(customerFee.invoiceValue, customer)

        customerFee.save(failOnError: true)

        return customerFee
    }

    public CustomerFee save(Customer customer, Map customerFeeConfig) {
        CustomerFee customerFee = CustomerFee.find(customer)
        if (!customerFee) {
            customerFee = new CustomerFee()
            customerFee.customer = customer
        }

        customerFee.properties [
            "invoiceValue", "productInvoiceValue", "consumerInvoiceValue", "transferValue",
            "paymentMessagingNotificationFeeValue", "paymentSmsNotificationFeeValue", "alwaysChargeTransferFee",
            "dunningCreditBureauFeeValue", "pixDebitFee", "creditBureauReportNaturalPersonFee",
            "creditBureauReportLegalPersonFee", "whatsappNotificationFee", "childAccountKnownYourCustomerFee",
            "phoneCallNotificationFee"] = customerFeeConfig

        if (customerFeeConfig.containsKey("invoiceValue")) {
            customerFee.invoiceValue = calculateInvoiceFeeValueWithOverprice(customerFeeConfig.invoiceValue, customer)
        }

        if (customerFeeConfig.containsKey("dunningCreditBureauFeeValue")) {
            customerFee.dunningCreditBureauFeeValue = calculateDunningCreditBureauFeeValueWithOverprice(customerFeeConfig.dunningCreditBureauFeeValue, customer)
        }

        customerFee.save(flush: true, failOnError: true)
        customerInteractionService.saveCustomerFeeIfNecessary(customer, customerFeeConfig)

        return customerFee
    }

    public CustomerFee replicateAccountOwnerConfig(Customer childAccount) {
        if (!childAccount?.accountOwner) throw new BusinessException(Utils.getMessageProperty("customer.dontHaveAccountOwner"))

        CustomerFee accountOwnerCustomerFee = CustomerFee.query([customer: childAccount.accountOwner]).get()
        return save(childAccount, [invoiceValue: accountOwnerCustomerFee.invoiceValue, dunningCreditBureauFeeValue: accountOwnerCustomerFee.dunningCreditBureauFeeValue, productInvoiceValue: accountOwnerCustomerFee.productInvoiceValue, consumerInvoiceValue: accountOwnerCustomerFee.consumerInvoiceValue, transferValue: accountOwnerCustomerFee.transferValue, pixDebitFee: accountOwnerCustomerFee.pixDebitFee])
    }

    public CustomerFeeAdapter calculateCustomerFeeWithoutOverprice(Customer customer) {
        List<String> customerFeeColumnList = ["invoiceValue", "dunningCreditBureauFeeValue"]
        Map customerFee = CustomerFee.query([customer: customer, readOnly: true, columnList: customerFeeColumnList]).get()

        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)

        CustomerFeeAdapter customerFeeAdapter = new CustomerFeeAdapter()

        BigDecimal invoiceValue = Utils.toBigDecimal(customerFee?.invoiceValue)
        customerFeeAdapter.invoiceValue = calculateInvoiceFeeValueWithoutOverprice(invoiceValue, customerCommissionConfig?.invoiceFeeFixedValueWithOverprice)

        BigDecimal dunningCreditBureauFeeValue = Utils.toBigDecimal(customerFee?.dunningCreditBureauFeeValue)
        customerFeeAdapter.dunningCreditBureauFeeValue = calculateDunningCreditBureauFeeValueWithoutOverprice(dunningCreditBureauFeeValue, customerCommissionConfig?.dunningCreditBureauFeeFixedValueWithOverprice)

        return customerFeeAdapter
    }

    private BigDecimal calculateInvoiceFeeValueWithoutOverprice(BigDecimal invoiceValue, BigDecimal invoiceFeeFixedValueWithOverprice) {
        if (!invoiceValue) return CustomerFee.SERVICE_INVOICE_FEE

        return invoiceValue - (invoiceFeeFixedValueWithOverprice ?: 0.0)
    }

    private BigDecimal calculateDunningCreditBureauFeeValueWithoutOverprice(BigDecimal dunningCreditBureauFeeValue, BigDecimal dunningCreditBureauFeeFixedValueWithOverprice) {
        if (!dunningCreditBureauFeeValue) return CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE

        return dunningCreditBureauFeeValue - (dunningCreditBureauFeeFixedValueWithOverprice ?: 0.0)
    }

    private BigDecimal calculateInvoiceFeeValueWithOverprice(BigDecimal invoiceValue, Customer customer) {
        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)
        if (!customerCommissionConfig?.invoiceFeeFixedValueWithOverprice) return invoiceValue

        if (!invoiceValue) invoiceValue = CustomerFee.SERVICE_INVOICE_FEE

        return invoiceValue + customerCommissionConfig.invoiceFeeFixedValueWithOverprice
    }

    private BigDecimal calculateDunningCreditBureauFeeValueWithOverprice(BigDecimal dunningCreditBureauFeeValue, Customer customer) {
        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)
        if (!customerCommissionConfig?.dunningCreditBureauFeeFixedValueWithOverprice) return dunningCreditBureauFeeValue

        if (!dunningCreditBureauFeeValue) {
            dunningCreditBureauFeeValue = CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE
        }

        return dunningCreditBureauFeeValue + customerCommissionConfig.dunningCreditBureauFeeFixedValueWithOverprice
    }
}
