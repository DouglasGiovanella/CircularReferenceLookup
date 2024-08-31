package com.asaas.payment

import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.integration.santander.api.SantanderBankSlipRegistrationManager
import com.asaas.integration.santander.parsers.SantanderBankSlipParser

import grails.transaction.Transactional

@Transactional
class SantanderBankSlipOnlineRegistrationService {

    def bankSlipOnlineRegistrationResponseService
	def boletoBatchFileItemService

	public BankSlipOnlineRegistrationResponse processRegistration(BoletoBatchFileItem pendingItem, Boolean asyncRegistration) {
		if (asyncRegistration) {
			pendingItem.payment.lock()
		}

		SantanderBankSlipRegistrationManager registrationManager = new SantanderBankSlipRegistrationManager()
		registrationManager.doRegistration(pendingItem)

		BankSlipOnlineRegistrationResponse onlineRegistrationResult = bankSlipOnlineRegistrationResponseService.save(pendingItem.id, registrationManager.registrationResponse)

		if (registrationManager.registrationResponse?.registrationStatus && !isInvalidPostalCodeFailure(registrationManager.registrationResponse)) {
			boletoBatchFileItemService.setItemAsSent(pendingItem)
			pendingItem.payment.updateRegistrationStatus(registrationManager.registrationResponse.registrationStatus)
		}

		return onlineRegistrationResult
	}

    private Boolean isInvalidPostalCodeFailure(Map registrationResponse) {
        return registrationResponse.errorCode in SantanderBankSlipParser.INVALID_POSTAL_CODE_ERROR_CODES
    }

}
