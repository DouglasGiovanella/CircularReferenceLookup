package com.asaas.service.financialstatement.financialstatementitem

import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.bank.Bank
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementAcquirerUtils
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.redisson.client.RedisException

@Transactional
class FinancialStatementItemForDomainObjectService {

    def financialStatementItemService
    def financialStatementService

    @Transactional(noRollbackFor = RedisException)
    public void save(Object domainObject, FinancialStatementType financialStatementType, Date statementDate, Bank bank) {
        FinancialStatement financialStatement = financialStatementService.readExistingFinancialStatementOrCreate(financialStatementType, statementDate, bank)

        if (!financialStatement) {
            financialStatementItemService.saveAsyncAction(domainObject, financialStatementType, statementDate, bank?.id)
            return
        }

        Boolean financialStatementItemExists = FinancialStatementItem.query([financialStatementId: financialStatement.id, domainInstance: domainObject, exists: true]).get()
        if (financialStatementItemExists) {
            AsaasLogger.warn("FinancialStatementService.saveItemForDomainObject >> FinancialStatementItem já existe. [financialStatementId: ${financialStatement.id}, Type: ${financialStatementType}, statementDate: ${statementDate}, domainObject: ${domainObject.id}]")
            return
        }

        financialStatementItemService.save(financialStatement, domainObject)
    }

    @Transactional(noRollbackFor = RedisException)
    public void saveForFinancialTransaction(FinancialTransaction financialTransaction) {
        List<FinancialStatementType> financialStatementTypeList = []
        Bank bank = null

        switch (financialTransaction.transactionType) {
            case FinancialTransactionType.PAYMENT_RECEIVED:
                financialStatementTypeList.addAll(getFinancialStatementTypeForPaymentReceived(financialTransaction))
                break
            case FinancialTransactionType.PIX_TRANSACTION_CREDIT:
                financialStatementTypeList.addAll(getFinancialStatementTypeForPixTransactionCredit(financialTransaction))
                break
            case FinancialTransactionType.CREDIT:
                financialStatementTypeList.addAll(listStatementTypeForCredit(financialTransaction))
                bank = Bank.findByCode(SupportedBank.SANTANDER.code())
                break
            case FinancialTransactionType.DEBIT:
                financialStatementTypeList.addAll(listStatementTypeForDebit(financialTransaction))
                bank = Bank.findByCode(SupportedBank.SANTANDER.code())
                break
            default:
                return
        }

        if (!financialStatementTypeList) return

        for (FinancialStatementType statementType : financialStatementTypeList) {
            Date transactionDate = financialTransaction.transactionDate
            save(financialTransaction, statementType, transactionDate, bank)
        }
    }

    private List<FinancialStatementType> getFinancialStatementTypeForPaymentReceived(FinancialTransaction financialTransaction) {
        if (financialTransaction.payment.billingType.isCreditCard()) {
            final Date statementProcessingWithTransactionStartDate = CustomDateUtils.fromString("05/07/2024")
            if (financialTransaction.transactionDate < statementProcessingWithTransactionStartDate) return []

            CreditCardAcquirer acquirer = CreditCardTransactionInfo.query([column: "acquirer", paymentId: financialTransaction.payment.id]).get()
            return [FinancialStatementAcquirerUtils.getAcquirerCreditCardReceivedTransitoryCreditFinancialStatementType(acquirer)]
        }

        if (financialTransaction.payment.billingType.isPix()) {
            Boolean isPixTransactionWithPayment = FinancialTransaction.query([
                exists: true,
                id: financialTransaction.id,
                "hasCreditPixTransactionWithAsaasKey[notExists]": true,
                "internalPixCreditTransaction[notExists]": true,
                "cashInRiskAnalysisRequestReason[notExists]": true
            ]).get()
            if (!isPixTransactionWithPayment) return []

            return [FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE]
        }

        return []
    }

    private List<FinancialStatementType> getFinancialStatementTypeForPixTransactionCredit(FinancialTransaction financialTransaction) {
        Boolean isPixTransactionWithoutPayment = FinancialTransaction.query([
            exists: true,
            id: financialTransaction.id,
            hasExternalPixCreditTransactionWithoutPayment: true
        ]).get()
        if (!isPixTransactionWithoutPayment) return []

        return [FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE]
    }

    private List<FinancialStatementType> listStatementTypeForCredit(FinancialTransaction financialTransaction) {
        if (!financialTransaction.credit.type.isConfirmedFraudBalanceZeroing()) return []

        return [FinancialStatementType.CONFIRMED_FRAUD_BALANCE_ZEROING_CREDIT, FinancialStatementType.CONFIRMED_FRAUD_BALANCE_ZEROING_DEBIT]
    }

    private List<FinancialStatementType> listStatementTypeForDebit(FinancialTransaction financialTransaction) {
        if (financialTransaction.debit.type.isConfirmedFraudBalanceZeroing()) return [FinancialStatementType.CONFIRMED_FRAUD_BALANCE_ZEROING_REVERSAL_CREDIT, FinancialStatementType.CONFIRMED_FRAUD_BALANCE_ZEROING_REVERSAL_DEBIT]

        if (financialTransaction.debit.type.isConfirmedFraudPositiveBalanceAppropriation()) return [FinancialStatementType.CONFIRMED_FRAUD_POSITIVE_BALANCE_APPROPRIATION_CREDIT, FinancialStatementType.CONFIRMED_FRAUD_POSITIVE_BALANCE_APPROPRIATION_DEBIT]

        return []
    }
}
