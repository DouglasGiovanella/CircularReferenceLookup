package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.internaltransfer.InternalTransferType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementAsaasMoneyService {

    def financialStatementItemService
    def financialStatementService

    public void createFinancialStatements(Date startDate, Date endDate) {
        if (!startDate || !endDate) throw new RuntimeException("As datas de início e de fim devem ser informadas")

        AsaasLogger.info("createFinancialStatements >> createForAsaasMoneyBackingInternalTransfer")
        Utils.withNewTransactionAndRollbackOnError({
            createForAsaasMoneyBackingInternalTransfer(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createForAsaasMoneyBackingInternalTransfer."])

        AsaasLogger.info("createFinancialStatements >> createForAsaasMoneyBackingInternalTransferRefund")
        Utils.withNewTransactionAndRollbackOnError({
            createForAsaasMoneyBackingInternalTransferRefund(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createForAsaasMoneyBackingInternalTransferRefund."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyAnticipationFeeStatements")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyAnticipationFeeStatements(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyAnticipationFeeStatements."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyAnticipationFeeStatementsRefund")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyAnticipationFeeStatementsRefund(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyAnticipationFeeStatementsRefund."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyFinancingFeeStatements")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyFinancingFeeStatements(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyFinancingFeeStatements."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyFinancingFeeStatementsRefund")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyFinancingFeeStatementsRefund(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyFinancingFeeStatementsRefund."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyDiscountInternalTransferStatements")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyDiscountInternalTransferStatements(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyDiscountInternalTransferStatements."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyDiscountInternalTransferStatementsRefund")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyDiscountInternalTransferStatementsRefund(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyDiscountInternalTransferStatementsRefund."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyCashbackStatements")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyCashbackStatements(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyCashbackStatements."])

        AsaasLogger.info("createFinancialStatements >> createAsaasMoneyCashbackStatementsRefund")
        Utils.withNewTransactionAndRollbackOnError({
            createAsaasMoneyCashbackStatementsRefund(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementAsaasMoneyService.createFinancialStatements() -> Erro ao executar createAsaasMoneyCashbackStatementsRefund."])
    }

    public void createForProviderCreditCard(Bank bank, List<Long> asaasMoneyPaymentIdList) {
        List<Payment> asaasMoneyCreditCardPaymentList = Payment.query(["id[in]": asaasMoneyPaymentIdList, billingType: BillingType.MUNDIPAGG_CIELO, "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_MONEY_PROVIDER_CREDIT_CARD_REVENUE]]).list(readOnly: true)
        if (!asaasMoneyCreditCardPaymentList) return

        Payment firstPayment = asaasMoneyCreditCardPaymentList.first()
        Date creditDate = CreditCardTransactionInfo.query([column: "creditDate", paymentId: firstPayment.id]).get()
        Date statementDate = creditDate ?: firstPayment.paymentDate

        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.ASAAS_MONEY_PROVIDER_CREDIT_CARD_REVENUE, statementDate, bank, asaasMoneyCreditCardPaymentList.value.sum())

        financialStatementItemService.saveInBatch(financialStatement, asaasMoneyCreditCardPaymentList)
    }

    private void createForAsaasMoneyBackingInternalTransfer(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_DEBIT, FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_CREDIT]
        queryParameters.internalTransferType = InternalTransferType.ASAAS_MONEY
        queryParameters."dateCreated[ge]" = startDate
        queryParameters."dateCreated[lt]" = endDate

        List<FinancialTransaction> debitTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT]).list(readOnly: true)
        List<FinancialTransaction> creditTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_CREDIT]).list(readOnly: true)

        if (debitTransactionList) {
            List<Map> debitStatementInfoList = []
            debitStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_DEBIT])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", debitTransactionList, debitStatementInfoList, bank)
        }

        if (creditTransactionList) {
            List<Map> creditStatementInfoList = []
            creditStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_CREDIT])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", creditTransactionList, creditStatementInfoList, bank)
        }
    }

    private void createForAsaasMoneyBackingInternalTransferRefund(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_DEBIT_REFUND, FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_CREDIT_REFUND]
        queryParameters.internalTransferType = InternalTransferType.ASAAS_MONEY_REVERSAL
        queryParameters."dateCreated[ge]" = startDate
        queryParameters."dateCreated[lt]" = endDate

        List<FinancialTransaction> debitTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT]).list(readOnly: true)
        List<FinancialTransaction> creditTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_CREDIT]).list(readOnly: true)

        if (debitTransactionList) {
            List<Map> debitStatementInfoList = []
            debitStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_CREDIT_REFUND])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", debitTransactionList, debitStatementInfoList, bank)
        }

        if (creditTransactionList) {
            List<Map> creditStatementInfoList = []
            creditStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_INTERNAL_TRANSFER_DEBIT_REFUND])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", creditTransactionList, creditStatementInfoList, bank)
        }
    }

    private void createAsaasMoneyAnticipationFeeStatements(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE
        queryParameters.'financialStatementTypeList[notExists]' = [FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_REVENUE, FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_CUSTOMER_BALANCE_DEBIT]
        queryParameters.'dateCreated[ge]' = startDate
        queryParameters.'dateCreated[lt]' = endDate

        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!feeTransactionList) return

        List<Map> paymentAnticipationFeeRevenueStatementInfoList = []
        paymentAnticipationFeeRevenueStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_REVENUE])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, paymentAnticipationFeeRevenueStatementInfoList, bank)

        List<Map> paymentAnticipationFeeCustomerDebitStatementInfoList = []
        paymentAnticipationFeeCustomerDebitStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_CUSTOMER_BALANCE_DEBIT])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, paymentAnticipationFeeCustomerDebitStatementInfoList, bank)
    }

    private void createAsaasMoneyAnticipationFeeStatementsRefund(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_REFUND
        queryParameters.'financialStatementTypeList[notExists]' = [FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_REVENUE_REFUND, FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_CUSTOMER_BALANCE_DEBIT_REFUND]
        queryParameters.'dateCreated[ge]' = startDate
        queryParameters.'dateCreated[lt]' = endDate

        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!feeTransactionList) return

        List<Map> revenueRefundStatementInfoList = []
        revenueRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_REVENUE_REFUND])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, revenueRefundStatementInfoList, bank)

        List<Map> debitRefundStatementInfoList = []
        debitRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_ANTICIPATION_FEE_CUSTOMER_BALANCE_DEBIT_REFUND])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, debitRefundStatementInfoList, bank)
    }

    private void createAsaasMoneyFinancingFeeStatements(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.ASAAS_MONEY_PAYMENT_FINANCING_FEE
        queryParameters.'financialStatementTypeList[notExists]' = [FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_REVENUE, FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_CUSTOMER_BALANCE_DEBIT]
        queryParameters.'dateCreated[ge]' = startDate
        queryParameters.'dateCreated[lt]' = endDate

        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!feeTransactionList) return

        List<Map> paymentFinancingFeeRevenueStatementInfoList = []
        paymentFinancingFeeRevenueStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_REVENUE])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, paymentFinancingFeeRevenueStatementInfoList, bank)

        List<Map> paymentFinancingFeeCustomerDebitStatementInfoList = []
        paymentFinancingFeeCustomerDebitStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_CUSTOMER_BALANCE_DEBIT])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, paymentFinancingFeeCustomerDebitStatementInfoList, bank)
    }

    private void createAsaasMoneyFinancingFeeStatementsRefund(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_REFUND
        queryParameters.'financialStatementTypeList[notExists]' = [FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_REVENUE_REFUND, FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_CUSTOMER_BALANCE_DEBIT_REFUND]
        queryParameters.'dateCreated[ge]' = startDate
        queryParameters.'dateCreated[lt]' = endDate

        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!feeTransactionList) return

        List<Map> revenueRefundStatementInfoList = []
        revenueRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_REVENUE_REFUND])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, revenueRefundStatementInfoList, bank)

        List<Map> debitRefundStatementInfoList = []
        debitRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_PAYMENT_FINANCING_FEE_CUSTOMER_BALANCE_DEBIT_REFUND])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", feeTransactionList, debitRefundStatementInfoList, bank)
    }

    private void createAsaasMoneyDiscountInternalTransferStatements(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_MONEY_DISCOUNT_DEBIT, FinancialStatementType.ASAAS_MONEY_DISCOUNT_CREDIT]
        queryParameters.internalTransferType = InternalTransferType.ASAAS_MONEY_DISCOUNT
        queryParameters."dateCreated[ge]" = startDate
        queryParameters."dateCreated[lt]" = endDate

        List<FinancialTransaction> debitTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT]).list(readOnly: true)
        List<FinancialTransaction> creditTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_CREDIT]).list(readOnly: true)

        if (debitTransactionList) {
            List<Map> debitStatementInfoList = []
            debitStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_DISCOUNT_DEBIT])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", debitTransactionList, debitStatementInfoList, bank)
        }

        if (creditTransactionList) {
            List<Map> creditStatementInfoList = []
            creditStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_DISCOUNT_CREDIT])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", creditTransactionList, creditStatementInfoList, bank)
        }
    }

    private void createAsaasMoneyDiscountInternalTransferStatementsRefund(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_MONEY_DISCOUNT_DEBIT_REFUND, FinancialStatementType.ASAAS_MONEY_DISCOUNT_CREDIT_REFUND]
        queryParameters.internalTransferType = InternalTransferType.ASAAS_MONEY_DISCOUNT_REVERSAL
        queryParameters."dateCreated[ge]" = startDate
        queryParameters."dateCreated[lt]" = endDate

        List<FinancialTransaction> debitTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT]).list(readOnly: true)
        List<FinancialTransaction> creditTransactionList = FinancialTransaction.query(queryParameters + [transactionType: FinancialTransactionType.INTERNAL_TRANSFER_CREDIT]).list(readOnly: true)

        if (debitTransactionList) {
            List<Map> debitRefundStatementInfoList = []
            debitRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_DISCOUNT_DEBIT_REFUND])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", debitTransactionList, debitRefundStatementInfoList, bank)
        }

        if (creditTransactionList) {
            List<Map> creditRefundStatementInfoList = []
            creditRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_DISCOUNT_CREDIT_REFUND])
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", creditTransactionList, creditRefundStatementInfoList, bank)
        }
    }

    public void createAsaasMoneyCashbackStatements(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CASHBACK
        queryParameters.'financialStatementTypeList[notExists]' = [FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_ASAAS_EXPENSE, FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_CUSTOMER_BALANCE_CREDIT]
        queryParameters.'dateCreated[ge]' = startDate
        queryParameters.'dateCreated[lt]' = endDate

        List<FinancialTransaction> cashbackTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!cashbackTransactionList) return

        List<Map> asaasExpenseStatementInfoList = []
        asaasExpenseStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_ASAAS_EXPENSE])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", cashbackTransactionList, asaasExpenseStatementInfoList, bank)

        List<Map> customerBalanceCreditStatementInfoList = []
        customerBalanceCreditStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_CUSTOMER_BALANCE_CREDIT])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", cashbackTransactionList, customerBalanceCreditStatementInfoList, bank)
    }

    public void createAsaasMoneyCashbackStatementsRefund(Date startDate, Date endDate) {
        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CASHBACK_REFUND
        queryParameters.'financialStatementTypeList[notExists]' = [FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_ASAAS_EXPENSE_REFUND, FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_CUSTOMER_BALANCE_CREDIT_REFUND]
        queryParameters.'dateCreated[ge]' = startDate
        queryParameters.'dateCreated[lt]' = endDate

        List<FinancialTransaction> cashbackTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!cashbackTransactionList) return

        List<Map> asaasExpenseRefundStatementInfoList = []
        asaasExpenseRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_ASAAS_EXPENSE_REFUND])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", cashbackTransactionList, asaasExpenseRefundStatementInfoList, bank)

        List<Map> customerBalanceCreditRefundStatementInfoList = []
        customerBalanceCreditRefundStatementInfoList.add([financialStatementType: FinancialStatementType.ASAAS_MONEY_TRANSACTION_CASHBACK_CUSTOMER_BALANCE_CREDIT_REFUND])
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", cashbackTransactionList, customerBalanceCreditRefundStatementInfoList, bank)
    }
}
