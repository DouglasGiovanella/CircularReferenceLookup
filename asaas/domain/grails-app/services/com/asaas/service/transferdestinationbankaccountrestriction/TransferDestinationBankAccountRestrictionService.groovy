package com.asaas.service.transferdestinationbankaccountrestriction

import com.asaas.domain.transfer.Transfer
import com.asaas.domain.transfer.TransferDestinationBankAccountRestriction
import com.asaas.exception.BusinessException
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class TransferDestinationBankAccountRestrictionService {

    def customerAdminService
    def fraudPreventionService

    public void save(String cpfCnpj) {
        validateSave(cpfCnpj)

        TransferDestinationBankAccountRestriction transferDestinationBankAccountRestriction = new TransferDestinationBankAccountRestriction()
        transferDestinationBankAccountRestriction.cpfCnpj = cpfCnpj
        transferDestinationBankAccountRestriction.save(failOnError: true)
    }

    public void onTransferFailedByRestrictedDestination(Transfer transfer) {
        if (!transfer.status.isFailed()) return

        customerAdminService.disableCheckout(transfer.customer, Utils.getMessageProperty("transfer.fail.invalidTransferDestinationBankAccount"))

        String additionalInfo = Utils.getMessageProperty("customerInteraction.suspectedOfFraudFlaggedByTransferDestinationBankAccountRestriction")
        fraudPreventionService.toggleSuspectedOfFraud(transfer.customer, true, additionalInfo)
    }

    private void validateSave(String cpfCnpj) {
        if (!cpfCnpj) throw new BusinessException("Necessário informar o CPF/CNPJ do destinatário a ser bloqueado")
        if (!CpfCnpjUtils.validate(cpfCnpj)) throw new BusinessException("Necessário informar um CPF/CNPJ válido do destinatário a ser bloqueado")
    }
}
