package com.asaas.service.financialstatement

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.springframework.util.StopWatch

@Transactional
class FinancialStatementDirectPixCreditService {

    def financialStatementService
    def financialStatementItemService
    def sessionFactory

    public void createFinancialStatements(Date startDate, Date endDate) {
        if (!startDate || !endDate) throw new RuntimeException("As datas de início e de fim devem ser informadas")

        AsaasLogger.info("createPixFinancialStatements >> FinancialStatementDirectPixCreditService.createFinancialStatements() [início: ${new Date()}]")

        StopWatch stopWatch = new StopWatch("FinancialStatementDirectPixCreditService")

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createPixDirectStatements [início: ${new Date()}]")
            stopWatch.start("createPixDirectStatements")

            Date createPixDirectStatementsEndDate = CustomDateUtils.sumDays(endDate.clone() as Date, -1)
            for (Date date in startDate..createPixDirectStatementsEndDate) {
                createPixDirectStatements(date)
            }

            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createPixDirectStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.createPixDirectStatements() -> Erro ao executar createPixDirectStatements."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.pixDirectFeeWithoutRevenueStatements [início: ${new Date()}]")
            stopWatch.start("pixDirectFeeWithoutRevenueStatements")

            Date pixDirectFeeWithoutRevenueStatementsEndDate = CustomDateUtils.sumDays(endDate.clone() as Date, -1)
            for (Date date in startDate..pixDirectFeeWithoutRevenueStatementsEndDate) {
                pixDirectFeeWithoutRevenueStatements(date)
            }

            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.pixDirectFeeWithoutRevenueStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.pixDirectFeeWithoutRevenueStatements() -> Erro ao executar pixDirectFeeWithoutRevenueStatements."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createPixCreditFeeDiscountStatements [início: ${new Date()}]")
            stopWatch.start("createPixCreditFeeDiscountStatements")
            createPixCreditFeeDiscountStatements(startDate, endDate)
            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createPixCreditFeeDiscountStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.createPixCreditFeeDiscountStatements >> Falha ao gerar os lançamentos contábeis de crédito promocional de Pix."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createRefundedPixDirectStatements [início: ${new Date()}]")
            stopWatch.start("createRefundedPixDirectStatements")
            createRefundedPixDirectStatements(startDate, endDate)
            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createRefundedPixDirectStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.createRefundedPixDirectStatements() -> Erro ao executar createRefundedPixDirectStatements."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createCancelledPixDirectRefundStatements [início: ${new Date()}]")
            stopWatch.start("createCancelledPixDirectRefundStatements")
            createCancelledPixDirectRefundStatements(startDate, endDate)
            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createCancelledPixDirectRefundStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.createCancelledPixDirectRefundStatements() -> Erro ao executar createCancelledPixDirectRefundStatements."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createInternalPixTransactionCreditStatements [início: ${new Date()}]")
            stopWatch.start("createInternalPixTransactionCreditStatements")
            createInternalPixTransactionCreditStatements(startDate, endDate)
            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createInternalPixTransactionCreditStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.createInternalPixTransactionCreditStatements >> Falha ao gerar os lançamentos contábeis de crédito de Pix entre cadastros."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createInternalPixTransactionFeeStatements [início: ${new Date()}]")
            stopWatch.start("createInternalPixTransactionFeeStatements")
            createInternalPixTransactionFeeStatements(startDate, endDate)
            stopWatch.stop()
            AsaasLogger.info("FinancialStatementDirectPixCreditService.createInternalPixTransactionFeeStatements [conclusão: ${new Date()}]")
        }, [logErrorMessage: "FinancialStatementDirectPixCreditService.createInternalPixTransactionFeeStatements >> Falha ao gerar os lançamentos contábeis de taxa de criação de cobranças com o Pix entre cadastros."])

        AsaasLogger.info(stopWatch.prettyPrint())
        AsaasLogger.info("createPixFinancialStatements >> FinancialStatementDirectPixCreditService.createFinancialStatements() [conclusão: ${new Date()}]")
    }

    private void createInternalPixTransactionCreditStatements(Date startDate, Date endDate) {
        Map commonParams = ["transactionDate[ge]": startDate,
                            "transactionDate[lt]": endDate,
                            "financialStatementTypeList[notExists]": [FinancialStatementType.INTERNAL_PIX_TRANSACTION_CREDIT]]

        Map withPaymentQueryParams = [paymentBillingType: BillingType.PIX,
                                      transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
                                      "internalPixCreditTransaction[exists]": true
                                     ] + commonParams
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(withPaymentQueryParams).list(readonly: true)

        Map withoutPaymentQueryParams = [hasInternalPixCreditTransactionWithoutPayment: true,
                                         transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT
                                        ] + commonParams
        financialTransactionList.addAll(FinancialTransaction.query(withoutPaymentQueryParams).list(readonly: true))
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.INTERNAL_PIX_TRANSACTION_CREDIT]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfoMap], bank)
    }

    private void createInternalPixTransactionFeeStatements(Date startDate, Date endDate) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PAYMENT_FEE,
            paymentBillingTypeList: [BillingType.PIX],
            "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_DIRECT_PAYMENT_FEE_DEBIT, FinancialStatementType.INTERNAL_PIX_PAYMENT_FEE_DEBIT],
            "internalPixCreditTransaction[exists]": true,
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate
        ]).list()
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.INTERNAL_PIX_PAYMENT_FEE_DEBIT],
            [financialStatementType: FinancialStatementType.INTERNAL_PIX_PAYMENT_FEE_REVENUE]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createPixDirectStatements(Date date) {
        if (date > CustomDateUtils.fromString("28/05/2024")) return

        Map commonParams = [
            "column": 'id',
            "transactionDate": date,
            "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE]
        ]

        Map withPaymentQueryParams = [
            paymentBillingType: BillingType.PIX,
            transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
            "hasCreditPixTransactionWithAsaasKey[notExists]": true,
            "internalPixCreditTransaction[notExists]": true,
            "cashInRiskAnalysisRequestReason[notExists]": true
        ] + commonParams
        List<Long> financialTransactionIdList = FinancialTransaction.query(withPaymentQueryParams).list(readOnly: true)

        Map withoutPaymentQueryParams = [transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT,
                                         hasExternalPixCreditTransactionWithoutPayment: true
        ] + commonParams
        financialTransactionIdList.addAll(FinancialTransaction.query(withoutPaymentQueryParams).list(readOnly: true))
        if (!financialTransactionIdList) return

        Date transactionDate = FinancialTransaction.query([column: 'transactionDate', id: financialTransactionIdList.first()]).get()

        Long financialStatementId = financialStatementService.saveWithNewTransaction(FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE, transactionDate, null, null)

        financialStatementItemService.saveItemsWithThreads(financialTransactionIdList, financialStatementId)
    }

    private void createPixCreditFeeDiscountStatements(Date startDate, Date endDate) {
        Map commonParams = ["transactionDate[ge]": startDate,
                            "transactionDate[lt]": endDate,
                            transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                            "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE, FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]]

        Map withPaymentQueryParams = [paymentBillingType: BillingType.PIX
                                     ] + commonParams
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([withPaymentQueryParams]).list(readonly: true)

        Map withoutPaymentQueryParams = [chargedFeeType: ChargedFeeType.PIX_CREDIT
                                        ] + commonParams
        financialTransactionList.addAll(FinancialTransaction.query([withoutPaymentQueryParams]).list(readonly: true))
        if (!financialTransactionList) return

        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE],
            [financialStatementType: FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createRefundedPixDirectStatements(Date startDate, Date endDate) {
        Map withPaymentQueryParams = ["transactionDate[ge]": startDate,
                                      "transactionDate[lt]": endDate,
                                      "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_REVERSAL],
                                      paymentBillingType: BillingType.PIX,
                                      transactionType: FinancialTransactionType.PAYMENT_REVERSAL,
                                      "hasCreditPixTransactionWithAsaasKey[notExists]": true,
                                      "internalPixCreditTransaction[notExists]": true]
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(withPaymentQueryParams).list(readOnly: true)

        List<Long> financialTransactionIdList = getFinancialTransactionIdListByExternalRefundedPixWithoutPayment(startDate, endDate)
        if (financialTransactionIdList) financialTransactionList.addAll(FinancialTransaction.query("id[in]": financialTransactionIdList).list(readOnly: true))

        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = []
        financialStatementInfoList.add([financialStatementType: FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_REVERSAL])

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private List<Long> getFinancialTransactionIdListByExternalRefundedPixWithoutPayment(Date startDate, Date endDate) {
        StringBuilder sql = new StringBuilder()
        sql.append("select ft.id from financial_transaction ft")
        sql.append(" join financial_transaction_pix_transaction ftpt ON ft.id=ftpt.financial_transaction_id")
        sql.append(" join pix_transaction pt ON ftpt.pix_transaction_id=pt.id")
        sql.append(" join pix_transaction_refund ptr ON pt.id=ptr.transaction_id")
        sql.append(" join pix_transaction ptOrigin ON ptr.refunded_transaction_id=ptOrigin.id")
        sql.append(" join pix_transaction_external_account ptea ON ptOrigin.id=ptea.pix_transaction_id")
        sql.append("   where ft.deleted = false")
        sql.append("     and ft.transaction_date >= :startDate")
        sql.append("     and ft.transaction_date <= :endDate")
        sql.append("     and ft.transaction_type = :transactionType")
        sql.append("     and pt.type = :pixTransactionType")
        sql.append("     and ptOrigin.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("     and ptOrigin.payment_id is null")
        sql.append("     and ptea.ispb <> :asaasIspb")
        sql.append("     and not exists")
        sql.append("        (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("         join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("         where fsi.financial_transaction_id=ft.id")
        sql.append("         and fs.deleted = false")
        sql.append("         and fs.financial_statement_type = :financialStatementType)")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("startDate", CustomDateUtils.fromDate(startDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("endDate", CustomDateUtils.fromDate(endDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("transactionType", FinancialTransactionType.PIX_TRANSACTION_CREDIT_REFUND.toString())
        query.setString("pixTransactionType", PixTransactionType.CREDIT_REFUND.toString())
        query.setString("receivedWithAsaasQrCode", 'false')
        query.setString("financialStatementType", FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_REVERSAL.toString())
        query.setLong("asaasIspb", Long.valueOf(AsaasApplicationHolder.getConfig().asaas.ispb))

        List<Long> financialTransactionIdList = query.list().collect( { Utils.toLong(it) } )
        return financialTransactionIdList
    }

    private void createCancelledPixDirectRefundStatements(Date startDate, Date endDate) {
        Map withPaymentQueryParams = ["transactionDate[ge]": startDate,
                                      "transactionDate[lt]": endDate,
                                      paymentBillingType: BillingType.PIX,
                                      transactionType: FinancialTransactionType.PAYMENT_REFUND_CANCELLED,
                                      "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_REVERSAL_CANCELLED],
                                      "hasCreditPixTransactionWithAsaasKey[notExists]": true,
                                      "internalPixCreditTransaction[notExists]": true]
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(withPaymentQueryParams).list(readOnly: true)

        List<Long> financialTransactionIdList = getFinancialTransactionIdListByCancelledExternalPixRefundWithoutPayment(startDate, endDate)
        if (financialTransactionIdList) financialTransactionList.addAll(FinancialTransaction.query("id[in]": financialTransactionIdList).list(readOnly: true))

        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_REVERSAL_CANCELLED]
        ]
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private List<Long> getFinancialTransactionIdListByCancelledExternalPixRefundWithoutPayment(Date startDate, Date endDate) {
        StringBuilder sql = new StringBuilder()
        sql.append("select ft.id from financial_transaction ft")
        sql.append(" join financial_transaction_pix_transaction ftpt ON ft.reversed_transaction_id=ftpt.financial_transaction_id")
        sql.append(" join pix_transaction pt ON ftpt.pix_transaction_id=pt.id")
        sql.append(" join pix_transaction_refund ptr ON pt.id=ptr.transaction_id")
        sql.append(" join pix_transaction pt_origin ON ptr.refunded_transaction_id=pt_origin.id")
        sql.append(" join pix_transaction_external_account ptea ON pt_origin.id=ptea.pix_transaction_id")
        sql.append("   where ft.deleted = false")
        sql.append("     and ft.transaction_date >= :startDate")
        sql.append("     and ft.transaction_date <= :endDate")
        sql.append("     and ft.transaction_type = :transactionType")
        sql.append("     and pt.type = :pixTransactionType")
        sql.append("     and pt_origin.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("     and pt_origin.payment_id is null")
        sql.append("     and ptea.ispb <> :asaasIspb")
        sql.append("     and not exists")
        sql.append("        (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("         join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("         where fsi.financial_transaction_id=ft.id")
        sql.append("         and fs.deleted = false")
        sql.append("         and fs.financial_statement_type = :financialStatementType)")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("startDate", CustomDateUtils.fromDate(startDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("endDate", CustomDateUtils.fromDate(endDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("transactionType", FinancialTransactionType.PIX_TRANSACTION_CREDIT_REFUND_CANCELLATION.toString())
        query.setString("pixTransactionType", PixTransactionType.CREDIT_REFUND.toString())
        query.setBoolean("receivedWithAsaasQrCode", false)
        query.setString("financialStatementType", FinancialStatementType.PIX_DIRECT_CUSTOMER_REVENUE_REVERSAL_CANCELLED.toString())
        query.setLong("asaasIspb", Long.valueOf(AsaasApplicationHolder.getConfig().asaas.ispb))

        List<Long> financialTransactionIdList = query.list().collect( { Utils.toLong(it) } )
        return financialTransactionIdList
    }

    private void pixDirectFeeWithoutRevenueStatements(Date date) {
        Map commonParams = ["column": 'id',
                            "transactionDate": date,
                            "internalPixCreditTransaction[notExists]": true,
                            "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_FEE_REVENUE,
                                                                      FinancialStatementType.PAYMENT_FEE_DEBIT,
                                                                      FinancialStatementType.PIX_DIRECT_PAYMENT_FEE_REVENUE,
                                                                      FinancialStatementType.PIX_DIRECT_PAYMENT_FEE_DEBIT]
                           ]

        Map withPaymentQueryParams = [transactionType: FinancialTransactionType.PAYMENT_FEE,
                                      paymentBillingType: BillingType.PIX,
                                      "paymentFeeStatement[notExists]": true,
                                      "hasCreditPixTransactionWithAsaasKey[notExists]": true
                                     ] + commonParams
        List<Long> financialTransactionIdList = FinancialTransaction.query(withPaymentQueryParams).list(readOnly: true)

        Map withoutPaymentQueryParams = [transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT_FEE
                                        ] + commonParams
        financialTransactionIdList.addAll(FinancialTransaction.query(withoutPaymentQueryParams).list(readOnly: true))
        if (!financialTransactionIdList) return

        Date transactionDate = FinancialTransaction.query([column: 'transactionDate', id: financialTransactionIdList.first()]).get()

        Long financialStatementRevenueId = financialStatementService.saveWithNewTransaction(FinancialStatementType.PIX_DIRECT_PAYMENT_FEE_REVENUE, transactionDate, null, null)
        financialStatementItemService.saveItemsWithThreads(financialTransactionIdList, financialStatementRevenueId)

        Long financialStatementDebitId = financialStatementService.saveWithNewTransaction(FinancialStatementType.PIX_DIRECT_PAYMENT_FEE_DEBIT, transactionDate, null, null)
        financialStatementItemService.saveItemsWithThreads(financialTransactionIdList, financialStatementDebitId)
    }
}
