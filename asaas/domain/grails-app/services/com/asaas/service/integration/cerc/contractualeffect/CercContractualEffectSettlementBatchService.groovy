package com.asaas.service.integration.cerc.contractualeffect

import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlementBatch
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileItem
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduledConfirmationReturnFile
import com.asaas.exception.BusinessException
import com.asaas.integration.cerc.enums.ReceivableRegistrationNonPaymentReason
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementBatchStatus
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementStatus
import com.asaas.integration.cerc.enums.contractualeffect.ContractualEffectExternalSettlementType
import com.asaas.log.AsaasLogger
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CercContractualEffectSettlementBatchService {

    def financialTransactionContractualEffectSettlementService
    def receivableUnitItemService

    public Boolean processAwaitingTransferSilocBatchs() {
        final Integer maxBatchesPerExecution = 10

        Map search = [
            column: "id",
            status: CercContractualEffectSettlementBatchStatus.AWAITING_TRANSFER,
            type: ContractualEffectExternalSettlementType.SILOC,
            "paymentArrangement[notIn]": PaymentArrangement.listAllowedToSlcAutomaticProcessArrangement(),
            "debitDate[le]": CustomDateUtils.todayMinusBusinessDays(2).getTime(),
            disableSort: true
        ]

        List<Long> batchIdList = CercContractualEffectSettlementBatch.query(search).list(max: maxBatchesPerExecution)
        if (!batchIdList) return false

        for (Long id : batchIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercContractualEffectSettlementBatch batch = CercContractualEffectSettlementBatch.get(id)
                setAsTransferred(batch)
            }, [logErrorMessage: "CercContractualEffectSettlementBatchService.processAwaitingTransferSilocBatchs >> Erro ao atualizar batch SILOC como transferido [${id}]"])
        }

        return true
    }

    public Boolean processAwaitingTransferSlcAutomaticProcessSilocBatchs() {
        final Integer maxBatchesPerExecution = 10

        Map search = [
            column: "id",
            status: CercContractualEffectSettlementBatchStatus.AWAITING_TRANSFER,
            type: ContractualEffectExternalSettlementType.SILOC,
            "paymentArrangement[in]": PaymentArrangement.listAllowedToSlcAutomaticProcessArrangement(),
            "debitDate[le]": CustomDateUtils.todayMinusBusinessDays(2).getTime(),
            disableSort: true
        ]

        List<Long> batchIdList = CercContractualEffectSettlementBatch.query(search).list(max: maxBatchesPerExecution)
        if (!batchIdList) return false

        for (Long id : batchIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercContractualEffectSettlementBatch batch = CercContractualEffectSettlementBatch.get(id)
                List<String> fileItemConfirmationOcurrenceCodeList = getConfirmationOccurrenceCodeList(batch)

                processBatchBasedOnOccurrenceCodeList(batch, fileItemConfirmationOcurrenceCodeList)
            }, [logErrorMessage: "CercContractualEffectSettlementBatchService.processAwaitingTransferSlcAutomaticProcessSilocBatchs >> Erro ao processar batch SILOC [batchId: ${id}]"])
        }

        return true
    }

    public void processBatchBasedOnOccurrenceCodeList(CercContractualEffectSettlementBatch batch, List<String> fileItemConfirmationOcurrenceCodeList) {
        Boolean hasErrorCode = fileItemConfirmationOcurrenceCodeList.any { it != null && ReceivableUnitSettlementScheduledConfirmationReturnFile.INVALID_BANK_ACCOUNT_CODES.contains(it) }
        if (hasErrorCode) {
            deny(batch, ReceivableRegistrationNonPaymentReason.INVALID_BANK_ACCOUNT_DATA)
            return
        }

        Boolean hasOnlySuccessCode = !fileItemConfirmationOcurrenceCodeList.any { it == null || !ReceivableUnitSettlementScheduledConfirmationReturnFile.SUCCESS_OCCURENCE_CODES.contains(it) }
        if (hasOnlySuccessCode)  {
            setAsTransferred(batch)
            return
        }

        AsaasLogger.error("CercContractualEffectSettlementBatchService.processBatchBasedOnOccurrenceCodeList >> Problema ao processar item do lote SILOC. Um dos confirmationOcurrenceCode é desconhecido. [CercContractualEffectSettlementBatchId: ${batch.id}]")
        setAsAwaitingManualIntervention(batch)
    }

    public CercContractualEffectSettlementBatch saveIfNecessary(Long bankAccountId, PaymentArrangement paymentArrangement, Date debitDate, ReceivableUnitItemStatus itemStatusBeforeSettlement) {
        ContractualEffectExternalSettlementType type = chooseExternalSettlementType(itemStatusBeforeSettlement)

        CercContractualEffectSettlementBatch batch = CercContractualEffectSettlementBatch.validByCompositeKey(bankAccountId, paymentArrangement, debitDate, type).get()
        if (batch) return batch

        return save(bankAccountId, paymentArrangement, debitDate, type)
    }

    public void deny(CercContractualEffectSettlementBatch batch, ReceivableRegistrationNonPaymentReason denialReason) {
        CercContractualEffectSettlementBatch validatedDomain = validateDeny(batch, denialReason)
        if (validatedDomain.hasErrors()) throw new ValidationException("Dados inválidos para negar a transferência do lote", validatedDomain.errors)

        batch.status = CercContractualEffectSettlementBatchStatus.DENIED
        batch.denialReason = denialReason
        batch.analyst = UserUtils.getCurrentUser()
        batch.analysisDate = new Date()
        batch.save(failOnError: true)

        for (CercContractualEffectSettlement settlement in batch.listSettlement()) {
            financialTransactionContractualEffectSettlementService.reverseDebit(settlement)
            receivableUnitItemService.setAsSettlementDenied(settlement.receivableUnitItem, denialReason)

            settlement.status = CercContractualEffectSettlementStatus.REFUNDED
            settlement.save(failOnError: true)
        }
    }

    public void setAsTransferred(CercContractualEffectSettlementBatch batch) {
        if (batch.listSettlement().any { it.status.isRefunded() }) {
            throw new BusinessException("Os itens dessa remessa não estão marcados como debitados.")
        }

        batch.status = CercContractualEffectSettlementBatchStatus.TRANSFERRED
        batch.analysisDate = new Date()
        batch.analyst = UserUtils.getCurrentUser()
        batch.save(failOnError: true)
    }

    public void setBatchAsAwaitingTransferIfPossible(CercContractualEffectSettlementBatch cercContractualEffectSettlementBatch) {
        if (!cercContractualEffectSettlementBatch.status.isTransferred()) return

        cercContractualEffectSettlementBatch.status = CercContractualEffectSettlementBatchStatus.AWAITING_TRANSFER
        cercContractualEffectSettlementBatch.save(failOnError: true)
    }

    public void setAsAwaitingManualIntervention(CercContractualEffectSettlementBatch cercContractualEffectSettlementBatch) {
        cercContractualEffectSettlementBatch.status = CercContractualEffectSettlementBatchStatus.AWAITING_MANUAL_INTERVENTION
        cercContractualEffectSettlementBatch.save(failOnError: true)
    }

    private List<String> getConfirmationOccurrenceCodeList(CercContractualEffectSettlementBatch batch) {
        List<String> confirmationOcurrenceCodeList = []

        List<String> protocolList = CercContractualEffectSettlement.query([batchId: batch.id, "distinct": "contractualEffect.protocol", disableSort: true]).list()
        for (String contractualEffectProtocol : protocolList) {
            List<String> protocolConfirmationOcurrenceCodeList = ReceivableUnitSettlementScheduleReturnFileItem.query(["column": "confirmationOcurrenceCode", contractualEffectProtocol: contractualEffectProtocol, disableSort: true]).list()

            confirmationOcurrenceCodeList.addAll(protocolConfirmationOcurrenceCodeList)
        }

        return confirmationOcurrenceCodeList
    }

    private CercContractualEffectSettlementBatch save(Long bankAccountId, PaymentArrangement paymentArrangement, Date debitDate, ContractualEffectExternalSettlementType type) {
        CercContractualEffectSettlementBatch validatedDomain = validateSave(bankAccountId, paymentArrangement, debitDate, type)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro de validação ao salvar batch para a conta [${bankAccountId}]", validatedDomain.errors)

        CercContractualEffectSettlementBatch batch = new CercContractualEffectSettlementBatch()
        batch.bankAccount = CercBankAccount.read(bankAccountId)
        batch.debitDate = debitDate
        batch.type = type
        batch.paymentArrangement = paymentArrangement
        return batch.save(failOnError: true, flush: true)
    }

    private CercContractualEffectSettlementBatch validateSave(Long bankAccountId, PaymentArrangement paymentArrangement, Date debitDate, ContractualEffectExternalSettlementType type) {
        CercContractualEffectSettlementBatch validatedDomain = new CercContractualEffectSettlementBatch()

        Boolean batchExistsAlready = CercContractualEffectSettlementBatch.validByCompositeKey(bankAccountId, paymentArrangement, debitDate, type).get()
        if (batchExistsAlready) DomainUtils.addError(validatedDomain, "Já existe um batch aguardando transferência para a data [${debitDate}] e conta [${bankAccountId}]")

        return validatedDomain
    }

    private CercContractualEffectSettlementBatch validateDeny(CercContractualEffectSettlementBatch batch, ReceivableRegistrationNonPaymentReason denialReason) {
        CercContractualEffectSettlementBatch validatedDomain = new CercContractualEffectSettlementBatch()

        if (!batch.status.isAwaitingTransfer()) DomainUtils.addError(validatedDomain, "Este lote não está aguardando transferência e portanto não pode ser negado")

        if (!denialReason) DomainUtils.addError(validatedDomain, "É obrigatório informar o motivo")

        return validatedDomain
    }

    private ContractualEffectExternalSettlementType chooseExternalSettlementType(ReceivableUnitItemStatus itemStatus) {
        if (itemStatus.isInSettlementSchedulingProcess()) return ContractualEffectExternalSettlementType.SILOC

        return ContractualEffectExternalSettlementType.INTERNAL
    }
}
