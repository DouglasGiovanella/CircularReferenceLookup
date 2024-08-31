package com.asaas.service.financialtransaction

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.domain.integration.cerc.contractualeffect.FinancialTransactionContractualEffectSettlement
import com.asaas.exception.BusinessException
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FinancialTransactionContractualEffectSettlementService {

    def financialTransactionService

    public FinancialTransactionContractualEffectSettlement saveDebit(CercContractualEffectSettlement settlement) {
        FinancialTransaction transaction = financialTransactionService.saveContractualEffectSettlement(settlement)
        return save(settlement, transaction)
    }

    public void reverseDebit(CercContractualEffectSettlement settlement) {
        FinancialTransaction transactionToBeReversed = FinancialTransactionContractualEffectSettlement.query([
            column: "financialTransaction",
            contractualEffectSettlementId: settlement.id,
            financialTransactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT
        ]).get()
        if (!transactionToBeReversed) throw new BusinessException("Não há transação a ser revertida no item de liquidação [${settlement.id}]")

        FinancialTransaction reversalTransaction = financialTransactionService.saveContractualEffectSettlementReversal(transactionToBeReversed)

        save(settlement, reversalTransaction)
    }

    private FinancialTransactionContractualEffectSettlement save(CercContractualEffectSettlement settlement, FinancialTransaction transaction) {
        FinancialTransactionContractualEffectSettlement validatedDomain = validateSave(settlement.id, transaction.transactionType)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar transação de liquidação de contratos no extrato do cliente", validatedDomain.errors)

        FinancialTransactionContractualEffectSettlement transactionSettlement = new FinancialTransactionContractualEffectSettlement()
        transactionSettlement.contractualEffectSettlement = settlement
        transactionSettlement.financialTransaction = transaction
        return transactionSettlement.save(failOnError: true)
    }

    private FinancialTransactionContractualEffectSettlement validateSave(Long settlementId, FinancialTransactionType type) {
        FinancialTransactionContractualEffectSettlement validatedDomain = new FinancialTransactionContractualEffectSettlement()

        Boolean settlementHasTransactionWithSameType = FinancialTransactionContractualEffectSettlement.query([
            exists: true,
            contractualEffectSettlementId: settlementId,
            financialTransactionType: type
        ]).get().asBoolean()
        if (settlementHasTransactionWithSameType) DomainUtils.addError(validatedDomain, "O item de liquidação [${settlementId}] já possui uma transação do tipo [${type}]")

        return validatedDomain
    }
}
