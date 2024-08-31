package com.asaas.service.asaascard

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asaascard.AsaasCardBrand
import com.asaas.domain.asaascard.AsaasCardAgreement
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User

import grails.transaction.Transactional

@Transactional
class AsaasCardAgreementService {

    def bifrostAccountManagerService

    public AsaasCardAgreement saveCreditCardAgreementIfNecessary(Customer customer, AsaasCardBrand brand, Map params, User user) {
        AsaasCardAgreement asaasCardAgreement = AsaasCardAgreement.findByCustomerAndCurrentContractVersion(customer, brand, buildContractVersion(brand))
        if (asaasCardAgreement) return asaasCardAgreement

        asaasCardAgreement = save(customer, brand, user, params)

        return bifrostAccountManagerService.saveAsaasCardAgreement(asaasCardAgreement)
    }

    public String getCurrentContractText(AsaasCardBrand brand) {
        Integer contractVersion = buildContractVersion(brand)
        String brandPackage = brand.toString().toLowerCase()

        return AsaasApplicationHolder.applicationContext.groovyPageRenderer.render(template: "/asaasCard/templates/contractVersion/creditCard/${brandPackage}/${contractVersion}").trim()
    }

    public Integer buildContractVersion(AsaasCardBrand brand) {
        if (brand.isElo()) return AsaasCardAgreement.ELO_CREDIT_CARD_CURRENT_CONTRACT_VERSION
        if (brand.isMasterCard()) return AsaasCardAgreement.MASTERCARD_COMBO_CARD_CURRENT_CONTRACT_VERSION

        throw new RuntimeException("Bandeira não suportada para assinatura de contrato. [brand: ${brand}]")
    }

    public Boolean customerHasUpdatedAsaasCardAgreement(Customer customer, AsaasCardBrand brand) {
        Integer contractVersion = buildContractVersion(brand)
        return AsaasCardAgreement.customerHasUpdatedAsaasCardAgreement(customer, brand, contractVersion)
    }

    private AsaasCardAgreement save(Customer customer, AsaasCardBrand brand, User user, Map params) {
        Map agreementFields = [
            remoteIp: params.remoteIp,
            customer: customer,
            brand: brand,
            user: user,
            userAgent: params.userAgent,
            terms: getCurrentContractText(brand),
            requestHeaders: params.headers,
            contractVersion: buildContractVersion(brand)
        ]

        AsaasCardAgreement asaasCardAgreement = new AsaasCardAgreement(agreementFields)
        asaasCardAgreement.save(failOnError: true)

        return asaasCardAgreement
    }
}
