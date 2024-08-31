package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiCreditCardParser
import com.asaas.api.ApiCustomerAccountGroupParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiSubscriptionParser
import com.asaas.billinginfo.BillingType
import com.asaas.customeraccount.CustomerAccountUpdateResponse
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerAccountGroup
import com.asaas.domain.payment.Payment
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.exception.SubscriptionNotFoundException
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.postalservice.PostalServiceSendingError
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.segment.PaymentOriginTracker

import grails.transaction.Transactional

@Transactional
class ApiSubscriptionService extends ApiBaseService {

    def apiResponseBuilderService
    def customerAccountService
    def subscriptionService
    def apiCustomerInvoiceService

	def find(params) {
        try {
        	Boolean expand = params.expand ?: ApiBaseParser.getApiVersion() <= 2

            return ApiSubscriptionParser.buildResponseItem(Subscription.find(params.id, getProvider(params)), expand, getExpandCustomer(params))
        } catch(SubscriptionNotFoundException e) {
            return apiResponseBuilderService.buildNotFoundItem()
        }
	}

	def list(Map params) {
        Customer customer = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        Map filters = ApiSubscriptionParser.parseListFilters(customer, params)

        List<Subscription> subscriptions = subscriptionService.list(filters.customerAccountId, customer.id, limit, offset, filters)

        List<Map> responseItem = []
        List<Map> extraData = []

        if (ApiMobileUtils.isMobileAppRequest()) {
            responseItem = subscriptions.collect { ApiSubscriptionParser.buildLeanResponseItem(it) }

            List<CustomerAccountGroup> customerAccountGroups = CustomerAccountGroup.query([customerId: customer.id]).list(max: 20, offset: 0, sort: "name", order: "asc", readOnly: true)
            if (customerAccountGroups) {
                List<Map> customerAccountGroupMap = customerAccountGroups.collect { ApiCustomerAccountGroupParser.buildResponseItem(it) }
                extraData << [customerAccountGroups: customerAccountGroupMap]
            }
        } else {
            responseItem = subscriptions.collect { ApiSubscriptionParser.buildResponseItem(it, Boolean.valueOf(params.expand), getExpandCustomer(params)) }
        }

        return apiResponseBuilderService.buildList(responseItem, limit, offset, subscriptions.totalCount, extraData)
	}

	def save(params) {
        Customer customer = getProviderInstance(params)

        def fields = ApiSubscriptionParser.parseRequestParams(customer, params)
		fields.creditCardTransactionOriginInfo = ApiCreditCardParser.parseRequestCreditCardTransactionOriginInfoParams(params)

		if (!fields.customerAccount) return apiResponseBuilderService.buildError("invalid", "customer", "", ["Cliente"])

		Map responseMap = [:]

		if (ApiMobileUtils.isMobileAppRequest()) {
			responseMap.isFirstPayment = !customer.hasCreatedPayments()
            responseMap.shouldAskCustomerAcquisitionChannel = !customer.hasCreatedPayments()
		}

        CustomerAccount customerAccount

        if (!fields.customerAccount.id) {
            customerAccount = customerAccountService.save(customer, null, true, fields.customerAccount)

            customerAccountService.createDefaultNotifications(customerAccount)
        } else {
            CustomerAccountUpdateResponse customerAccountUpdateResponse = customerAccountService.update(customer.id, fields.customerAccount)
            customerAccount = customerAccountUpdateResponse.customerAccount
        }

		Subscription subscription = subscriptionService.save(customerAccount, fields, true)

		Boolean expand = params.expand ?: ApiBaseParser.getApiVersion() <= 2
		responseMap << ApiSubscriptionParser.buildResponseItem(subscription, expand, getExpandCustomer(params))

		Subscription.withNewTransaction { status ->
			Payment payment = Payment.query([subscriptionId: subscription.id]).get()
			trackPaymentCreated([providerId: customer.id, payment: payment])

            PaymentOriginTracker.trackApiCreation(null, null, subscription, params.mobileAction?.toString())
		}

		return apiResponseBuilderService.buildSuccess(responseMap)
	}

