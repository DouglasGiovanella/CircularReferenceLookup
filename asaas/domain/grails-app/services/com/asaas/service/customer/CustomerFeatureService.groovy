package com.asaas.service.customer

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.bankaccountinfo.BankAccountInfoUpdateRequest
import com.asaas.domain.city.City
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.utils.AbTestUtils
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class CustomerFeatureService {

    def asaasSegmentioService
    def customerFeatureCacheService
	def customerInteractionService
    def customerParameterService
    def invoiceCityConfigService

	public void save(Customer customer) {
        CustomerFeature customerFeature = CustomerFeature.findOrCreateByCustomer(customer)
        customerFeature.asaasCardElo = true
        customerFeatureCacheService.evictAsaasCardEloByCustomerId(customer.id)

        if (customer.accountOwner && !customer.accountOwner.whatsAppNotificationEnabled()) {
            customerFeature.whatsappNotification = false
            customerFeatureCacheService.evictWhatsappNotificationByCustomerId(customer.id)
        }

		customerFeature.save(flush: true, failOnError: true)
	}

	public CustomerFeature toggleMultipleBankAccounts(Long customerId, Boolean enabled) {
		Customer customer = Customer.get(customerId)
        CustomerFeature customerFeature = CustomerFeature.query([customerId: customerId]).get()

		if (!customerFeature.multipleBankAccounts && enabled && BankAccountInfoUpdateRequest.hasLatestAndIsNotApproved(customer)) {
			CustomerFeature validatedCustomerFeature = new CustomerFeature()
			DomainUtils.addError(validatedCustomerFeature, "Não é possível habilitar múltiplas contas, pois o fornecedor possui dados bancários não aprovados.")
			return validatedCustomerFeature
		}

		customerFeature.multipleBankAccounts = enabled
		customerFeature.save(flush: false, failOnError: false)

		if (customerFeature.hasErrors()) return customerFeature

        customerFeatureCacheService.evictMultipleBankAccountsByCustomerId(customerId)
		customerInteractionService.saveToggleMultiplesBankAccounts(customer, enabled)

		return customerFeature
	}

	public CustomerFeature toggleBillPayment(Long customerId, Boolean enabled) {
		Customer customer = Customer.get(customerId)
		CustomerFeature customerFeature = CustomerFeature.query([customerId: customerId]).get()
		customerFeature.billPayment = enabled
		customerFeature.save(flush: false, failOnError: false)

		if (customerFeature.hasErrors()) return customerFeature

        customerFeatureCacheService.evictBillPaymentByCustomerId(customerId)
		customerInteractionService.saveToggleBillPayment(customer, enabled)

		return customerFeature
	}

	public CustomerFeature toggleHandleBillingInfo(Long customerId, Boolean enabled, Boolean automaticRoutine, Boolean fromAccountOwner) {
		Customer customer = Customer.get(customerId)

        if (enabled && !automaticRoutine && !customer.hadGeneralApproval()) throw new RuntimeException("Feature de tokenização não pode ser habilitada para conta não aprovada. [${customerId}]")

        CustomerFeature customerFeature = CustomerFeature.query([customerId: customerId]).get()
		customerFeature.canHandleBillingInfo = enabled
		customerFeature.save(failOnError: false)

		if (customerFeature.hasErrors()) return customerFeature

        customerFeatureCacheService.evictCanHandleBillingInfo(customerId)
        customerInteractionService.saveToggleHandleBillingInfo(customer, enabled, fromAccountOwner)
        customerParameterService.save(customer, CustomerParameterName.ALLOW_CREDIT_CARD_ACQUIRER_REFUSE_REASON, enabled)

		return customerFeature
	}

	public void enableInvoiceFeatureIfNecessary(Customer customer, City city) {
		if (!customer.isLegalPerson()) return

        CustomerFeature customerFeature = CustomerFeature.query([customerId: customer.id]).get()
		if (customerFeature.invoice) return

		if (!invoiceCityConfigService.isCityIntegrationEnabled(city) && !customer.useNationalPortal()) return

		customerFeature.invoice = true
		customerFeature.save()

        customerFeatureCacheService.evictInvoiceByCustomerId(customer.id)
		asaasSegmentioService.identify(customer.id, ["nfseEnabled": true])
	}

    public void enablePixWithAsaasKeyFeatureIfNecessary(Customer customer) {
        Boolean hasParameterToDisablePix = (CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_PIX_WITH_ASAAS_KEY_TOGGLE))
        if (hasParameterToDisablePix) return

        setPixWithAsaasKey(customer.id, true)
    }

    public void togglePixWithAsaasKeyToChildAccount(Customer accountOwner, Customer childAccount) {
        Boolean enabled = CustomerFeature.isPixWithAsaasKeyEnabled(accountOwner.id)
        if (enabled) enabled = (!CustomerParameter.getValue(childAccount, CustomerParameterName.DISABLE_PIX_WITH_ASAAS_KEY_TOGGLE))

        CustomerFeature customerFeature = CustomerFeature.findByCustomer(childAccount)
        customerFeature.pixWithAsaasKey = enabled
        customerFeature.save(failOnError: true)

        customerFeatureCacheService.evictPixWithAsaasKeyByCustomerId(accountOwner.id)
	}

    public CustomerFeature setWhatsAppNotification(Long customerId, Boolean enabled) {
        CustomerFeature customerFeature = CustomerFeature.query([customerId: customerId]).get()
        customerFeature.whatsappNotification = enabled
        customerFeature.save(failOnError: true)

        if (customerFeature.hasErrors()) return customerFeature

        Customer customer = Customer.get(customerId)

        customerFeatureCacheService.evictWhatsappNotificationByCustomerId(customerId)
        customerInteractionService.saveToggleWhatsAppNotification(customer, enabled)

        return customerFeature
    }

    public CustomerFeature toggleAsaasCardElo(Long customerId, Boolean enabled) {
        CustomerFeature customerFeature = CustomerFeature.query([customerId: customerId]).get()
        customerFeature.asaasCardElo = enabled
        customerFeature.save(failOnError: true)

        if (customerFeature.hasErrors()) return customerFeature

        Customer customer = Customer.get(customerId)

        customerFeatureCacheService.evictAsaasCardEloByCustomerId(customerId)
        customerInteractionService.saveToggleAsaasCardElo(customer, enabled)

        return customerFeature
    }

    public void setPixWithAsaasKey(Long customerId, Boolean enabled) {
        CustomerFeature customerFeature = CustomerFeature.query([customerId: customerId]).get()

        if (customerFeature.pixWithAsaasKey == enabled) return

        customerFeature.pixWithAsaasKey = enabled
        customerFeature.save(failOnError: true)

        customerFeatureCacheService.evictPixWithAsaasKeyByCustomerId(customerId)
    }
}
