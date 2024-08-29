package com.asaas.service.financialstatement

import com.asaas.debit.DebitType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementManualDebitService {

    def financialStatementService

    public void createAllStatements() {
        Date yesterday = CustomDateUtils.getYesterday()

        Utils.withNewTransactionAndRollbackOnError({
            createBacenjudStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createBacenjudStatements >> Falha ao gerar os lançamentos contábeis/financeiros de débitos de processo judicial BACENJUD."])

        Utils.withNewTransactionAndRollbackOnError({
            createBalanceZeroingReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createBalanceZeroingReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de débitos de reversão de zeramento de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createBacenReservesTransferSystemTransferStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createBacenReservesTransferSystemTransferStatements >> Falha ao gerar os lançamentos contábeis/financeiros de devolução de transferências STR."])

        Utils.withNewTransactionAndRollbackOnError({
            createEthocaMastercardReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createEthocaMastercardReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de estorno Ethoca Mastercard."])

        Utils.withNewTransactionAndRollbackOnError({
            createManualInternalTransferStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createManualInternalTransferStatements >> Falha ao gerar os lançamentos contábeis/financeiros de transferência manual entre contas Asaas."])

        Utils.withNewTransactionAndRollbackOnError({
            createFraudRecoveryAppropriationStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createFraudRecoveryAppropriationStatements >> Falha ao gerar os lançamentos contábeis/financeiros de apropriação de valor por fraude."])

        Utils.withNewTransactionAndRollbackOnError({
            createValuesToRefundSvrStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createValuesToRefundSvrStatements >> Falha ao gerar os lançamentos contábeis/financeiros de valores esquecidos SVR."])

        Utils.withNewTransactionAndRollbackOnError({
            createCommissionTaxStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementManualDebitService.createCommissionTaxStatements >> Falha ao gerar os lançamentos contábeis/financeiros de imposto sobre comissões."])
    }

    public void createCommissionTaxStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(DebitType.TAX, [FinancialStatementType.COMMISSION_TAX_CREDIT, FinancialStatementType.COMMISSION_TAX_DEBIT], transactionDate)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.COMMISSION_TAX_CREDIT],
            [financialStatementType: FinancialStatementType.COMMISSION_TAX_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createValuesToRefundSvrStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.SVR_REFUND_VALUE, [FinancialStatementType.SVR_REFUND_VALUE_DEBIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.SVR_REFUND_VALUE_DEBIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], bank)
    }

    private void createBacenjudStatements(Date transactionDate) {
        Map search = [:]
        search.transactionTypeList = [FinancialTransactionType.DEBIT, FinancialTransactionType.BACEN_JUDICIAL_TRANSFER]
        search.debitTypeList = [DebitType.JUDICIAL_PROCESS_BACENJUD, DebitType.JUDICIAL_TRANSFER]
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.JUDICIAL_PROCESS_BACENJUD_DEBIT]
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!transactionList) return

        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_BACENJUD_DEBIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], null)
    }

    private void createBalanceZeroingReversalStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.BALANCE_ZEROING_REVERSAL, [FinancialStatementType.BALANCE_ZEROING_REVERSAL_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.BALANCE_ZEROING_REVERSAL_CREDIT],
            [financialStatementType: FinancialStatementType.BALANCE_ZEROING_REVERSAL_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createBacenReservesTransferSystemTransferStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.BACEN_RESERVES_TRANSFER_SYSTEM_TRANSFER, [FinancialStatementType.BACEN_RESERVES_TRANSFER_SYSTEM_TRANSFER_DEBIT], transactionDate)
        if (!transactionList) return

        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.BACEN_RESERVES_TRANSFER_SYSTEM_TRANSFER_DEBIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], null)
    }

    private void createEthocaMastercardReversalStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.ETHOCA_MASTERCARD_REVERSAL, [FinancialStatementType.ETHOCA_MASTERCARD_REVERSAL_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.ETHOCA_MASTERCARD_REVERSAL_CREDIT],
            [financialStatementType: FinancialStatementType.ETHOCA_MASTERCARD_REVERSAL_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createManualInternalTransferStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.MANUAL_INTERNAL_TRANSFER, [FinancialStatementType.MANUAL_INTERNAL_TRANSFER_DEBIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.MANUAL_INTERNAL_TRANSFER_DEBIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], bank)
    }

    private void createFraudRecoveryAppropriationStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.FRAUD_RECOVERY, [FinancialStatementType.FRAUD_RECOVERY_APPROPRIATION_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.FRAUD_RECOVERY_APPROPRIATION_CREDIT],
            [financialStatementType: FinancialStatementType.FRAUD_RECOVERY_APPROPRIATION_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateStatements(DebitType debitType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.DEBIT
        search.debitType = debitType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
