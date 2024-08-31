package com.asaas.service.customer

import com.asaas.customer.CustomerProductVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceItem
import com.asaas.domain.payment.PaymentCustomerProduct
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.invoice.InvoiceCityUtils
import com.asaas.invoice.InvoiceStatus

import grails.transaction.Transactional

@Transactional
class CustomerProductService {

    def invoiceTaxInfoService
    def subscriptionTaxInfoService

	public CustomerProduct findOrSave(Customer customer, CustomerProductVO customerProductVo) {
        customerProductVo.municipalServiceCode = InvoiceCityUtils.formatMunicipalServiceCode(customer, customerProductVo.municipalServiceCode)

		CustomerProduct customerProduct = CustomerProduct.find(customer, customerProductVo.municipalServiceExternalId, customerProductVo.name)

		if (!customerProduct) return saveOrUpdate(null, customer, customerProductVo)

		if (customerProductVo.municipalServiceCode && customerProduct.municipalServiceCode != customerProductVo.municipalServiceCode) {
			customerProduct = saveOrUpdate(customerProduct.id, customer, customerProductVo)
		}

		return customerProduct
	}

	public CustomerProduct saveOrUpdate(Long id, Customer customer, CustomerProductVO customerProductVo) {
    	CustomerProduct customerProduct = id ? CustomerProduct.find(id, customer.id) : new CustomerProduct()
		customerProduct.build(customer, customerProductVo)
        customerProduct.save(failOnError: false)

		if (customerProduct.hasErrors()) return customerProduct
        if (customerProduct.defaultProduct) {
            CustomerProduct.executeUpdate("update CustomerProduct cp set cp.defaultProduct = false, lastUpdated = :lastUpdated where cp.customer = :customer and cp != :customerProduct", [customer: customer, customerProduct: customerProduct, lastUpdated: new Date()])
        }

        updateCustomerProductDependencies(customerProduct)

		return customerProduct
    }

    public void onToggleUseNationalPortal(Customer customer) {
        CustomerProduct.executeUpdate(
            "UPDATE CustomerProduct SET version = version + 1, lastUpdated = :now, municipalServiceCode = null, municipalServiceExternalId = null WHERE customer = :customer",
            [now: new Date(), customer: customer]
        )
    }

    private void updateCustomerProductDependencies(CustomerProduct customerProduct) {
        List<Subscription> subscriptionList = PaymentCustomerProduct.query([column: "subscription", customerProduct: customerProduct, "subscription[isNotNull]": true]).list()

        for (Subscription subscription : subscriptionList) {
            subscriptionTaxInfoService.updateIssTax(subscription, customerProduct.issTax)
        }

        List<Invoice> invoiceList = InvoiceItem.query([column: "invoice", customerProduct: customerProduct, invoiceStatusList: InvoiceStatus.getUpdatableCustomerInvoiceStatus()]).list()

        for (Invoice invoice : invoiceList) {
            invoiceTaxInfoService.updateIssTax(invoice, customerProduct.issTax)
        }
    }

    public void delete(CustomerProduct customerProduct) {
		if (!customerProduct.canDelete()) throw new BusinessException("Não foi possível remover o serviço: ${customerProduct.deleteDisabledReason}")

		customerProduct.deleted = true
		customerProduct.save(failOnError: true)
	}
}
