package com.asaas.payment

import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.integration.bradesco.api.BradescoBankSlipRegistrationManager
import com.asaas.integration.bradesco.parsers.BradescoBankSlipParser

import grails.transaction.Transactional

@Transactional
class BradescoBankSlipOnlineRegistrationService {

    def bankSlipOnlineRegistrationResponseService
    def boletoBatchFileItemService

    def messageService

    public BankSlipOnlineRegistrationResponse processRegistration(BoletoBatchFileItem pendingItem, Boolean asyncRegistration) {
        if (asyncRegistration) {
            pendingItem.payment.lock()
        }

        BradescoBankSlipRegistrationManager registrationManager = new BradescoBankSlipRegistrationManager()
        registrationManager.doRegistration(pendingItem)

        BankSlipOnlineRegistrationResponse onlineRegistrationResult = bankSlipOnlineRegistrationResponseService.save(pendingItem.id, registrationManager.registrationResponse)

        if (registrationManager.registrationResponse?.registrationStatus && !isInvalidPostalCodeFailure(registrationManager.registrationResponse)) {
            boletoBatchFileItemService.setItemAsSent(pendingItem)
            pendingItem.payment.updateRegistrationStatus(registrationManager.registrationResponse.registrationStatus)
        }

        return onlineRegistrationResult
    }

    private Boolean isInvalidPostalCodeFailure(Map registrationResponse) {
        return registrationResponse.errorCode == BradescoBankSlipParser.INVALID_POSTAL_CODE_ERROR_CODE
    }
}
