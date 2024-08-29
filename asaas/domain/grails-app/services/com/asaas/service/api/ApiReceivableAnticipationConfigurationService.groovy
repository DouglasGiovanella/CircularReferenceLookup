package com.asaas.service.api

import com.asaas.api.ApiReceivableAnticipationConfigurationParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import grails.transaction.Transactional

@Transactional
class ApiReceivableAnticipationConfigurationService extends ApiBaseService {

    def apiResponseBuilderService
    def customerAutomaticReceivableAnticipationConfigService

    public update(Map params) {
        Customer customer = getProviderInstance(params)
        Boolean creditCardAutomaticEnabledWithoutCache = CustomerAutomaticReceivableAnticipationConfig.activated([exists: true, customerId: customer.id]).get().asBoolean()

        if (params.containsKey("creditCardAutomaticEnabled") && creditCardAutomaticEnabledWithoutCache != params.creditCardAutomaticEnabled) {
            if (params.creditCardAutomaticEnabled) {
                creditCardAutomaticEnabledWithoutCache = customerAutomaticReceivableAnticipationConfigService.activate(customer)
            } else {
                creditCardAutomaticEnabledWithoutCache = customerAutomaticReceivableAnticipationConfigService.deactivate(customer.id)
            }
        }

        Map response = ApiReceivableAnticipationConfigurationParser.buildConfig(creditCardAutomaticEnabledWithoutCache)
        return apiResponseBuilderService.buildSuccess(response)
    }

    public find(Map params) {
        Customer customer = getProviderInstance(params)

        Boolean creditCardAutomaticEnabledWithoutCache = CustomerAutomaticReceivableAnticipationConfig.activated([exists: true, customerId: customer.id]).get().asBoolean()
        Map response = ApiReceivableAnticipationConfigurationParser.buildConfig(creditCardAutomaticEnabledWithoutCache)
        return apiResponseBuilderService.buildSuccess(response)
    }
}
