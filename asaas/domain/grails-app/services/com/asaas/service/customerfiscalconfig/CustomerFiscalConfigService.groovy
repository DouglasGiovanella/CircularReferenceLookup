package com.asaas.service.customerfiscalconfig

import grails.transaction.Transactional
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFiscalConfig
import com.asaas.invoice.InvoiceEstimatedTaxesType
import com.asaas.utils.Utils

@Transactional
class CustomerFiscalConfigService {

	public CustomerFiscalConfig save(Customer customer, Map params) {
        applyDefaultDescriptionsIfNecessary(params)

        CustomerFiscalConfig customerFiscalConfig = CustomerFiscalConfig.query([customerId: customer.id]).get()
        if (!customerFiscalConfig) customerFiscalConfig = new CustomerFiscalConfig()

        customerFiscalConfig.customer = customer
        customerFiscalConfig.invoiceEstimatedTaxesType = InvoiceEstimatedTaxesType.convert(params.invoiceEstimatedTaxesType)
        customerFiscalConfig.invoiceEstimatedTaxesPercentage = Utils.toBigDecimal(params.invoiceEstimatedTaxesPercentage) ?: 0
        customerFiscalConfig.invoiceNecessaryExpression = params.invoiceNecessaryExpression
        customerFiscalConfig.invoiceRetainedIrDescription = params.invoiceRetainedIrDescription
        customerFiscalConfig.invoiceRetainedCsrfDescription = params.invoiceRetainedCsrfDescription
        customerFiscalConfig.invoiceRetainedInssDescription = params.invoiceRetainedInssDescription
        customerFiscalConfig.includeInterestValue = Boolean.valueOf(params.includeInterestValue)
        customerFiscalConfig.save(flush: true)

        return customerFiscalConfig
    }

    public void delete(Long id) {
        CustomerFiscalConfig customerFiscalConfig = CustomerFiscalConfig.get(id)
        customerFiscalConfig.deleted = true
        customerFiscalConfig.save(failOnError: true, flush: true)
    }

    public onUseNationalPortal(Customer customer) {
        CustomerFiscalConfig customerFiscalConfig = CustomerFiscalConfig.query([customerId: customer.id]).get()
        if (!customerFiscalConfig) return
        if (customerFiscalConfig.invoiceEstimatedTaxesType != InvoiceEstimatedTaxesType.IBPT) return

        customerFiscalConfig.invoiceEstimatedTaxesType = InvoiceEstimatedTaxesType.NONE
        customerFiscalConfig.save(failOnError: true)
    }

    private void applyDefaultDescriptionsIfNecessary(Map params) {
        if (params.includeDefaultRetainedIncomeTaxDescription) {
            params.invoiceRetainedIrDescription = Utils.getMessageProperty("customerFiscalConfig.incomeTax.retained.default.description")
        }

        if (params.includeDefaultRetainedCsrfDescription) {
            params.invoiceRetainedCsrfDescription = Utils.getMessageProperty("customerFiscalConfig.csrf.retained.default.description")
        }

        if (params.includeDefaultRetainedInssDescription) {
            params.invoiceRetainedInssDescription = Utils.getMessageProperty("customerFiscalConfig.inss.retained.default.description")
        }
    }
}