	def update(params) {
		if (!params.id) {
			return apiResponseBuilderService.buildError("required", "subscription", "id", ["Id"])
		}

		def fields = ApiSubscriptionParser.parseRequestParams(getProviderInstance(params), params)
		fields.providerId = getProvider(params)

		try {
			Subscription subscription = subscriptionService.update(fields)
			if(subscription.hasErrors()) {
				return apiResponseBuilderService.buildErrorList(subscription)
			}

			Boolean expand = ApiBaseParser.getApiVersion() <= 2

			return apiResponseBuilderService.buildSuccess(ApiSubscriptionParser.buildResponseItem(subscription, expand, getExpandCustomer(params)))
		} catch (SubscriptionNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}
	}

	def delete(params) {
		try {
			if (!params.id) {
				return apiResponseBuilderService.buildError("invalid", "subscription", "", ["Id"])
			}

			if (ApiBaseParser.getApiVersion() >= 3 && !params.containsKey("deletePendingPayments")) {
            	params.deletePendingPayments = true
            }

			subscriptionService.delete(params.id, getProvider(params), Boolean.valueOf(params.deletePendingPayments))
			return apiResponseBuilderService.buildDeleted(params.id)
		} catch (SubscriptionNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}
	}

	def getInvoices(params) {
        Subscription subscription = Subscription.find(params.id, getProvider(params))

        if (!subscription.subscriptionPayments) {
            return apiResponseBuilderService.buildList([], getLimit(params), getOffset(params), 0)
        }

        Map invoiceListParams = params.clone()
        invoiceListParams."paymentId[in]" = subscription.subscriptionPayments.collect { it.payment.id }

        return apiCustomerInvoiceService.list(invoiceListParams)
    }

    def getInvoiceConfig(params) {
        Subscription subscription = Subscription.find(params.id, getProvider(params))

        if (!subscription.getFiscalConfig()) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        return apiResponseBuilderService.buildSuccess(ApiSubscriptionParser.buildInvoiceConfigResponseItem(subscription))
    }

    public Map getPaymentBook(Map params) {
        Long subscriptionId = Subscription.query([column: "id", publicId: params.id, customerId: getProvider(params)]).get()
        if (!subscriptionId) return apiResponseBuilderService.buildNotFoundItem()

        Map parsedFields = ApiSubscriptionParser.parsePaymentBookParams(params)
        if (!parsedFields.month || !parsedFields.year) throw new BusinessException("Os campos [month] e [year] devem ser informados")

        parsedFields.id = subscriptionId

        byte[] paymentBookFile = subscriptionService.createPaymentBook(parsedFields, getProvider(params))
        return apiResponseBuilderService.buildFile(paymentBookFile, "Carnê_${params.id}.pdf")
    }

    def configureInvoice(params) {
        Customer customer = getProviderInstance(params)

        Subscription subscription = Subscription.find(params.id, customer.id)

        Map fields = ApiSubscriptionParser.parseInvoiceConfigRequestParams(params)

        if (subscription.getFiscalConfig()) {
            subscriptionService.updateInvoiceConfiguration(subscription.id, customer, fields.invoiceFiscalVO)
        } else {
            subscriptionService.saveInvoiceForExistingSubscription(subscription.id, customer, fields.invoiceFiscalVO, fields.customerProductVO, fields)
        }

        Subscription.withNewTransaction { status ->
            return apiResponseBuilderService.buildSuccess(ApiSubscriptionParser.buildInvoiceConfigResponseItem(subscription))
        }
    }

    def deleteInvoiceConfig(params) {
        Customer customer = getProviderInstance(params)

        Subscription subscription = Subscription.find(params.id, customer.id)

        if (!subscription.getFiscalConfig()) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        subscriptionService.disableInvoiceForExistingSubscription(subscription.id, customer.id)

        return apiResponseBuilderService.buildDeleted(params.id)
    }
}
