package com.asaas.service.bankslip

import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BankSlipOnlineRegistrationResponseService {

    public BankSlipOnlineRegistrationResponse save(Long boletoBatchFileItemId, Map registrationResponseMap) {
        BankSlipOnlineRegistrationResponse onlineRegistrationResult = new BankSlipOnlineRegistrationResponse()

        onlineRegistrationResult.boletoBatchFileItemId = boletoBatchFileItemId
        onlineRegistrationResult.returnCode = registrationResponseMap?.returnCode
        onlineRegistrationResult.errorCode = registrationResponseMap?.errorCode
        onlineRegistrationResult.errorMessage = Utils.truncateString(registrationResponseMap?.errorMessage, 255)
        onlineRegistrationResult.registrationStatus = registrationResponseMap?.registrationStatus
        onlineRegistrationResult.responseHttpStatus = registrationResponseMap?.responseHttpStatus
        onlineRegistrationResult.barCode = registrationResponseMap?.barCode
        onlineRegistrationResult.externalIdentifier = registrationResponseMap?.externalIdentifier
        onlineRegistrationResult.registrationReferenceId = registrationResponseMap?.registrationReferenceId
        onlineRegistrationResult.linhaDigitavel = registrationResponseMap?.digitableLine
        onlineRegistrationResult.save(failOnError: true)

        return onlineRegistrationResult
    }
}
