package com.asaas.payment

import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.integration.bb.api.BankSlipRegistrationManager
import com.asaas.integration.bb.parsers.BankSlipParser

import grails.transaction.Transactional

@Transactional
class BancoDoBrasilBankSlipRegisterService {

    def bankSlipOnlineRegistrationResponseService
	def boletoBatchFileItemService
    def messageService

	public BankSlipOnlineRegistrationResponse processRegistration(BoletoBatchFileItem pendingItem, Boolean asyncRegistration) {
		if (asyncRegistration) {
			pendingItem.payment.lock()
		}

		BankSlipRegistrationManager registrationManager = new BankSlipRegistrationManager()
		registrationManager.doRegistration(pendingItem)
		Map parsedRegistrationResponse = BankSlipParser.parseRegistrationResponse(registrationManager.response)

        if (registrationManager.responseHttpStatus != 200 && !parsedRegistrationResponse.errorMessage) {
            parsedRegistrationResponse.errorMessage = registrationManager.response?.toString()?.take(255)
        }

        if (BankSlipParser.isNullCpfCnpjError(parsedRegistrationResponse.errorMessage, pendingItem.payment.provider.cpfCnpj)) {
            parsedRegistrationResponse.registrationStatus = BoletoRegistrationStatus.FAILED
        }

        parsedRegistrationResponse.responseHttpStatus = registrationManager.responseHttpStatus
        BankSlipOnlineRegistrationResponse onlineRegistrationResult = bankSlipOnlineRegistrationResponseService.save(pendingItem.id, parsedRegistrationResponse)

		if (parsedRegistrationResponse.registrationStatus && !isInvalidPostalCodeFailure(parsedRegistrationResponse)) {
			boletoBatchFileItemService.setItemAsSent(pendingItem)
			pendingItem.payment.updateRegistrationStatus(parsedRegistrationResponse.registrationStatus)
		}

		return onlineRegistrationResult
	}

    private Boolean isInvalidPostalCodeFailure(Map registrationResponse) {
        return (registrationResponse.errorCode == BankSlipParser.INVALID_POSTAL_CODE_ERROR_CODE && registrationResponse.returnCode in BankSlipParser.INVALID_POSTAL_CODE_RETURN_CODE_LIST)
    }

}
