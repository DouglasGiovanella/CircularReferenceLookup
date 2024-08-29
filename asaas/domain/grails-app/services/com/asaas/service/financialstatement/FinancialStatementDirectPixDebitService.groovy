package com.asaas.service.financialstatement

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.springframework.util.StopWatch

@Transactional
class FinancialStatementDirectPixDebitService {

    def financialStatementService
    def financialStatementItemService

    public void createFinancialStatements(Date startDate, Date endDate) {
        if (!startDate || !endDate) throw new RuntimeException("As datas de início e de fim devem ser informadas")

        AsaasLogger.info("createPixFinancialStatements >> FinancialStatementDirectPixDebitService.createFinancialStatements() [início: ${new Date()}]")

        StopWatch stopWatch = new StopWatch("FinancialStatementDirectPixDebitService")

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForPixTransactionDebit")
            createForPixTransactionDebit(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createForPixTransactionDebit() -> Erro ao executar createForPixTransactionDebit."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createTransitoryForConfirmedPixTransactionDebit")
            createTransitoryForConfirmedPixTransactionDebit(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createTransitoryForConfirmedPixTransactionDebit() -> Erro ao executar."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForPixTransactionDebitFee")
            createForPixTransactionDebitFee(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createForPixTransactionDebitFee() -> Erro ao executar createForPixTransactionDebitFee."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createTransitoryForRequestedPixTransactionDebit")
            createTransitoryForRequestedPixTransactionDebit(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createTransitoryForRequestedPixTransactionDebit() -> Erro ao executar."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createTransitoryForPixTransactionCancelledDebitRefund")
            createTransitoryForPixTransactionCancelledDebitRefund(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createTransitoryForPixTransactionCancelledDebitRefund() -> Erro ao executar."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForPixTransactionDebitRefund")
            createForPixTransactionDebitRefund(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createForPixTransactionDebitRefund() -> Erro ao executar createForPixTransactionDebitRefund."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForPixTransactionCancelledDebitRefund")
            createForPixTransactionCancelledDebitRefund(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createForPixTransactionCancelledDebitRefund() -> Erro ao executar createForPixTransactionCancelledDebitRefund."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForPixTransactionCancelledFeeRefund")
            createForPixTransactionCancelledFeeRefund(startDate, endDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementDirectPixDebitService.createForPixTransactionCancelledFeeRefund() -> Erro ao executar createForPixTransactionCancelledFeeRefund."])

        AsaasLogger.info(stopWatch.prettyPrint())
        AsaasLogger.info("createPixFinancialStatements >> FinancialStatementDirectPixDebitService.createFinancialStatements() [conclusão: ${new Date()}]")
    }

    private void createForPixTransactionDebit(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT
        queryParameters."pixTransactionEffectiveDate[ge]" = startDate
        queryParameters."pixTransactionEffectiveDate[lt]" = endDate
        queryParameters."pixTransactionLastUpdated[ge]" = startDate
        queryParameters."pixTransactionType" = PixTransactionType.DEBIT
        queryParameters."pixTransactionStatus" = PixTransactionStatus.DONE
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.INTERNAL_PIX_TRANSACTION_DEBIT]
        List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!transactionList) return

        List<FinancialTransaction> internalTransactionList = transactionList.findAll { it.financialTransactionPixTransaction.pixTransaction.isInternalTransaction() }
        createInternalPixTransactionDebitStatements(internalTransactionList)

        transactionList.removeAll(internalTransactionList)
        if (!transactionList) return

