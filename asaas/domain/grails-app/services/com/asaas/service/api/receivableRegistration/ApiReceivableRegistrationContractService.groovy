package com.asaas.service.api.receivableRegistration

import com.asaas.api.receivableRegistration.ApiReceivableRegistrationContestationParser
import com.asaas.api.receivableRegistration.ApiReceivableRegistrationContractParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercContract
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.integration.cerc.enums.contestation.CercContestationType
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiReceivableRegistrationContractService extends ApiBaseService {

    def apiResponseBuilderService
    def cercContestationService
    def cercContractualEffectService

    public Map show(Map params) {
        Customer customer = getProviderInstance()
        CercContract contract = CercContract.find(customer, Utils.toLong(params.id))

        return apiResponseBuilderService.buildSuccess(ApiReceivableRegistrationContractParser.buildResponseItem(customer, contract))
    }

    public Map list(Map params) {
        Customer customer = getProviderInstance()

        Map filterParams = ApiReceivableRegistrationContractParser.parseFilterParams(params)

        List<CercContract> contractList = CercContract.query(filterParams + [customer: customer]).list(max: getLimit(params), offset: getOffset(params))

        List<Map> builtContracts = contractList.collect { ApiReceivableRegistrationContractParser.buildResponseItem(customer, it) }

        return apiResponseBuilderService.buildList(builtContracts, getLimit(params), getOffset(params), contractList.totalCount)
    }

    public Map listEffects(Map params) {
        Customer customer = getProviderInstance()

        CercContract contract = CercContract.find(customer, Utils.toLong(params.id))

        List<CercContractualEffect> contractualEffectList = cercContractualEffectService.listEffectsAffectedByReceivableUnit(customer, contract.externalIdentifier, getLimit(params), getOffset(params))

        List<Map> builtEffects = contractualEffectList.collect { ApiReceivableRegistrationContractParser.buildContractualEffectResponseItem(it) }

        return apiResponseBuilderService.buildList(builtEffects, getLimit(params), getOffset(params), contractualEffectList.totalCount)
    }

    public Map requestContest(Map params) {
        Customer customer = getProviderInstance()

        Map parsedFields = ApiReceivableRegistrationContestationParser.parseRequestParams(params)

        CercContract contract = CercContract.find(customer, Utils.toLong(params.id))

        CercContestation cercContestation = cercContestationService.saveFromCustomerRequest(
            contract.customerCpfCnpj,
            contract.beneficiaryCpfCnpj,
            contract.externalIdentifier,
            parsedFields.description,
            CercContestationType.CONTRACT,
            parsedFields.reason
        )

        if (cercContestation.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(cercContestation)
        }

        return apiResponseBuilderService.buildSuccess(ApiReceivableRegistrationContestationParser.buildResponseItem(cercContestation))
    }
}
