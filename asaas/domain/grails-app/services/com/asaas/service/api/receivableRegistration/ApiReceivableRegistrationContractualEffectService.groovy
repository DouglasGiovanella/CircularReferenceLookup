package com.asaas.service.api.receivableRegistration

import com.asaas.api.ApiMobileUtils
import com.asaas.api.receivableRegistration.ApiReceivableRegistrationContractualEffectParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercContract
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.integration.cerc.enums.contestation.CercContestationProgressStatus
import com.asaas.integration.cerc.enums.contestation.CercContestationType
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiReceivableRegistrationContractualEffectService extends ApiBaseService {

    def apiResponseBuilderService
    def cercContestationService
    def cercContractualEffectService

    public Map list(Map params) {
        String contractIdentifier = params.externalIdentifier
        Customer customer = getProviderInstance()

        List<CercContractualEffect> contractualEffectList = cercContractualEffectService.listEffectsAffectedByReceivableUnit(customer, contractIdentifier, getLimit(params), getOffset(params))

        List<Map> builtEffects = contractualEffectList.collect { ApiReceivableRegistrationContractualEffectParser.buildResponseItem(it) }

        List<Map> extraData = []
        if (ApiMobileUtils.isMobileAppRequest()) {
            CercContestation contractContestation = CercContestation.findContestationInProgress(customer, contractIdentifier, CercContestationType.CONTRACT)
            if (contractContestation) extraData << [contractContestation: ApiReceivableRegistrationContractualEffectParser.buildContestationResponseItem(contractContestation)]

            CercContract cercContract = CercContract.query([customer: customer, externalIdentifier: contractIdentifier]).get()

            Map summaryData = [:]
            summaryData.paymentTotalValue = cercContract.calculatePaymentTotalValue()
            summaryData.settlementTotalValue = cercContract.settledValue
            summaryData.compromisedTotalValue = cercContract.value
            summaryData.beneficiaryCpfCnpj = cercContract.beneficiaryCpfCnpj

            extraData << [summaryData: summaryData]
        }

        return apiResponseBuilderService.buildList(builtEffects, getLimit(params), getOffset(params), contractualEffectList.totalCount, extraData)
    }

    public Map requestContest(Map params) {
        Customer customer = getProviderInstance()
        String contractIdentifier = params.externalIdentifier

        Map parsedFields = ApiReceivableRegistrationContractualEffectParser.parseContestRequestParams(params)

        CercContractualEffect cercContractualEffect = CercContractualEffect.byExternalIdentifier(customer, contractIdentifier, [:]).get()

        CercContestation cercContestation = cercContestationService.saveFromCustomerRequest(
            cercContractualEffect.customerCpfCnpj,
            cercContractualEffect.beneficiaryCpfCnpj,
            cercContractualEffect.externalIdentifier,
            parsedFields.description,
            CercContestationType.CONTRACT,
            parsedFields.reason
        )

        if (cercContestation.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(cercContestation)
        }

        return apiResponseBuilderService.buildSuccess(ApiReceivableRegistrationContractualEffectParser.buildContestationResponseItem(cercContestation))
    }
}
