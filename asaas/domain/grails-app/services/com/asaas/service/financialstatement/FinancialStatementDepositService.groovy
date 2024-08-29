package com.asaas.service.financialstatement

import com.asaas.bankdepositstatus.BankDepositStatus
import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.holiday.Holiday
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementDepositService {

    def financialStatementPaymentService
    def financialStatementService
    def financialStatementItemService

    public void createFinancialStatementsForDeposit() {
        Date startDate = CustomDateUtils.sumDays(new Date(), -1)

        AsaasLogger.info("FinancialStatementDepositService >> createFinancialStatementsForNotIdentifiedDeposit")
        createFinancialStatementsForNotIdentifiedDeposit(startDate, new Date().clearTime())
        AsaasLogger.info("FinancialStatementDepositService >> createFinancialStatementsForIdentifiedDeposit")
        createFinancialStatementsForIdentifiedDeposit(startDate, new Date())
    }

    public void createPaymentFeeRevenueStatementsForTransferAndDeposit(Date startDate, Date endDate) {
        AsaasLogger.info("FinancialStatementDepositService >> createPaymentFeeRevenueStatementsForTransferAndDeposit")

        List<Long> bankIdList = Bank.query([column: "id"]).list()
        for (Long bankId : bankIdList) {
            createTransferFee(bankId, startDate, endDate)

            createDepositFee(bankId, startDate, endDate)

            createTransferFeeDiscount(bankId, startDate, endDate)
        }
    }

    private void createTransferFee(Long bankId, Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.paymentBankId = bankId
            search."transactionDate[ge]" = startDate
            search."transactionDate[lt]" = endDate
            search.transactionType = FinancialTransactionType.PAYMENT_FEE
            search."paymentFeeStatement[notExists]" = true
            search."financialStatementTypeList[notExists]" = [FinancialStatementType.TRANSFER_PAYMENT_FEE_CREDIT]
            search.paymentBillingType = BillingType.TRANSFER

            List<FinancialTransaction> transferFeeTransactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!transferFeeTransactionList) return

            Map<Date, List<FinancialTransaction>> transferFeeTransactionListGroupedByDate = transferFeeTransactionList.groupBy { it.transactionDate }
            for (Date transactionDate : transferFeeTransactionListGroupedByDate.keySet()) {
                List<FinancialTransaction> transferFeeTransactions = transferFeeTransactionListGroupedByDate[transactionDate]

                List<Map> financialStatementTypeInfoList = [
                    [financialStatementType: FinancialStatementType.TRANSFER_PAYMENT_FEE_CREDIT],
                    [financialStatementType: FinancialStatementType.TRANSFER_PAYMENT_FEE_DEBIT]
                ]

                Bank bank = Bank.get(bankId)
                financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transferFeeTransactions, financialStatementTypeInfoList, bank)
            }
        }, [logErrorMessage: "FinancialStatementDepositService.createTransferFee >> Erro ao criar os lançamentos de transferência"])
    }

    private void createDepositFee(Long bankId, Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.paymentBankId = bankId
            search."transactionDate[ge]" = startDate
            search."transactionDate[lt]" = endDate
            search.transactionType = FinancialTransactionType.PAYMENT_FEE
            search."paymentFeeStatement[notExists]" = true
            search."financialStatementTypeList[notExists]" = [FinancialStatementType.DEPOSIT_PAYMENT_FEE_CREDIT]
            search.paymentBillingType = BillingType.DEPOSIT

            List<FinancialTransaction> depositFeeTransactionList = FinancialTransaction.query(search).list(readOnly: true)

            Map<Date, List<FinancialTransaction>> depositFeeTransactionListGroupedByDate = depositFeeTransactionList.groupBy { it.transactionDate }
            for (Date transactionDate : depositFeeTransactionListGroupedByDate.keySet()) {
                List<FinancialTransaction> debitCardFeeTransactions = depositFeeTransactionListGroupedByDate[transactionDate]

                List<Map> financialStatementTypeInfoList = [
                    [financialStatementType: FinancialStatementType.DEPOSIT_PAYMENT_FEE_CREDIT],
                    [financialStatementType: FinancialStatementType.DEPOSIT_PAYMENT_FEE_DEBIT]
                ]

                Bank bank = Bank.get(bankId)
                financialStatementService.groupFinancialTransactionsAndSave("transactionDate", debitCardFeeTransactions, financialStatementTypeInfoList, bank)
            }
        }, [logErrorMessage: "FinancialStatementDepositService.createDepositFee >> Erro ao criar os lançamentos de depósito"])
    }

    private void createTransferFeeDiscount(Long bankId, Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.paymentBankId = bankId
            search."transactionDate[ge]" = startDate
            search."transactionDate[lt]" = endDate
            search.transactionType = FinancialTransactionType.PROMOTIONAL_CODE_CREDIT
            search.paymentBillingTypeList = [BillingType.DEPOSIT, BillingType.TRANSFER]
            search."paymentFeeDiscountStatement[notExists]" = true
            search."financialStatementTypeList[notExists]" = [FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE, FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
            search."payment[isNotNull]" = true

            List<FinancialTransaction> transferFeeDiscountTransactionList = FinancialTransaction.query(search).list(readOnly: true)

            Map transferFeeDiscountTransactionListGroupedByDate = transferFeeDiscountTransactionList.groupBy { it.transactionDate }
            transferFeeDiscountTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> transferFeeDiscountTransactions ->
                financialStatementPaymentService.saveFeeDiscount(transferFeeDiscountTransactions, Bank.get(bankId))
            }
        }, [logErrorMessage: "FinancialStatementDepositService.createTransferFeeDiscount >> Erro ao criar os lançamentos de desconto na taxa de transferência"])
    }

    private void createFinancialStatementsForNotIdentifiedDeposit(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<BankDeposit> bankDepositList = BankDeposit.query(['documentDate[ge]': startDate, 'documentDate[lt]': endDate, 'status[in]': [BankDepositStatus.AWAITING_MANUAL_CONCILIATION, BankDepositStatus.CONCILIATED], 'notIdentifiedDepositStatement[notExists]': true]).list()

            bankDepositList.removeAll{ it.conciliationDate?.clone()?.clearTime() == it.documentDate.clone().clearTime() }

            Map bankDepositListGroupedByBank = bankDepositList.groupBy { it.bank }

            bankDepositListGroupedByBank.each { Bank bank, List<BankDeposit> bankDepositListByBank ->
                Map bankDepositListGroupedByDate = bankDepositListByBank.groupBy { it.documentDate.clone().clearTime() }

                bankDepositListGroupedByDate.each { Date documentDate, List<BankDeposit> bankDepositListByDate ->
                    saveNotIdentifiedDepositFinancialStatements(bankDepositListByDate, bank)
                }
            }
        }, [logErrorMessage: "FinancialStatementDepositService - Erro ao executar createFinancialStatementsForNotIdentifiedDeposit"])
    }

    private void saveNotIdentifiedDepositFinancialStatements(List<BankDeposit> bankDepositList, Bank bank) {
        if (!bankDepositList) return

        BigDecimal financialStatementValue = bankDepositList.value.sum()

        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.NOT_IDENTIFIED_DEPOSIT_CREDIT, calculateStatementDateForDepositNotIdentified(bankDepositList.first().documentDate), bank, financialStatementValue)

        for (BankDeposit bankDeposit : bankDepositList) {
            financialStatementItemService.save(financialStatement, bankDeposit)
        }
    }

    private Date calculateStatementDateForDepositNotIdentified(Date paymentDate) {
        Date dateNow = new Date().clearTime()

        if (dateNow == CustomDateUtils.getFirstBusinessDayOfCurrentMonth().getTime().clearTime() && CustomDateUtils.truncate(paymentDate, Calendar.MONTH) == CustomDateUtils.truncate(CustomDateUtils.addMonths(dateNow, -1), Calendar.MONTH)) return paymentDate

        if (CustomDateUtils.truncate(dateNow, Calendar.MONTH) == CustomDateUtils.truncate(paymentDate, Calendar.MONTH)) return paymentDate

        if (dateNow == CustomDateUtils.getFirstDayOfCurrentMonth().clearTime() && Holiday.isHoliday(dateNow)) return paymentDate

        return dateNow
    }

    private void createFinancialStatementsForIdentifiedDeposit(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<BankDeposit> bankDepositList = BankDeposit.query(['conciliationDate[ge]': startDate, 'conciliationDate[lt]': endDate, status: BankDepositStatus.CONCILIATED, 'notIdentifiedDepositStatement[exists]': true, 'identifiedDepositStatement[notExists]': true, 'expropriatedBalanceStatement[notExists]': true]).list()

            Map bankDepositListGroupedByBank = bankDepositList.groupBy { it.bank }

            bankDepositListGroupedByBank.each { Bank bank, List<BankDeposit> bankDepositListByBank ->
                Map bankDepositListGroupedByDate = bankDepositListByBank.groupBy { it.conciliationDate.clone().clearTime() }

                bankDepositListGroupedByDate.each { Date conciliationDate, List<BankDeposit> bankDepositListByDate ->
                    saveIdentifiedDepositFinancialStatements(bankDepositListByDate, bank, conciliationDate)
                }
            }
        }, [logErrorMessage: "FinancialStatementDepositService - Erro ao executar createFinancialStatementsForIdentifiedDeposit"])
    }

    private void saveIdentifiedDepositFinancialStatements(List<BankDeposit> bankDepositList, Bank bank, Date statementDate) {
        if (!bankDepositList) return

        BigDecimal financialStatementValue = bankDepositList.value.sum()

        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.IDENTIFIED_DEPOSIT_DEBIT, statementDate, bank, financialStatementValue)

        for (BankDeposit bankDeposit : bankDepositList) {
            financialStatementItemService.save(financialStatement, bankDeposit)
        }
    }
}
