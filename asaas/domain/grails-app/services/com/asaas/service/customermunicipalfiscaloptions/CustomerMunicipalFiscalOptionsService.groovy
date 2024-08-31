package com.asaas.service.customermunicipalfiscaloptions

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.invoice.InvoiceCityConfig
import com.asaas.integration.enotas.objects.EnotasTipoAssinaturaDigital
import com.asaas.integration.enotas.objects.EnotasTipoAutenticacao
import com.asaas.integration.invoice.api.manager.MunicipalRequestManager
import com.asaas.integration.invoice.api.vo.MunicipalFiscalOptionsVO
import com.asaas.utils.Utils

import grails.transaction.Transactional
import groovy.json.JsonSlurper

@Transactional
class CustomerMunicipalFiscalOptionsService {

    public MunicipalFiscalOptionsVO getOptions(Customer customer) {
        MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(customer)
        MunicipalFiscalOptionsVO municipalFiscalOptionsVO = municipalRequestManager.getOptions()

        Boolean useNationalPortal = CustomerFiscalInfo.query([column: "useNationalPortal", customerId: customer.id]).get().asBoolean()
        if (useNationalPortal) overrideWithNationalPortalOptions(municipalFiscalOptionsVO)

        if (!customer.city) return municipalFiscalOptionsVO

        String nationalPortalTaxCalculationRegimeList = InvoiceCityConfig.findNationalPortalTaxCalculationRegimeListFromCity(customer.city)
        if (nationalPortalTaxCalculationRegimeList) {
            municipalFiscalOptionsVO.nationalPortalTaxCalculationRegimeList = new JsonSlurper().parseText(nationalPortalTaxCalculationRegimeList) as List<Map>
            municipalFiscalOptionsVO.nationalPortalTaxCalculationRegimeHelp = Utils.getMessageProperty("municipalFiscalOptions.nationalPortalTaxCalculationRegimeHelp.description")
        }

        return municipalFiscalOptionsVO
    }

    public Boolean isMunicipalServiceCodeEnabled(Customer customer) {
        Boolean useNationalPortal = CustomerFiscalInfo.query([column: "useNationalPortal", customerId: customer.id]).get().asBoolean()
        if (useNationalPortal) return false

        MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(customer)
        return municipalRequestManager.isMunicipalServiceCodeEnabled()
    }

    private void overrideWithNationalPortalOptions(MunicipalFiscalOptionsVO municipalFiscalOptionsVO) {
        municipalFiscalOptionsVO.authenticationType = EnotasTipoAutenticacao.USER_AND_PASSWORD.value
        municipalFiscalOptionsVO.digitalSignature = EnotasTipoAssinaturaDigital.OPTIONAL.value
        municipalFiscalOptionsVO.supportsCancellation = false
        municipalFiscalOptionsVO.isServiceListIntegrationAvailable = false
        municipalFiscalOptionsVO.municipalServiceCodeHelp = Utils.getMessageProperty("municipalFiscalOptions.municipalServiceCodeHelp.description")
    }
}
