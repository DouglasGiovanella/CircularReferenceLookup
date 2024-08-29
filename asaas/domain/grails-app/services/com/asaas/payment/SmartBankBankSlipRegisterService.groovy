package com.asaas.payment

import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.smartBank.manager.SmartBankRegistrationManager
import com.asaas.integration.smartBank.parser.SmartBankBankSlipParser
import com.asaas.integration.smartBank.parser.SmartBankBeneficiaryParser
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class SmartBankBankSlipRegisterService {

    def boletoBatchFileItemService

    def messageService

    private static final Integer PENDING_ITEM_LIMIT = 10

    public void processPendingCancellations() {
        if (!AsaasEnvironment.isProduction()) return

        BoletoBank boletoBankSmartBank = BoletoBank.query([bankCode: SupportedBank.SMARTBANK.code()]).get()

        List<Long> pendingItemIdList = BoletoBatchFileItem.pendingDeleteIdsForOnlineRegistration(["payment.boletoBank": boletoBankSmartBank]).list(max: PENDING_ITEM_LIMIT)

        for (Long pendingItemId in pendingItemIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                processPendingDeleteItem(BoletoBatchFileItem.get(pendingItemId))
            })
        }
    }

    private void processPendingDeleteItem(BoletoBatchFileItem pendingDeleteItem) {
        if (pendingDeleteItem.payment.isConfirmedOrReceived()) {
            boletoBatchFileItemService.setItemAsCancelled(pendingDeleteItem)
            return
        }

        BoletoBatchFileItem sentCreateItem = BoletoBatchFileItem.sentCreateItem([nossoNumero: pendingDeleteItem.nossoNumero, "payment.boletoBank": pendingDeleteItem.payment.boletoBank]).get()

        if (!sentCreateItem) {
            boletoBatchFileItemService.setItemAsCancelled(pendingDeleteItem)
            return
        }

        BankSlipOnlineRegistrationResponse onlineRegistrationResponse = BankSlipOnlineRegistrationResponse.notFailedByBoletoBatchFileItem(sentCreateItem.id).get()

        if (!onlineRegistrationResponse || !onlineRegistrationResponse.externalIdentifier) {
            boletoBatchFileItemService.setItemAsCancelled(pendingDeleteItem)
            return
        }

        SmartBankRegistrationManager registrationManager = new SmartBankRegistrationManager()

        registrationManager.cancelBankSlipRegistration(onlineRegistrationResponse.externalIdentifier)

        Map parsedCancellationResponse = SmartBankBankSlipParser.parseRegistrationResponse(registrationManager.response)
        if (parsedCancellationResponse.registrationStatus?.failed()) {
            AsaasLogger.error("SmartBankBankSlipRegisterService - Failed cancellation : BoletoBatchFileItem [${pendingDeleteItem.id}] - HTTP status ${registrationManager.responseHttpStatus} - response : ${registrationManager.response}")
            return
        }

        boletoBatchFileItemService.setItemAsSent(pendingDeleteItem.id)
    }

    public Map processBeneficiaryRegistration(Customer customer) {
        if (!AsaasEnvironment.isProduction()) return null

        SmartBankRegistrationManager registrationManager = new SmartBankRegistrationManager()

        registrationManager.doBeneficiaryRegistration(customer)

        Map parsedBeneficiaryRegistrationResponse = SmartBankBeneficiaryParser.parseBeneficiaryRegistrationResponse(registrationManager.response)

        if (registrationManager.responseHttpStatus != 201) {
            AsaasLogger.error("SmartBankBankSlipRegisterService - Beneficiary [${customer.id}] - registration result : ${parsedBeneficiaryRegistrationResponse}")
        }

        return [
            status: parsedBeneficiaryRegistrationResponse.status,
            externalIdentifier: parsedBeneficiaryRegistrationResponse.externalIdentifier,
            errorMessage: parsedBeneficiaryRegistrationResponse.errorMessage,
            errorCode: parsedBeneficiaryRegistrationResponse.errorCode
        ]
    }

}
