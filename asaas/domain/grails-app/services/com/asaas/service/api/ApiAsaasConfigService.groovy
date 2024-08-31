package com.asaas.service.api

import com.asaas.domain.bank.Bank
import grails.transaction.Transactional

@Transactional
class ApiAsaasConfigService extends ApiBaseService {

    def grailsApplication

    public Map find(Map params) {
        Map responseMap = [:]

        responseMap.contact = buildContactResponseItem()
        responseMap.bankInfo = buildBankInfoResponseItem()

        return responseMap
    }

    private Map buildContactResponseItem() {
        Map responseItem = [:]

        responseItem.phone = grailsApplication.config.asaas.phone
        responseItem.obsoletePhone = grailsApplication.config.asaas.obsolete.phone
        responseItem.email = grailsApplication.config.asaas.contato
        responseItem.whatsApp = grailsApplication.config.asaas.mobilephone
        responseItem.ombudsmanPhone = grailsApplication.config.asaas.ombudsman.phone
        responseItem.ombudsmanEmail = grailsApplication.config.asaas.ombudsman.mail

        return responseItem
    }

    private Map buildBankInfoResponseItem() {
        Map responseItem = [:]
        responseItem.name = Bank.ASAAS_BANK_NAME
        responseItem.code = Bank.ASAAS_BANK_CODE

        return responseItem
    }
}
