package com.asaas.service.api

import com.asaas.api.ApiCustomerAccountGroupParser
import com.asaas.api.ApiCustomerAccountParser
import com.asaas.api.ApiMobileUtils
import com.asaas.customeraccount.CustomerAccountUpdateResponse
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerAccountGroup
import com.asaas.exception.CustomerAccountNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.mysql.jdbc.exceptions.MySQLTimeoutException
import grails.transaction.Transactional
import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy
import org.springframework.web.context.request.RequestContextHolder

@Transactional
class ApiCustomerAccountService extends ApiBaseService {

    def customerAccountService
	def apiSubscriptionService
	def apiPaymentService
	def apiNotificationService
	def apiResponseBuilderService
    def cityService
    def grailsApplication

	def find(params) {
        try {
        	if (ApiCustomerAccountParser.getApiVersion() <= 2) params.expand = true

        	return buildResponseItem(CustomerAccount.find(params.id, getProvider(params)))
        } catch(CustomerAccountNotFoundException e) {
            return apiResponseBuilderService.buildNotFoundItem()
        }
	}

	def list(params) {
        Customer customer = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        Map filters = ApiCustomerAccountParser.parseListFilters(params, customer.id)

        try {
            NamedCriteriaProxy criteriaProxy =  CustomerAccount.query(filters)
            List<Customer> customerList = criteriaProxy.list(max: limit, offset: offset, timeout: grailsApplication.config.asaas.query.defaultTimeoutInSeconds)
            Integer totalCount = criteriaProxy.count() { setTimeout(grailsApplication.config.asaas.query.defaultTimeoutInSeconds) }

            List customerListResponse = buildListResponse(customerList)

            List<Map> extraData = []

            if (ApiMobileUtils.isMobileAppRequest()) {
                List<CustomerAccountGroup> customerAccountGroups = CustomerAccountGroup.query([customerId: customer.id]).list(max: 20, offset: 0, sort: "name", order: "asc", readOnly: true)
                if (customerAccountGroups) {
                    List<Map> customerAccountGroupMap = customerAccountGroups.collect { ApiCustomerAccountGroupParser.buildResponseItem(it) }
                    extraData << [customerAccountGroups: customerAccountGroupMap]
                }
            }

            return apiResponseBuilderService.buildList(customerListResponse, limit, offset, totalCount, extraData)
        } catch (error) {
            if (error.cause instanceof MySQLTimeoutException) {
                AsaasLogger.error("ApiCustomerAccountService.list >> Timeout na listagem de customer account. Params [${filters}]", error)
            }

            throw error
        }
	}

	public List buildListResponse(List<Customer> customerList) {
		List customerListResponse = []

		for (customer in customerList) {
            if (ApiCustomerAccountParser.getApiVersion() >= 3) {
                customerListResponse << buildResponseItem(customer)
    		} else {
                customerListResponse << [customer: buildResponseItem(customer)]
            }
        }

        return customerListResponse
	}

	def save(params) {
		Map fields = ApiCustomerAccountParser.parseRequestParams(params)

		def customerAccount = customerAccountService.save(getProviderInstance(params), null, false, fields)
        if (customerAccount.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(customerAccount)
		}

		customerAccountService.createDefaultNotifications(customerAccount)

		if (ApiCustomerAccountParser.getApiVersion() <= 2) params.expand = true

		return apiResponseBuilderService.buildSuccess(buildResponseItem(customerAccount))
	}

	def update(params) {
		if(!params.id) {
			return apiResponseBuilderService.buildError("required", "customer", "id", ["Id"])
		}

        def fields = ApiCustomerAccountParser.parseRequestParams(params)

		CustomerAccount customerAccount
		try {
            CustomerAccountUpdateResponse response = customerAccountService.update(getProvider(params), fields)
            customerAccount = response.customerAccount
		} catch (CustomerAccountNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}

		if (customerAccount.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(customerAccount)
		}

		return apiResponseBuilderService.buildSuccess(buildResponseItem(customerAccount))
	}

	def delete(params) {
        try {
            if (!params.id) {
                return apiResponseBuilderService.buildError("invalid", "customer", "", ["Id"])
            }

            if (ApiCustomerAccountParser.getApiVersion() >= 3 && !params.containsKey("deletePendingPayments")) {
            	params.deletePendingPayments = true
            }

            customerAccountService.delete(params.id, getProvider(params), Boolean.valueOf(params.deletePendingPayments))
            return apiResponseBuilderService.buildDeleted(params.id)
        } catch (CustomerAccountNotFoundException e) {
            return apiResponseBuilderService.buildNotFoundItem()
        }
	}

    def restore(params) {
        CustomerAccount restoredCustomerAccount = customerAccountService.restore(getProvider(params), params.id)
        return apiResponseBuilderService.buildSuccess(buildResponseItem(restoredCustomerAccount))
    }

	public Map buildResponseItem(customer) {
    	def model = ApiCustomerAccountParser.buildResponseItem(customer)

    	List<CustomerAccountGroup> customerAccountGroupList = CustomerAccountGroup.query([customerId: customer.provider.id,
																					  	  customerAccountId: customer.id,
																					 	  workspace: false]).list()
    	if (customerAccountGroupList) {
    		model.groups = ApiCustomerAccountGroupParser.buildResponseList(customerAccountGroupList)
    	}

		if (getExpand()) {
			model.subscriptions = apiSubscriptionService.list([provider: customer.provider.id, customer: customer.publicId, limit: 100, offset: 0, expand: true])
			model.payments = apiPaymentService.list([provider: customer.provider.id, customer: customer.id, limit: 100, offset: 0])
			model.notifications = apiNotificationService.list([provider: customer.provider.id, customer: customer.id, limit: 100, offset: 0])
		}

		return model
    }

    private Boolean getExpand() {
		return Utils.toBoolean(RequestContextHolder.requestAttributes.params.expand)
	}
}
