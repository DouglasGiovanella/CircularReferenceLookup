package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.credittransferrequest.CreditTransferRequestTransferBatchFileStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.transferbatchfile.TransferBatchFile
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementTransferService {

    def financialStatementService
    def financialStatementItemService
    def grailsApplication

    public void createFinancialStatementsForTransfer(Date limitDate) {
        Date startDate = CustomDateUtils.sumDays(limitDate, -1)
        Date endDate = limitDate.clearTime()

        AsaasLogger.info("FinancialStatementTransferService >> createFinancialStatementsForTransferForSupportedBanks")
        createFinancialStatementsForTransferForSupportedBanks(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createFinancialStatementsForFailedTransfer")
        createFinancialStatementsForFailedTransfer(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createTransitoryForRequestedTransfer")
        createTransitoryForRequestedTransfer(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createTransitoryForRequestedTransferFee")
        createTransitoryForRequestedTransferFee(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createTransitoryForReversedTransfer")
        createTransitoryForReversedTransfer(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createTransitoryForReversedTransferFee")
        createTransitoryForReversedTransferFee(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createTransitoryForConfirmedTransfer")
        createTransitoryForConfirmedTransfer(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransferService >> createTransitoryForConfirmedTransferFee")
        createTransitoryForConfirmedTransferFee(startDate, endDate)
    }

    public void createFinancialStatementsForReceivedTed(Date transactionDate) {
        Date tedTransactionReleaseStartDate = CustomDateUtils.fromString("01/01/2024")
        if (transactionDate < tedTransactionReleaseStartDate) return

        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
                                                                                              paymentBillingType: BillingType.TRANSFER,
                                                                                              "transactionDate": transactionDate,
                                                                                              "financialStatementTypeList[notExists]": [FinancialStatementType.TED_CUSTOMER_REVENUE, FinancialStatementType.CUSTOMER_REVENUE, FinancialStatementType.TRANSFER_CUSTOMER_REVENUE]]).list(readOnly: true)

            if (!financialTransactionList) return
            FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.TED_CUSTOMER_REVENUE, transactionDate, null, financialTransactionList.value.sum())

            financialStatementItemService.saveInBatch(financialStatement, financialTransactionList)
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createTransitoryForConfirmedTransfer"])
    }

    private void createFinancialStatementsForFailedTransfer(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Bank bank

            for (String bankCode : TransferBatchFile.SUPPORTED_BANK_CODES) {
                bank = Bank.query([code: bankCode]).get()

                createFinancialStatementsForFailedTransferByBank(startDate, endDate, bank)
                createFinancialStatementsForFailedTransferFeeByBank(startDate, endDate, bank)
            }
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createFinancialStatementsForFailedTransfer"])
    }

    private void createFinancialStatementsForFailedTransferByBank(Date startDate, Date endDate, Bank bank) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([transactionType: FinancialTransactionType.TRANSFER,
                                                                                 transferBatchFileBankId: bank.id,
                                                                                 transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.FAILED_INVALID_ACCOUNT,
                                                                                 'transferDate[ge]': startDate,
                                                                                 'transferDate[lt]': endDate,
                                                                                 'financialStatementTypeList[exists]': [FinancialStatementType.CUSTOMER_TRANSFER_EXPENSE],
                                                                                 'financialStatementTypeList[notExists]': [FinancialStatementType.FAILED_TRANSFER_CUSTOMER_BALANCE_CREDIT],
                                                                                 "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id]).list()

        if (!transactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.FAILED_TRANSFER_CUSTOMER_BALANCE_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoList, bank)
    }

    private void createFinancialStatementsForFailedTransferFeeByBank(Date startDate, Date endDate, Bank bank) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.TRANSFER_FEE,
            transferBatchFileBankId: bank.id,
            transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.FAILED_INVALID_ACCOUNT,
            'transferDate[ge]': startDate,
            'transferDate[lt]': endDate,
            'financialStatementTypeList[exists]': [FinancialStatementType.TRANSFER_FEE_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.TRANSFER_FEE_REVENUE],
            'financialStatementTypeList[notExists]': [FinancialStatementType.FAILED_TRANSFER_FEE_ASAAS_BALANCE_DEBIT, FinancialStatementType.FAILED_TRANSFER_FEE_CUSTOMER_BALANCE_CREDIT]
        ]).list()

        if (!transactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.FAILED_TRANSFER_FEE_ASAAS_BALANCE_DEBIT],
            [financialStatementType: FinancialStatementType.FAILED_TRANSFER_FEE_CUSTOMER_BALANCE_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoList, bank)
    }

    private void createFinancialStatementsForTransferForSupportedBanks(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Bank bank

            for (String bankCode : TransferBatchFile.SUPPORTED_BANK_CODES) {
                bank = Bank.findByCode(bankCode)

                createFinancialStatementsForTransferByBank(startDate, endDate, bank)
                createFinancialStatementsForTransferFeeByBank(startDate, endDate, bank)
                createFinancialStatementsForTransferFeeDiscountByBank(startDate, endDate, bank)
            }
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createFinancialStatementsForTransferForSupportedBanks"])
    }

    private void createFinancialStatementsForTransferByBank(Date startDate, Date endDate, Bank bank) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([transactionType: FinancialTransactionType.TRANSFER,
                                                                                 transferBatchFileBankId: bank.id,
                                                                                 transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.CONFIRMED,
                                                                                 'transferDate[ge]': startDate,
                                                                                 'transferDate[lt]': endDate,
                                                                                 'transferStatement[notExists]': true,
                                                                                 'financialStatementTypeList[notExists]': [FinancialStatementType.CUSTOMER_TRANSFER_EXPENSE],
                                                                                 "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id]).list()

        if (!transactionList) return

        Map transactionListGroupedByDate = transactionList.groupBy { it.creditTransferRequest.transferDate }

        transactionListGroupedByDate.each { Date transferDate, List<FinancialTransaction> financialTransactionListByDate ->
            saveTransferCustomerExpenseFinancialStatements(financialTransactionListByDate, bank, transferDate)
        }
    }

    private void createFinancialStatementsForTransferFeeByBank(Date startDate, Date endDate, Bank bank) {
        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.TRANSFER_FEE,
            transferBatchFileBankId: bank.id,
            transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.CONFIRMED,
            'transferDate[ge]': startDate,
            'transferDate[lt]': endDate,
            'transferFeeStatement[notExists]': true,
            'financialStatementTypeList[notExists]': [FinancialStatementType.TRANSFER_FEE_REVENUE, FinancialStatementType.TRANSFER_FEE_CUSTOMER_BALANCE_DEBIT]
        ]).list()

        if (!feeTransactionList) return

        Map feeTransactionListGroupedByDate = feeTransactionList.groupBy { it.creditTransferRequest.transferDate }

        feeTransactionListGroupedByDate.each { Date transferDate, List<FinancialTransaction> financialTransactionListByDate ->
            saveTransferFeeFinancialStatements(financialTransactionListByDate, bank, transferDate)
        }
    }

    private void createFinancialStatementsForTransferFeeDiscountByBank(Date startDate, Date endDate, Bank bank) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            transferBatchFileBankId: bank.id,
            transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.CONFIRMED,
            "transferDate[ge]": startDate,
            "transferDate[lt]": endDate,
            "transferPromotionalCodeStatement[notExists]": true,
            "transfer[isNotNull]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_FEE_DISCOUNT_EXPENSE, FinancialStatementType.TRANSFER_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
        ]).list()

        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.TRANSFER_FEE_DISCOUNT_EXPENSE],
            [financialStatementType: FinancialStatementType.TRANSFER_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createTransitoryForConfirmedTransfer(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([transactionType: FinancialTransactionType.TRANSFER,
                                                                                              transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.CONFIRMED,
                                                                                              "transferDate[ge]": startDate,
                                                                                              "transferDate[lt]": endDate,
                                                                                              "financialStatementTypeList[exists]": [FinancialStatementType.TRANSFER_REQUEST_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
                                                                                              "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_CONFIRMED_CUSTOMER_BALANCE_TRANSITORY_CREDIT],
                                                                                              "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id]).list()

            if (!financialTransactionList) return

            Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.creditTransferRequest.transferDate }

            financialTransactionListGroupedByDate.each { Date transferDate, List<FinancialTransaction> financialTransactionListByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.TRANSFER_CONFIRMED_CUSTOMER_BALANCE_TRANSITORY_CREDIT, transferDate, null, financialTransactionListByDate.value.sum())
                financialStatementItemService.saveInBatch(financialStatement, financialTransactionListByDate)
            }
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createTransitoryForConfirmedTransfer"])
    }

    private void createTransitoryForConfirmedTransferFee(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.TRANSFER_FEE,
                transferBatchFileStatus: CreditTransferRequestTransferBatchFileStatus.CONFIRMED,
                "transferDate[ge]": startDate,
                "transferDate[lt]": endDate,
                "financialStatementTypeList[exists]": [FinancialStatementType.TRANSFER_REQUEST_FEE_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
                "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_CONFIRMED_FEE_CUSTOMER_BALANCE_TRANSITORY_CREDIT],
                "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id
            ]).list()

            if (!financialTransactionList) return

            Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.creditTransferRequest.transferDate }

            financialTransactionListGroupedByDate.each { Date transferDate, List<FinancialTransaction> financialTransactionListByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.TRANSFER_CONFIRMED_FEE_CUSTOMER_BALANCE_TRANSITORY_CREDIT, transferDate, null, financialTransactionListByDate.value.sum())
                financialStatementItemService.saveInBatch(financialStatement, financialTransactionListByDate)
            }
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createTransitoryForConfirmedTransferFee"])
    }

    private void createTransitoryForRequestedTransfer(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.transactionDateDifferentFromTransferDate([
                transactionType: FinancialTransactionType.TRANSFER,
                "transactionDate[ge]": startDate,
                "transactionDate[lt]": endDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_REQUEST_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
                "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id
            ]).list()

            if (!financialTransactionList) return

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.TRANSFER_REQUEST_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

            financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createTransitoryForRequestedTransfer"])
    }

    private void createTransitoryForRequestedTransferFee(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.transactionDateDifferentFromTransferDate([
                transactionType: FinancialTransactionType.TRANSFER_FEE,
                "transactionDate[ge]": startDate,
                "transactionDate[lt]": endDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_REQUEST_FEE_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
                "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id
            ]).list()

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.TRANSFER_REQUEST_FEE_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]

            financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoList, null)
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createTransitoryForRequestedTransferFee"])
    }

    private void createTransitoryForReversedTransfer(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.REVERSAL,
                "transactionDate[ge]": startDate,
                "transactionDate[lt]": endDate,
                "reversedFinancialStatementType[exists]": FinancialStatementType.TRANSFER_REQUEST_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
                "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT],
                "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id
            ]).list()

            if (!financialTransactionList) return

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.TRANSFER_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT]

            financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementTransferService - Erro ao executar createTransitoryForReversedTransfer"])
    }

    private void createTransitoryForReversedTransferFee(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.REVERSAL,
                "transactionDate[ge]": startDate,
                "transactionDate[lt]": endDate,
                "reversedFinancialStatementType[exists]": FinancialStatementType.TRANSFER_REQUEST_FEE_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
                "financialStatementTypeList[notExists]": [FinancialStatementType.TRANSFER_REVERSAL_FEE_CUSTOMER_BALANCE_TRANSITORY_CREDIT],
                "customerId[ne]": grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id
            ]).list()

            if (!financialTransactionList) return

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.TRANSFER_REVERSAL_FEE_CUSTOMER_BALANCE_TRANSITORY_CREDIT]

            financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementTransferService.createTransitoryForReversedTransferFee - Erro ao criar as taxas de transferência canceladas"])
    }

    private void saveTransferCustomerExpenseFinancialStatements(List<FinancialTransaction> transferTransactionList, Bank bank, Date statementDate) {
        if (!transferTransactionList) return

        FinancialStatement transferExpense = financialStatementService.save(FinancialStatementType.CUSTOMER_TRANSFER_EXPENSE, statementDate, bank, transferTransactionList.value.sum())

        financialStatementItemService.saveInBatch(transferExpense, transferTransactionList)
    }

    private void saveTransferFeeFinancialStatements(List<FinancialTransaction> transferFeeTransactionList, Bank bank, Date statementDate) {
        if (!transferFeeTransactionList) return

        BigDecimal financialStatementValue = transferFeeTransactionList.value.sum()

        FinancialStatement transferFeeRevenue = financialStatementService.save(FinancialStatementType.TRANSFER_FEE_REVENUE, statementDate, bank, financialStatementValue)
        FinancialStatement transferFeeCustomerBalanceDebit = financialStatementService.save(FinancialStatementType.TRANSFER_FEE_CUSTOMER_BALANCE_DEBIT, statementDate, bank, financialStatementValue)

        financialStatementItemService.saveInBatch(transferFeeRevenue, transferFeeTransactionList)
        financialStatementItemService.saveInBatch(transferFeeCustomerBalanceDebit, transferFeeTransactionList)
    }
}
