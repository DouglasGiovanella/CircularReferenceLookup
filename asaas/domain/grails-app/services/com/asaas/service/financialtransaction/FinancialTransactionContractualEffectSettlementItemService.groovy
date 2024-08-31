package com.asaas.service.financialtransaction

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.contractualeffectsettlement.ContractualEffectSettlementItem
import com.asaas.domain.financialtransactioncontractualeffectsettlementitem.FinancialTransactionContractualEffectSettlementItem
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FinancialTransactionContractualEffectSettlementItemService {

    def financialTransactionService

    public FinancialTransactionContractualEffectSettlementItem saveDebit(ContractualEffectSettlementItem settlementItem, String beneficiaryCpfCnpj) {
        FinancialTransaction transaction = financialTransactionService.saveContractualEffectSettlementItem(settlementItem, beneficiaryCpfCnpj)
        return save(settlementItem, transaction)
    }

    public FinancialTransactionContractualEffectSettlementItem reverseDebit(ContractualEffectSettlementItem settlementItem, String beneficiaryCpfCnpj) {
        FinancialTransactionContractualEffectSettlementItem financialTransactionContractualEffectSettlementItem = FinancialTransactionContractualEffectSettlementItem.query([settlementItemId: settlementItem.id]).get()
        FinancialTransaction reversalFinancialTransaction = financialTransactionService.saveContractualEffectSettlementItemReversal(financialTransactionContractualEffectSettlementItem, beneficiaryCpfCnpj)

        return save(settlementItem, reversalFinancialTransaction)
    }

    private FinancialTransactionContractualEffectSettlementItem save(ContractualEffectSettlementItem settlementItem, FinancialTransaction transaction) {
        FinancialTransactionContractualEffectSettlementItem validatedDomain = validateSave(settlementItem.id, transaction.transactionType)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar transação de liquidação de contratos no extrato do cliente", validatedDomain.errors)

        FinancialTransactionContractualEffectSettlementItem financialTransactionContractualEffectSettlementItem = new FinancialTransactionContractualEffectSettlementItem()
        financialTransactionContractualEffectSettlementItem.settlementItem = settlementItem
        financialTransactionContractualEffectSettlementItem.financialTransaction = transaction
        return financialTransactionContractualEffectSettlementItem.save(failOnError: true)
    }

    private FinancialTransactionContractualEffectSettlementItem validateSave(Long settlementItemId, FinancialTransactionType type) {
        FinancialTransactionContractualEffectSettlementItem validatedDomain = new FinancialTransactionContractualEffectSettlementItem()

        Boolean settlementItemHasTransactionWithSameType = FinancialTransactionContractualEffectSettlementItem.query([
            exists: true,
            settlementItemId: settlementItemId,
            financialTransactionType: type
        ]).get().asBoolean()
        if (settlementItemHasTransactionWithSameType) DomainUtils.addError(validatedDomain, "O item de liquidação [${settlementItemId}] já possui uma transação do tipo [${type}]")

        return validatedDomain
    }
}
