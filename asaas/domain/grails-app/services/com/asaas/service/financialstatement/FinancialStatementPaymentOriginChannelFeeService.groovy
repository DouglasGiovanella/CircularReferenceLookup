package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementPaymentOriginChannelFeeService {

    def financialStatementService

    public void createForPaymentOriginChannelFee(Date transactionDate) {
        AsaasLogger.info("FinancialStatementPaymentOriginChannelFeeService >> createForPaymentOriginChannelFee")
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_ORIGIN_CHANNEL_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_ORIGIN_CHANNEL_FEE_CREDIT, FinancialStatementType.PAYMENT_ORIGIN_CHANNEL_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_ORIGIN_CHANNEL_FEE_CREDIT],
                [financialStatementType: FinancialStatementType.PAYMENT_ORIGIN_CHANNEL_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementPaymentOriginChannelFeeService.createForPaymentOriginChannelFee - Erro ao executar"])
    }
}
