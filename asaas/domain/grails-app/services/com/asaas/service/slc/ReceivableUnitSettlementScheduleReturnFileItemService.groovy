package com.asaas.service.slc

import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFile
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileItem
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduledConfirmationReturnFile
import com.asaas.log.AsaasLogger
import com.asaas.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileItemVO
import com.asaas.receivableUnitSettlement.ReceivableUnitSettlementScheduledConfirmationReturnFileItemVO
import com.asaas.receivableunit.PaymentArrangement
import grails.transaction.Transactional

@Transactional
class ReceivableUnitSettlementScheduleReturnFileItemService {

    public ReceivableUnitSettlementScheduleReturnFileItem saveReceivableUnitSettlementScheduleReturnFile(ReceivableUnitSettlementScheduleReturnFileItemVO receivableUnitSettlementScheduleReturnFileItemVO, Long receivableUnitSettlementScheduleReturnFileId) {
        ReceivableUnitSettlementScheduleReturnFileItem receivableUnitSettlementScheduleReturnFileItem = new ReceivableUnitSettlementScheduleReturnFileItem()
        receivableUnitSettlementScheduleReturnFileItem.receivableUnitSettlementScheduleReturnFile = ReceivableUnitSettlementScheduleReturnFile.load(receivableUnitSettlementScheduleReturnFileId)
        receivableUnitSettlementScheduleReturnFileItem.status = receivableUnitSettlementScheduleReturnFileItemVO.status
        receivableUnitSettlementScheduleReturnFileItem.acquirerCnpj = receivableUnitSettlementScheduleReturnFileItemVO.acquirerCnpj
        receivableUnitSettlementScheduleReturnFileItem.debtorIspb = receivableUnitSettlementScheduleReturnFileItemVO.debtorIspb
        receivableUnitSettlementScheduleReturnFileItem.creditorIspb = receivableUnitSettlementScheduleReturnFileItemVO.creditorIspb
        receivableUnitSettlementScheduleReturnFileItem.settlementExternalIdentifier = receivableUnitSettlementScheduleReturnFileItemVO.settlementExternalIdentifier
        receivableUnitSettlementScheduleReturnFileItem.customerCpfCnpj = receivableUnitSettlementScheduleReturnFileItemVO.customerCpfCnpj
        receivableUnitSettlementScheduleReturnFileItem.paymentArrangement = receivableUnitSettlementScheduleReturnFileItemVO.paymentArrangement
        receivableUnitSettlementScheduleReturnFileItem.settlementDate = receivableUnitSettlementScheduleReturnFileItemVO.settlementDate
        receivableUnitSettlementScheduleReturnFileItem.holderCpfCnpj = receivableUnitSettlementScheduleReturnFileItemVO.holderCpfCnpj
        receivableUnitSettlementScheduleReturnFileItem.receivableUnitExternalIdentifier = receivableUnitSettlementScheduleReturnFileItemVO.receivableUnitExternalIdentifier
        receivableUnitSettlementScheduleReturnFileItem.settlementEffectiveDate = receivableUnitSettlementScheduleReturnFileItemVO.settlementEffectiveDate
        receivableUnitSettlementScheduleReturnFileItem.settlementEffectiveValue = receivableUnitSettlementScheduleReturnFileItemVO.settlementEffectiveValue
        receivableUnitSettlementScheduleReturnFileItem.beneficiaryCpfCnpj = receivableUnitSettlementScheduleReturnFileItemVO.beneficiaryCpfCnpj
        receivableUnitSettlementScheduleReturnFileItem.receiverCpfCnpj = receivableUnitSettlementScheduleReturnFileItemVO.receiverCpfCnpj
        receivableUnitSettlementScheduleReturnFileItem.receiverAgency = receivableUnitSettlementScheduleReturnFileItemVO.receiverAgency
        receivableUnitSettlementScheduleReturnFileItem.receiverAccountNumber = receivableUnitSettlementScheduleReturnFileItemVO.receiverAccountNumber
        receivableUnitSettlementScheduleReturnFileItem.contractualEffectProtocol = receivableUnitSettlementScheduleReturnFileItemVO.contractualEffectProtocol
        receivableUnitSettlementScheduleReturnFileItem.errorMessage = receivableUnitSettlementScheduleReturnFileItemVO.errorMessage
        receivableUnitSettlementScheduleReturnFileItem.save(failOnError: true)

        return receivableUnitSettlementScheduleReturnFileItem
	}

    public void processConfirmation(ReceivableUnitSettlementScheduledConfirmationReturnFileItemVO receivableUnitSettlementScheduledConfirmationReturnFileItemVO, ReceivableUnitSettlementScheduledConfirmationReturnFile confirmationFile) {
        ReceivableUnitSettlementScheduleReturnFileItem receivableUnitSettlementScheduleReturnFileItem = ReceivableUnitSettlementScheduleReturnFileItem.query([settlementExternalIdentifier: receivableUnitSettlementScheduledConfirmationReturnFileItemVO.settlementExternalIdentifier]).get()
        if (!receivableUnitSettlementScheduleReturnFileItem) {
            AsaasLogger.warn("ReceivableUnitSettlementScheduleReturnFileItemService.processConfirmation >>> Não foi encontrado o item de retorno do agendamento da UR [settlementExternalIdentifier: ${receivableUnitSettlementScheduledConfirmationReturnFileItemVO.settlementExternalIdentifier}]")
            return
        }

        validateOccurrenceCode(receivableUnitSettlementScheduledConfirmationReturnFileItemVO.occurrenceCode, receivableUnitSettlementScheduleReturnFileItem)

        receivableUnitSettlementScheduleReturnFileItem.confirmationReturnFile = confirmationFile
        receivableUnitSettlementScheduleReturnFileItem.confirmationOcurrenceCode = receivableUnitSettlementScheduledConfirmationReturnFileItemVO.occurrenceCode
        receivableUnitSettlementScheduleReturnFileItem.save(failOnError: true)
    }

    private void validateOccurrenceCode(String occurrenceCode, ReceivableUnitSettlementScheduleReturnFileItem receivableUnitSettlementScheduleReturnFileItem) {
        PaymentArrangement paymentArrangement = PaymentArrangement.valueOf(receivableUnitSettlementScheduleReturnFileItem.paymentArrangement)
        if (PaymentArrangement.listAllowedToSlcAutomaticProcessArrangement().contains(paymentArrangement)) return

        if (ReceivableUnitSettlementScheduledConfirmationReturnFile.SUCCESS_OCCURENCE_CODES.contains(occurrenceCode)) return
        if (ReceivableUnitSettlementScheduledConfirmationReturnFile.INVALID_BANK_ACCOUNT_CODES.contains(occurrenceCode)) return

        AsaasLogger.error("ReceivableUnitSettlementScheduleReturnFileItemService.validateOccurrenceCode >>> Código de ocorrência não está homologado para o arranjo ${paymentArrangement}. [receivableUnitSettlementScheduleReturnFileItem: ${receivableUnitSettlementScheduleReturnFileItem}], occurrenceCode: ${occurrenceCode}]")
    }
}
