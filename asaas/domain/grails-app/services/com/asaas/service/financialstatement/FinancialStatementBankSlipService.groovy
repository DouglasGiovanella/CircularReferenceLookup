package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.domain.payment.PaymentConfirmRequestGroup
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementBankSlipService {

    def financialStatementPaymentService
    def financialStatementService

    public void createFinancialStatementsForBankSlip(Date startDate, Date endDate) {
        AsaasLogger.info("FinancialStatementBankSlipService >> createFinancialStatementsForBankSlip")
        List<Long> boletoBankIdList = BoletoBank.query([column: "id"]).list()

        for (Date date in startDate..endDate) {
            for (Long boletoBankId : boletoBankIdList) {
                createBankSlipStatementsByBoletoBankId(boletoBankId, date)
            }

            createBankSlipStatementsByBoletoBankId(null, date)
        }
    }

    public void createForApprovedPaymentConfirmRequestGroups(Date startDate, Date endDate) {
        AsaasLogger.info("FinancialStatementBankSlipService >> createForApprovedPaymentConfirmRequestGroups")
        Utils.withNewTransactionAndRollbackOnError({
            List<Map> paymentConfirmRequestGroupList = PaymentConfirmRequestGroup.query([
                columnList: ["id", "paymentBank"],
                status: Status.APPROVED,
                "approvedDate[ge]": startDate,
                "approvedDate[le]": endDate
            ]).list()

            for (Map paymentConfirmRequestGroup in paymentConfirmRequestGroupList) {
                List<Long> paymentIdListToCreateFinancialStatements = PaymentConfirmRequest.query([
                    groupId: paymentConfirmRequestGroup.id,
                    column: "payment.id",
                    billingType: BillingType.BOLETO,
                    status: Status.SUCCESS,
                    duplicatedPayment: false,
                    "financialStatementTypeList[notExists]": [FinancialStatementType.CUSTOMER_REVENUE, FinancialStatementType.BANK_SLIP_CUSTOMER_REVENUE, FinancialStatementType.BANK_FEE_EXPENSE, FinancialStatementType.BANKSLIP_SETTLEMENT_ASAAS_EXPENSE]
                ]).list()

                if (paymentIdListToCreateFinancialStatements) {
                    financialStatementPaymentService.saveForConfirmedPaymentsWithNewTransaction(paymentConfirmRequestGroup.paymentBank, paymentIdListToCreateFinancialStatements)
                }
            }
        }, [logErrorMessage: "FinancialStatementBankSlipService - Erro ao executar createForApprovedPaymentConfirmRequestGroups"])
    }

    public void createFinancialStatementsForBankSlipReversal(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError ({
            createBankSlipPaymentReversalStatements(transactionDate)
        }, [logErrorMessage: "FinancialStatementBankSlipService.createBankSlipPaymentReversalStatements >> Falha ao gerar os lançamentos de estorno de boleto."])

        Utils.withNewTransactionAndRollbackOnError ({
            createBankSlipPaymentFeeReversalStatements(transactionDate)
        }, [logErrorMessage: "FinancialStatementBankSlipService.createBankSlipPaymentFeeReversalStatements >> Falha ao gerar os lançamentos de estorno de taxa de boleto."])

        Utils.withNewTransactionAndRollbackOnError ({
            createBankSlipPromotionalCodeReversalStatements(transactionDate)
        }, [logErrorMessage: "FinancialStatementBankSlipService.createBankSlipPromotionalCodeReversalStatements >> Falha ao gerar os lançamentos de estorno de desconto promocional."])
    }

    private void createBankSlipStatementsByBoletoBankId(Long boletoBankId, Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            Bank bank = boletoBankId ? BoletoBank.query([column: "bank", id: boletoBankId]).get() : Bank.findByCode(SupportedBank.SANTANDER.code())

            createBankSlipFeeStatementsByBoletoBankId(boletoBankId, bank, date)
            createBankSlipFeePromotionalCodeStatementsByBoletoBankId(boletoBankId, bank, date)
        }, [logErrorMessage: "FinancialStatementBankSlipService - Erro ao executar createBankSlipStatementsByBoletoBankId",
            onError: { error -> throw error }])
    }

    private void createBankSlipFeeStatementsByBoletoBankId(Long boletoBankId, Bank bank, Date date) {
        Map search = [
            transactionType: FinancialTransactionType.PAYMENT_FEE,
            paymentBillingType: BillingType.BOLETO,
            transactionDate: date,
            "paymentFeeStatement[notExists]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.BANK_SLIP_PAYMENT_FEE_CREDIT]
        ]
        if (boletoBankId) {
            search += [paymentBoletoBankId: boletoBankId]
        } else {
            search += ["paymentBoletoBankId[isNull]": true]
        }
        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!feeTransactionList) return

        List<Map> financialStatementTypeInfoList = [
            [financialStatementType: FinancialStatementType.BANK_SLIP_PAYMENT_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.BANK_SLIP_PAYMENT_FEE_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", feeTransactionList, financialStatementTypeInfoList, bank)
    }

    private void createBankSlipFeePromotionalCodeStatementsByBoletoBankId(Long boletoBankId, Bank bank, Date date) {
        Map search = [
            transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
            paymentBillingTypeList: [BillingType.BOLETO],
            transactionDate: date,
            "paymentFeeDiscountStatement[notExists]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE, FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT],
            "payment[isNotNull]": true
        ]
        if (boletoBankId) {
            search += [paymentBoletoBankId: boletoBankId]
        } else {
            search += ["paymentBoletoBankId[isNull]": true]
        }
        List<FinancialTransaction> feeDiscountTransactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!feeDiscountTransactionList) return

        financialStatementPaymentService.saveFeeDiscount(feeDiscountTransactionList, bank)
    }

    private void createBankSlipPaymentReversalStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listBankSlipReversalTransactions(FinancialTransactionType.PAYMENT_REVERSAL, [FinancialStatementType.BANK_SLIP_PAYMENT_REVERSAL_DEBIT], transactionDate)
        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.BANK_SLIP_PAYMENT_REVERSAL_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private void createBankSlipPaymentFeeReversalStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listBankSlipReversalTransactions(FinancialTransactionType.PAYMENT_FEE_REVERSAL, [FinancialStatementType.BANK_SLIP_PAYMENT_FEE_REVERSAL_CREDIT, FinancialStatementType.BANK_SLIP_PAYMENT_FEE_REVERSAL_DEBIT], transactionDate)
        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.BANK_SLIP_PAYMENT_FEE_REVERSAL_CREDIT],
            [financialStatementType: FinancialStatementType.BANK_SLIP_PAYMENT_FEE_REVERSAL_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private void createBankSlipPromotionalCodeReversalStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listBankSlipReversalTransactions(FinancialTransactionType.PROMOTIONAL_CODE_DEBIT, [FinancialStatementType.BANK_SLIP_PROMOTIONAL_CODE_REVERSAL_CREDIT, FinancialStatementType.BANK_SLIP_PROMOTIONAL_CODE_REVERSAL_DEBIT], transactionDate)
        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.BANK_SLIP_PROMOTIONAL_CODE_REVERSAL_CREDIT],
            [financialStatementType: FinancialStatementType.BANK_SLIP_PROMOTIONAL_CODE_REVERSAL_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private List<FinancialTransaction> listBankSlipReversalTransactions(FinancialTransactionType financialTransactionType, List<FinancialStatementType> financialStatementTypeList, Date transactionDate) {
        Map search = [
            transactionType: financialTransactionType,
            transactionDate: transactionDate,
            paymentBillingType: BillingType.BOLETO,
            paymentStatus: PaymentStatus.REFUNDED,
            "financialStatementTypeList[notExists]": financialStatementTypeList
        ]

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
