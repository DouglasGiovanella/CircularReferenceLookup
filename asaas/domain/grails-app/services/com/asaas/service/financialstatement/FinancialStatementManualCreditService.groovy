package com.asaas.service.financialstatement

import com.asaas.credit.CreditType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementManualCreditService {

    def financialStatementService

    public void createAllStatements(Date date) {
        Utils.withNewTransactionAndRollbackOnError ({
            createBacenReservesTransferSystemTransferCreditStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createBacenReservesTransferSystemTransferCreditStatements >> Falha ao gerar os lançamentos de crédito para clientes em transferência."])

        Utils.withNewTransactionAndRollbackOnError ({
            createForDebtRenegotiation(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createForDebtRenegotiation >> Falha ao gerar os lançamentos de crédito referente à renegociação de dívida."])

        Utils.withNewTransactionAndRollbackOnError({
            createBacenjudStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createBacenjudStatements >> Falha ao gerar os lançamentos contábeis/financeiros de estornos de débitos de processo judicial BACENJUD."])

        Utils.withNewTransactionAndRollbackOnError({
            createCreditCardReceivingAsaasDomicileStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createCreditCardReceivingAsaasDomicileStatements >> Falha ao gerar os lançamentos contábeis de recebimento de cartão de crédito (Asaas como domicílio)."])

        Utils.withNewTransactionAndRollbackOnError({
            createBalanceZeroingStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createBalanceZeroingStatements >> Falha ao gerar os lançamentos contábeis de zeramento de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createChargebackEloStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createChargebackEloStatements >> Falha ao gerar os lançamentos contábeis/financeiros de chargeback ELO."])

        Utils.withNewTransactionAndRollbackOnError({
            createManualInternalTransferStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createManualInternalTransferStatements >> Falha ao gerar os lançamentos contábeis/financeiros de transferência manual entre contas Asaas."])

        Utils.withNewTransactionAndRollbackOnError({
            createFraudRecoveryAppropriationReversalStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createFraudRecoveryAppropriationReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de estorno de apropriação de valor por fraude."])

        Utils.withNewTransactionAndRollbackOnError({
            createMessagingFeeRefundStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createMessagingFeeRefundStatements >> Falha ao gerar os lançamentos contábeis/financeiros de estorno de taxa de mensageria."])

        Utils.withNewTransactionAndRollbackOnError({
            createCreditCardAnticipationAsaasDomicileStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createCreditCardAnticipationAsaasDomicileStatements >> Falha ao gerar os lançamentos contábeis de antecipação de cartão de crédito (Asaas como domicílio)."])

        Utils.withNewTransactionAndRollbackOnError({
            createTransferFeeRefundStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createTransferFeeRefundStatements >> Falha ao gerar os lançamentos contábeis/financeiros de estorno de taxa de transferência via remessa."])

        Utils.withNewTransactionAndRollbackOnError({
            createPixFeeRefundStatements(date)
        }, [logErrorMessage: "FinancialStatementManualCreditService.createPixFeeRefundStatements >> Falha ao gerar os lançamentos contábeis/financeiros de estorno de taxa de Pix."])
    }

    private void createBacenReservesTransferSystemTransferCreditStatements(Date date) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(CreditType.BACEN_RESERVES_TRANSFER_SYSTEM_TRANSFER, [FinancialStatementType.BACEN_RESERVES_TRANSFER_SYSTEM_TRANSFER_CREDIT], date)
        if (!transactionList) return

        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.BACEN_RESERVES_TRANSFER_SYSTEM_TRANSFER_CREDIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], null)
    }

    private void createForDebtRenegotiation(Date date) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(CreditType.DEBT_RENEGOTIATION, [FinancialStatementType.DEBT_RENEGOTIATION_CUSTOMER_BALANCE_CREDIT], date)
        if (!transactionList) return

        Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.DEBT_RENEGOTIATION_CUSTOMER_BALANCE_CREDIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], santander)
    }

    private void createBacenjudStatements(Date date) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(CreditType.JUDICIAL_PROCESS_BACENJUD, [FinancialStatementType.JUDICIAL_PROCESS_BACENJUD_DEBIT_REFUND], date)
        if (!transactionList) return

        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_BACENJUD_DEBIT_REFUND
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], null)
    }

    private void createCreditCardReceivingAsaasDomicileStatements(Date date) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(CreditType.CREDIT_CARD_RECEIVING_ASAAS_DOMICILE, [FinancialStatementType.CREDIT_CARD_RECEIVING_ASAAS_DOMICILE_CREDIT], date)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [
            financialStatementType: FinancialStatementType.CREDIT_CARD_RECEIVING_ASAAS_DOMICILE_CREDIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfoMap], bank)
    }

    private void createBalanceZeroingStatements(Date date) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(CreditType.BALANCE_ZEROING, [FinancialStatementType.BALANCE_ZEROING_CREDIT, FinancialStatementType.BALANCE_ZEROING_DEBIT], date)
        if (!financialTransactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.BALANCE_ZEROING_CREDIT],
            [financialStatementType: FinancialStatementType.BALANCE_ZEROING_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createChargebackEloStatements(Date date) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(CreditType.CHARGEBACK_ELO, [FinancialStatementType.CHARGEBACK_ELO_CREDIT], date)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CHARGEBACK_ELO_CREDIT],
            [financialStatementType: FinancialStatementType.CHARGEBACK_ELO_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createManualInternalTransferStatements(Date date) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(CreditType.MANUAL_INTERNAL_TRANSFER, [FinancialStatementType.MANUAL_INTERNAL_TRANSFER_CREDIT], date)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.MANUAL_INTERNAL_TRANSFER_CREDIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfo], bank)
    }

    private void createFraudRecoveryAppropriationReversalStatements(Date date) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(CreditType.FRAUD_RECOVERY, [FinancialStatementType.FRAUD_RECOVERY_APPROPRIATION_REVERSAL_CREDIT], date)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.FRAUD_RECOVERY_APPROPRIATION_REVERSAL_CREDIT],
            [financialStatementType: FinancialStatementType.FRAUD_RECOVERY_APPROPRIATION_REVERSAL_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createMessagingFeeRefundStatements(Date date) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(CreditType.MESSAGING_FEE_REFUND, [FinancialStatementType.MESSAGING_FEE_REFUND_CREDIT, FinancialStatementType.MESSAGING_FEE_REFUND_DEBIT], date)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.MESSAGING_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.MESSAGING_FEE_REFUND_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createCreditCardAnticipationAsaasDomicileStatements(Date date) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(CreditType.CREDIT_CARD_ANTICIPATION_ASAAS_DOMICILE, [FinancialStatementType.CREDIT_CARD_ANTICIPATION_ASAAS_DOMICILE_CREDIT], date)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [
            financialStatementType: FinancialStatementType.CREDIT_CARD_ANTICIPATION_ASAAS_DOMICILE_CREDIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfoMap], bank)
    }

    private void createTransferFeeRefundStatements(Date date) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(CreditType.TRANSFER_FEE_REFUND, [FinancialStatementType.TRANSFER_FEE_REFUND_CREDIT, FinancialStatementType.TRANSFER_FEE_REFUND_DEBIT], date)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.TRANSFER_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.TRANSFER_FEE_REFUND_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createPixFeeRefundStatements(Date date) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(CreditType.PIX_FEE_REFUND, [FinancialStatementType.PIX_FEE_REFUND_CREDIT, FinancialStatementType.PIX_FEE_REFUND_DEBIT], date)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.BRADESCO.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PIX_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.PIX_FEE_REFUND_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateStatements(CreditType creditType, List<FinancialStatementType> statementTypeList, Date date) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.CREDIT
        search.creditType = creditType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = date

        return FinancialTransaction.query(search).list()
    }
}
