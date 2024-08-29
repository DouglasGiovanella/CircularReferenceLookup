package com.asaas.service.api

import com.asaas.api.ApiCustomerAlertNotificationParser
import com.asaas.customer.CustomerAlertNotificationType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAlertNotification
import grails.transaction.Transactional

@Transactional
class ApiCustomerAlertNotificationService extends ApiBaseService {

    def apiResponseBuilderService
    def customerAlertNotificationService

    def list(params) {
        Customer customer = getProviderInstance(params)

        Map search = [customer: customer, "alertType[in]": ApiCustomerAlertNotificationParser.listAppSupportedTypes()]

        List<CustomerAlertNotification> alertNotifications = CustomerAlertNotification.query(search).list(max: getLimit(params), offset: getOffset(params), readOnly: true)
        List<Map> responseList = alertNotifications.collect { alertNotification -> ApiCustomerAlertNotificationParser.buildResponseItem(alertNotification) }

        return apiResponseBuilderService.buildList(responseList, getLimit(params), getOffset(params), alertNotifications.totalCount)
    }

    def update(params) {
        Map fields = ApiCustomerAlertNotificationParser.parseRequestParams(params)
        customerAlertNotificationService.update(getProviderInstance(params), fields.id, fields)
        return apiResponseBuilderService.buildSuccess([success: true])
    }

    def processCustomerAlertsOnDisplay(params) {
        customerAlertNotificationService.processCustomerAlertsOnDisplay(getProvider(params))
        return apiResponseBuilderService.buildSuccess([success: true])
    }
}