        groupByEffectiveDateAndSave(transactionList, FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_DEBIT, null)
    }

    private void createTransitoryForConfirmedPixTransactionDebit(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT
        queryParameters."pixTransactionEffectiveDate[ge]" = startDate
        queryParameters."pixTransactionEffectiveDate[lt]" = endDate
        queryParameters."pixTransactionLastUpdated[ge]" = startDate
        queryParameters."pixTransactionType" = PixTransactionType.DEBIT
        queryParameters."pixTransactionStatus" = PixTransactionStatus.DONE
        queryParameters."financialStatementTypeList[exists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_CONFIRMED_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!financialTransactionList) return

        groupByEffectiveDateAndSave(financialTransactionList, FinancialStatementType.PIX_TRANSACTION_DEBIT_CONFIRMED_CUSTOMER_BALANCE_TRANSITORY_CREDIT, null)
    }

    private void createForPixTransactionDebitFee(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT_FEE
        queryParameters."transactionDate[ge]" = startDate
        queryParameters."transactionDate[lt]" = endDate
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_FEE_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.PIX_TRANSACTION_DEBIT_FEE_ASAAS_BALANCE_CREDIT]
        List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!transactionList) return

        List<Map> financialStatementInfoList = []
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_TRANSACTION_DEBIT_FEE_ASAAS_BALANCE_CREDIT])
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_TRANSACTION_DEBIT_FEE_CUSTOMER_BALANCE_DEBIT])

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoList, null)
    }

    private void createTransitoryForRequestedPixTransactionDebit(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT
        queryParameters."transactionDate[ge]" = startDate
        queryParameters."transactionDate[lt]" = endDate
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_TRANSITORY_DEBIT, FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_DEBIT]
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.transactionDateDifferentFromPixTransactionEffectiveDate(queryParameters).list(readOnly: true)
        if (!financialTransactionList) return

        Map financialStatementInfo = [financialStatementType: FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, [financialStatementInfo], null)
    }

    private void createTransitoryForPixTransactionCancelledDebitRefund(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT_REFUND
        queryParameters."transactionDate[ge]" = startDate
        queryParameters."transactionDate[lt]" = endDate
        queryParameters."reversedFinancialStatementType[exists]" = FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_TRANSITORY_DEBIT
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_CANCELLED_OR_REFUSED_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
        queryParameters.pixTransactionType = PixTransactionType.DEBIT
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!financialTransactionList) return

        Map financialStatementInfo = [financialStatementType: FinancialStatementType.PIX_TRANSACTION_DEBIT_CANCELLED_OR_REFUSED_CUSTOMER_BALANCE_TRANSITORY_CREDIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, [financialStatementInfo], null)
    }

    private void createForPixTransactionDebitRefund(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT_REFUND
        queryParameters."transactionDate[ge]" = startDate
        queryParameters."transactionDate[lt]" = endDate
        queryParameters."reversedFinancialStatementType[exists]" = FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_DEBIT
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_DEBIT_REFUND_CUSTOMER_BALANCE_CREDIT]
        queryParameters."pixTransactionType" = PixTransactionType.DEBIT_REFUND
        List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!transactionList) return

        transactionList = transactionList.findAll { !it.financialTransactionPixTransaction.pixTransaction.isInternalTransaction() }
        if (!transactionList) return

        List<Map> financialStatementInfoList = []
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_TRANSACTION_DEBIT_REFUND_CUSTOMER_BALANCE_CREDIT])

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoList, null)
    }

    @Deprecated
    private void createForPixTransactionCancelledDebitRefund(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.PIX_TRANSACTION_DEBIT_REFUND
        queryParameters."transactionDate[ge]" = startDate
        queryParameters."transactionDate[lt]" = endDate
        queryParameters."reversedFinancialStatementType[exists]" = FinancialStatementType.PIX_TRANSACTION_DEBIT_CUSTOMER_BALANCE_DEBIT
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_CANCELLED_DEBIT_REFUND_CUSTOMER_BALANCE_CREDIT]
        queryParameters."pixTransactionType" = PixTransactionType.DEBIT
        List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!transactionList) return

        transactionList = transactionList.findAll { !it.financialTransactionPixTransaction.pixTransaction.isInternalTransaction() }
        if (!transactionList) return

        List<Map> financialStatementInfoList = []
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_TRANSACTION_CANCELLED_DEBIT_REFUND_CUSTOMER_BALANCE_CREDIT])

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoList, null)
    }

    private void createForPixTransactionCancelledFeeRefund(Date startDate, Date endDate) {
        Map queryParameters = [:]
        queryParameters.transactionType = FinancialTransactionType.CHARGED_FEE_REFUND
        queryParameters.chargedFeeType = ChargedFeeType.PIX_DEBIT
        queryParameters."transactionDate[ge]" = startDate
        queryParameters."transactionDate[lt]" = endDate
        queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PIX_TRANSACTION_CANCELLED_DEBIT_FEE_REFUND_ASAAS_BALANCE_DEBIT, FinancialStatementType.PIX_TRANSACTION_CANCELLED_DEBIT_FEE_REFUND_CUSTOMER_BALANCE_CREDIT]
        List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!transactionList) return

        List<Map> financialStatementInfoList = []
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_TRANSACTION_CANCELLED_DEBIT_FEE_REFUND_ASAAS_BALANCE_DEBIT])
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_TRANSACTION_CANCELLED_DEBIT_FEE_REFUND_CUSTOMER_BALANCE_CREDIT])

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoList, null)
    }

    private void createInternalPixTransactionDebitStatements(List<FinancialTransaction> transactionList) {
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())

        groupByEffectiveDateAndSave(transactionList, FinancialStatementType.INTERNAL_PIX_TRANSACTION_DEBIT, bank)
    }

    private void groupByEffectiveDateAndSave(List<FinancialTransaction> transactionList, FinancialStatementType financialStatementType, Bank bank) {
        Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = transactionList.groupBy { it.financialTransactionPixTransaction.pixTransaction.effectiveDate.clone().clearTime() }

        financialTransactionListGroupedByDate.each { Date effectiveDate, List<FinancialTransaction> financialTransactionListByDate ->
            FinancialStatement financialStatement = financialStatementService.save(financialStatementType, effectiveDate, bank, financialTransactionListByDate.value.sum())
            financialStatementItemService.saveInBatch(financialStatement, financialTransactionListByDate)
        }
    }
}
