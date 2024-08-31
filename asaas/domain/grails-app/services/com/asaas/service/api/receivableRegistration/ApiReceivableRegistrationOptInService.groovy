package com.asaas.service.api.receivableRegistration

import com.asaas.api.receivableRegistration.ApiReceivableRegistrationContestationParser
import com.asaas.api.receivableRegistration.ApiReceivableRegistrationOptInParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.domain.integration.cerc.optin.CercOptIn
import com.asaas.integration.cerc.enums.contestation.CercContestationType
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiReceivableRegistrationOptInService extends ApiBaseService {

    def apiResponseBuilderService
    def cercContestationService
    def cercOptInService

    public Map show(Map params) {
        Customer customer = getProviderInstance()
        CercOptIn optIn = CercOptIn.find(customer, Utils.toLong(params.id))

        return apiResponseBuilderService.buildSuccess(ApiReceivableRegistrationOptInParser.buildResponseItem(customer, optIn))
    }

    public Map list(Map params) {
        Customer customer = getProviderInstance()

        Map filterParams = ApiReceivableRegistrationOptInParser.parseFilterParams(params)

        List<CercOptIn> optInList = cercOptInService.list(customer, getLimit(params), getOffset(params), filterParams)

        List<Map> builtOptInList = optInList.collect { ApiReceivableRegistrationOptInParser.buildResponseItem(customer, it) }

        return apiResponseBuilderService.buildList(builtOptInList, getLimit(params), getOffset(params), optInList.totalCount)
    }

    public Map requestContest(Map params) {
        Customer customer = getProviderInstance()

        Map parsedFields = ApiReceivableRegistrationContestationParser.parseRequestParams(params)

        CercOptIn optIn = CercOptIn.find(customer, Utils.toLong(params.id))

        CercContestation cercContestation = cercContestationService.saveFromCustomerRequest(
            optIn.customerCpfCnpj,
            optIn.requesterCpfCnpj,
            optIn.externalIdentifier,
            parsedFields.description,
            CercContestationType.OPT_IN,
            parsedFields.reason
        )

        if (cercContestation.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(cercContestation)
        }

        return apiResponseBuilderService.buildSuccess(ApiReceivableRegistrationContestationParser.buildResponseItem(cercContestation))
    }
}
