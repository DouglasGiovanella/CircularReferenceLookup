package com.asaas.service.openfinance.automatic.externalautomaticdebitservice

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentInfo
import com.asaas.openfinance.automatic.externalautomaticdebitconsent.adapter.ExternalAutomaticDebitConsentAdapter
import com.asaas.openfinance.externaldebitconsent.adapter.base.children.ReceiverAccountAdapter
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class ExternalAutomaticDebitConsentInfoService {

    def externalDebitConsentService
    def externalAutomaticDebitConsentPeriodicLimitsService

    public ExternalAutomaticDebitConsentInfo save(ExternalAutomaticDebitConsentAdapter externalAutomaticDebitConsentAdapter) {
        ExternalAutomaticDebitConsentInfo validatedConsentInfo = validate(externalAutomaticDebitConsentAdapter)
        if (validatedConsentInfo.hasErrors()) return validatedConsentInfo

        ExternalDebitConsent externalDebitConsent = externalDebitConsentService.save(externalAutomaticDebitConsentAdapter)
        if (externalDebitConsent.hasErrors()) return DomainUtils.copyAllErrorsWithErrorCodeFromObject(externalDebitConsent, validatedConsentInfo) as ExternalAutomaticDebitConsentInfo

        ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo = new ExternalAutomaticDebitConsentInfo()
        externalAutomaticDebitConsentInfo.consent = externalDebitConsent
        externalAutomaticDebitConsentInfo.startDate = externalAutomaticDebitConsentAdapter.startDateTime
        externalAutomaticDebitConsentInfo.expirationDate = externalAutomaticDebitConsentAdapter.expirationDateTime
        externalAutomaticDebitConsentInfo.additionalInformation = externalAutomaticDebitConsentAdapter.additionalInformation
        externalAutomaticDebitConsentInfo.type = externalAutomaticDebitConsentAdapter.externalAutomaticDebitConsentType
        externalAutomaticDebitConsentInfo.transactionLimitValue = externalAutomaticDebitConsentAdapter.periodicLimits.transactionLimit
        externalAutomaticDebitConsentInfo.totalAllowedAmount = externalAutomaticDebitConsentAdapter.periodicLimits.totalAllowedAmount
        externalAutomaticDebitConsentInfo.save(failOnError: true)

        externalAutomaticDebitConsentPeriodicLimitsService.save(externalAutomaticDebitConsentAdapter.periodicLimits, externalAutomaticDebitConsentInfo)

        return externalAutomaticDebitConsentInfo
    }

    private ExternalAutomaticDebitConsentInfo validate(ExternalAutomaticDebitConsentAdapter externalAutomaticDebitConsentAdapter) {
        ExternalAutomaticDebitConsentInfo validateConsentInfo = new ExternalAutomaticDebitConsentInfo()

        if (!externalAutomaticDebitConsentAdapter.isTransferToSameOwnership) {
            DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.isTransferNotToSameAccount", "O destinatário não é da mesma titularidade do pagador.")
            return validateConsentInfo
        }

        if (externalAutomaticDebitConsentAdapter.startDateTime && externalAutomaticDebitConsentAdapter.startDateTime < new Date().clearTime()) {
            DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.startDateIsBeforeToday", "A data de início é menor que a data de hoje.")
            return validateConsentInfo
        }

        if (externalAutomaticDebitConsentAdapter.expirationDateTime) {
            if (externalAutomaticDebitConsentAdapter.expirationDateTime <= new Date().clearTime()) {
                DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.expirationDateIsBeforeOrEqualToday", "A data de expiração é menor ou igual a data de hoje.")
                return validateConsentInfo
            }

            if (externalAutomaticDebitConsentAdapter.startDateTime && externalAutomaticDebitConsentAdapter.expirationDateTime <= externalAutomaticDebitConsentAdapter.startDateTime) {
                DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.expirationDateIsBeforeOrEqualStartDate", "A data de expiração não pode ser menor ou igual a data de início.")
                return validateConsentInfo
            }
        }

        if (externalAutomaticDebitConsentAdapter.customer) {
            if (externalAutomaticDebitConsentAdapter.customer.personType.isFisica()) {
                if (externalAutomaticDebitConsentAdapter.receiverList.size() > 1) {
                    DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.invalidReceiverQuantity", "Não é permitido o registro de vários recebedores para a configuração atual.")
                    return validateConsentInfo
                }

                if (externalAutomaticDebitConsentAdapter.getReceiver().cpfCnpj != externalAutomaticDebitConsentAdapter.customer.cpfCnpj) {
                    DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.invalidReceiverDocument", "Recebedor inválido.")
                    return validateConsentInfo
                }
            } else {
                String customerIspb = externalAutomaticDebitConsentAdapter.customer.cpfCnpj.take(8)

                for (ReceiverAccountAdapter receiverAccountAdapter : externalAutomaticDebitConsentAdapter.receiverList) {
                    if (receiverAccountAdapter.cpfCnpj.take(8) != customerIspb) {
                        DomainUtils.addErrorWithErrorCode(validateConsentInfo, "openFinance.invalidParameter.invalidReceiverDocument", "Recebedor inválido.")
                        return validateConsentInfo
                    }
                }
            }
        }

        return validateConsentInfo
    }
}
