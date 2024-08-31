package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtansactionexternalsettlement.FinancialTransactionExternalSettlement
import com.asaas.domain.integration.cerc.contractualeffect.FinancialTransactionContractualEffectSettlement
import com.asaas.externalsettlement.ExternalSettlementOrigin
import com.asaas.externalsettlement.ExternalSettlementStatus
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementExternalSettlementService {

    def financialStatementService

    public void createForContractualEffectExternalSettlements() {
        createForContractualEffectBatchCustomerBalanceDebit()
        createForContractualEffectBatchAsaasBalanceCredit()
        createForContractualEffectSetllementBatchAsaasBalanceDebit()
        createForContractualEffectBatchCustomerBalanceCredit()
    }

    private void createForContractualEffectBatchCustomerBalanceDebit() {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.column = "financialTransaction.id"
            search.financialTransactionType = FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT
            search."externalSettlement[exists]" = true
            search."financialStatementType[notExists]" = FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_TRANSITORY_DEBIT
            search.disableSort = true
            List<Long> financialTransactionIdList = FinancialTransactionContractualEffectSettlement.query(search).list()
            if (!financialTransactionIdList) return

            financialStatementService.saveAccountingEntriesForTransactionIdList(financialTransactionIdList, FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_TRANSITORY_DEBIT, null)
        }, [logErrorMessage: "FinancialStatementExternalSettlementService.createForContractualEffectBatchCustomerBalanceDebit - Erro ao realizar lançamentos contábeis de débito em transitória para as liquidações de efeito de contrato"])
    }

    private void createForContractualEffectBatchAsaasBalanceCredit() {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.column = "financialTransaction.id"
            search.financialTransactionType = FinancialTransactionType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CREDIT
            search."financialStatementType[notExists]" = FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_TRANSITORY_CREDIT
            search.disableSort = true
            List<Long> financialTransactionIdList = FinancialTransactionExternalSettlement.query(search).list()
            if (!financialTransactionIdList) return

            financialStatementService.saveAccountingEntriesForTransactionIdList(financialTransactionIdList, FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_TRANSITORY_CREDIT, null)
        }, [logErrorMessage: "FinancialStatementExternalSettlementService.createForContractualEffectBatchAsaasBalanceCredit - Erro ao realizar lançamentos contábeis de crédito em transitória para as liquidações de efeito de contrato"])
    }

    private void createForContractualEffectSetllementBatchAsaasBalanceDebit() {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.column = "financialTransaction.id"
            search.financialTransactionType = FinancialTransactionType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CREDIT
            search."financialStatementType[notExists]" = FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT
            search."externalSettlementStatus[in]" = [ExternalSettlementStatus.PRE_PROCESSED, ExternalSettlementStatus.PROCESSED, ExternalSettlementStatus.REFUNDED]
            search.externalSettlementOrigin = ExternalSettlementOrigin.CONTRACTUAL_EFFECT_SETTLEMENT_BATCH
            search.disableSort = true
            Bank santanderBank = Bank.findByCode(SupportedBank.SANTANDER.code())

            List<Long> santanderFinancialTransactionIdList = FinancialTransactionExternalSettlement.query(search + [bank: santanderBank]).list()
            if (santanderFinancialTransactionIdList) financialStatementService.saveAccountingEntriesForTransactionIdList(santanderFinancialTransactionIdList, FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT, santanderBank)

            Bank bradescoBank = Bank.findByCode(SupportedBank.BRADESCO.code())
            List<Long> bradescoFinancialTransactionIdList = FinancialTransactionExternalSettlement.query(search + [bank: bradescoBank]).list()
            if (bradescoFinancialTransactionIdList) financialStatementService.saveAccountingEntriesForTransactionIdList(bradescoFinancialTransactionIdList, FinancialStatementType.CONTRACTUAL_EFFECT_SETTLEMENT_CUSTOMER_BALANCE_DEBIT, bradescoBank)
        }, [logErrorMessage: "FinancialStatementExternalSettlementService.createForContractualEffectSetllementBatchAsaasBalanceDebit - Erro ao realizar lançamentos contábeis de débito em saldo cliente para as liquidações de efeito de contrato"])
    }

    private void createForContractualEffectBatchCustomerBalanceCredit() {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.column = "financialTransaction.id"
            search.financialTransactionType = FinancialTransactionType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_REVERSAL
            search."financialStatementType[notExists]" = FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_CREDIT
            search.externalSettlementStatus = ExternalSettlementStatus.REFUNDED
            search.externalSettlementOrigin = ExternalSettlementOrigin.CONTRACTUAL_EFFECT_SETTLEMENT_BATCH
            search.disableSort = true
            Bank santanderBank = Bank.findByCode(SupportedBank.SANTANDER.code())

            List<Long> santanderFinancialTransactionIdList = FinancialTransactionExternalSettlement.query(search + [bank: santanderBank]).list()
            if (santanderFinancialTransactionIdList) financialStatementService.saveAccountingEntriesForTransactionIdList(santanderFinancialTransactionIdList, FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_CREDIT, santanderBank)

            Bank bradescoBank = Bank.findByCode(SupportedBank.BRADESCO.code())
            List<Long> bradescoFinancialTransactionIdList = FinancialTransactionExternalSettlement.query(search + [bank: bradescoBank]).list()
            if (bradescoFinancialTransactionIdList) financialStatementService.saveAccountingEntriesForTransactionIdList(bradescoFinancialTransactionIdList, FinancialStatementType.EXTERNAL_SETTLEMENT_CONTRACTUAL_EFFECT_BATCH_CUSTOMER_BALANCE_CREDIT, bradescoBank)
        }, [logErrorMessage: "FinancialStatementExternalSettlementService.createForContractualEffectBatchCustomerBalanceCredit - Erro ao realizar lançamentos contábeis de estorno para as liquidações de efeito de contrato"])
    }
}
