package com.asaas.service.receivableanticipation

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.optin.CercAsaasOptIn
import com.asaas.domain.receivableanticipation.ReceivableAnticipationAgreement
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.cerc.enums.CercAsaasOptInType
import com.asaas.receivableanticipation.ReceivableAnticipationAgreementDataBuilder
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner

import grails.transaction.Transactional

import javax.servlet.http.HttpServletRequest

@Transactional
class ReceivableAnticipationAgreementService {

    def cercAsaasOptInService
    def originRequesterInfoService
    def receivableAnticipationPartnerConfigService
    def receivableAnticipationService
    def receivableAnticipationValidationCacheService

    public void sign(Customer customer, Map agreementData, Boolean authorizeAsaasExternalOptIn) {
        saveAgreement(customer, agreementData.remoteIp, agreementData.user, agreementData.name, agreementData.userAgent, agreementData.terms, agreementData.headers, agreementData.contractVersion, agreementData.agreed)
        receivableAnticipationValidationCacheService.evictAnyAgreementVersionHasBeenSigned(customer.id)

        receivableAnticipationPartnerConfigService.processPartnerContractAgreement(customer)

        Boolean customerHasOptInAlready = CercAsaasOptIn.query([exists: true, customerCpfCnpj: customer.cpfCnpj, type: CercAsaasOptInType.INTERNAL, partner: ReceivableAnticipationPartner.VORTX]).get().asBoolean()
        if (!customerHasOptInAlready) cercAsaasOptInService.save(customer.cpfCnpj, CercAsaasOptInType.INTERNAL, ReceivableAnticipationPartner.VORTX, null)

        if (authorizeAsaasExternalOptIn) cercAsaasOptInService.saveExternalOptIn(customer.cpfCnpj)
    }

    public void saveIfNecessary(Customer customer, HttpServletRequest request) {
        if (receivableAnticipationValidationCacheService.anyAgreementVersionHasBeenSigned(customer.id)) return

        sign(customer, new ReceivableAnticipationAgreementDataBuilder(request).execute(), true)
    }

    private void saveAgreement(Customer customer, String remoteIp, User user, String name, String userAgent, String terms, String headers, Integer contractVersion, Boolean agreed) {
        if (!name && !agreed) throw new BusinessException("Informe o seu primeiro nome.")
        if (!terms) throw new BusinessException("Informe o texto do contrato.")

        ReceivableAnticipationAgreement agreement = new ReceivableAnticipationAgreement()
        agreement.customer = customer
        agreement.remoteIp = remoteIp
        agreement.user = user
        agreement.name = name
        agreement.userAgent = userAgent
        agreement.terms = terms
        agreement.requestHeaders = headers
        agreement.contractVersion = contractVersion
        agreement.origin = originRequesterInfoService.getEventOrigin()
        agreement.save(failOnError: true)
    }
}
