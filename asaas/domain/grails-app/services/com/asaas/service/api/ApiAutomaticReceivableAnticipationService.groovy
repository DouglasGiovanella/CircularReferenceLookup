package com.asaas.service.api

import com.asaas.api.ApiAutomaticReceivableAnticipationParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiAutomaticReceivableAnticipationService extends ApiBaseService {

    def apiResponseBuilderService
    def customerAutomaticReceivableAnticipationConfigService
    def receivableAnticipationBatchService

    public index(Map params) {
        Customer customer = getProviderInstance(params)

        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        CustomerAutomaticReceivableAnticipationConfig automaticReceivableAnticipation = CustomerAutomaticReceivableAnticipationConfig.query([customerId: customer.id]).get()
        Map anticipationTotalValues = receivableAnticipationBatchService.buildAnticipationTotalValues(customer)

        return ApiAutomaticReceivableAnticipationParser.buildResponseItem(customer, customerReceivableAnticipationConfig, automaticReceivableAnticipation, anticipationTotalValues)
    }

    public save(Map params) {
        Customer customer = getProviderInstance(params)
        Boolean isEnableAutomation = Utils.toBoolean(params.isEnableAutomation)

        if (isEnableAutomation) {            
            customerAutomaticReceivableAnticipationConfigService.activate(customer)
        } else {
            customerAutomaticReceivableAnticipationConfigService.deactivate(customer.id)
        }

        return apiResponseBuilderService.buildSuccess([success: true])
    }

}
