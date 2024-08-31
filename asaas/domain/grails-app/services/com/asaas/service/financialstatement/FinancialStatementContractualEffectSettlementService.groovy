package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.integration.cerc.contractualeffect.FinancialTransactionContractualEffectSettlement
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementBatchStatus
import com.asaas.integration.cerc.enums.contractualeffect.ContractualEffectExternalSettlementType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementContractualEffectSettlementService {

    def financialStatementService

    public void createStatements(Date startDate) {
        createForInternalSettlements(startDate)
        createForSilocSettlements(startDate)
    }

    private void createForInternalSettlements(Date startDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<Long> transactionIdList = listTransactionsIdToCreateInternalStatements(startDate)
            if (!transactionIdList) return

            financialStatementService.saveAccountingEntriesForTransactionIdList(transactionIdList, FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT, Bank.findByCode(SupportedBank.SANTANDER.code()))
        }, [logErrorMessage: "FinancialStatementContractualEffectSettlementService.createForInternalSettlements >> Falha ao gerar os lançamentos do que foi liquidado para outras instituições."])
    }

    private void createForSilocSettlements(Date startDate) {
        Utils.withNewTransactionAndRollbackOnError({
            createForSilocSettled(startDate)
            createForSilocReversal(startDate)
        }, [logErrorMessage: "FinancialStatementContractualEffectSettlementService.createForSilocSettlements >> Falha ao gerar os lançamentos do que foi liquidado ou estornado para outras instituições."])
    }

    private void createForSilocSettled(Date startDate) {
        List<Long> debitTransactionIdList = listTransactionIdToCreateSilocStatements(
            startDate,
            CercContractualEffectSettlementBatchStatus.AWAITING_TRANSFER,
            FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT)
        if (debitTransactionIdList) financialStatementService.saveAccountingEntriesForTransactionIdList(debitTransactionIdList, FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT, Bank.findByCode(SupportedBank.ASAAS.code()))
    }

    private void createForSilocReversal(Date startDate) {
        List<Long> reversalTransactionIdList = listTransactionIdToCreateSilocStatements(
            startDate,
            CercContractualEffectSettlementBatchStatus.DENIED,
            FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_REFUND_CREDIT)

        if (reversalTransactionIdList) financialStatementService.saveAccountingEntriesForTransactionIdList(reversalTransactionIdList, FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_REFUND_CREDIT, Bank.findByCode(SupportedBank.ASAAS.code()))
    }

    private List<Long> listTransactionsIdToCreateInternalStatements(Date startDate) {
        Map search = [:]
        search.column = "financialTransaction.id"
        search.financialTransactionType = FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT
        search.batchStatus = CercContractualEffectSettlementBatchStatus.TRANSFERRED
        search.batchType = ContractualEffectExternalSettlementType.INTERNAL
        search."batchAnalysisDate[ge]" = startDate
        search."externalSettlement[notExists]" = true
        search."financialTransactionStatementWithType[notExists]" = FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT
        search.disableSort = true
        return FinancialTransactionContractualEffectSettlement.query(search).list()
    }

    private List<Long> listTransactionIdToCreateSilocStatements(Date startDate, CercContractualEffectSettlementBatchStatus status, FinancialStatementType financialStatementType) {
        Map search = [:]
        search.column = "financialTransaction.id"
        search.financialTransactionType = FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT
        search.batchStatus = status
        search.batchType = ContractualEffectExternalSettlementType.SILOC
        search."batchDebitDate[ge]" = startDate
        search."financialTransactionStatementWithType[notExists]" = financialStatementType
        search.disableSort = true
        return FinancialTransactionContractualEffectSettlement.query(search).list()
    }
}
