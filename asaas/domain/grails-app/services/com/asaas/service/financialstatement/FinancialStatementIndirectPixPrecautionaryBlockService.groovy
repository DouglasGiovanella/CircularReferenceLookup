package com.asaas.service.financialstatement

import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.cashinriskanalysis.CashInRiskAnalysisReason
import com.asaas.domain.bank.Bank
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.springframework.util.StopWatch

@Transactional
class FinancialStatementIndirectPixPrecautionaryBlockService {

    def financialStatementService
    def sessionFactory

    public void createFinancialStatements(Date startDate, Date endDate) {
        if (!startDate || !endDate) throw new RuntimeException("As datas de início e de fim devem ser informadas")

        AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createFinancialStatements >> [início: ${new Date()}]")

        StopWatch stopWatch = new StopWatch("FinancialStatementIndirectPixPrecautionaryBlockService")

        for (Date date in startDate..endDate) {
            Utils.withNewTransactionAndRollbackOnError({
                AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskConfirmed:${date} >> [início: ${new Date()}]")
                stopWatch.start("createForPixCashInRiskConfirmed:${date}")
                createForPixCashInRiskConfirmed(date)
                stopWatch.stop()
                AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskConfirmed:${date} >> [conclusão: ${new Date()}]")
            }, [logErrorMessage: "FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskConfirmed >> Erro ao executar da data ${date}."])

            Utils.flushAndClearSession()

            Utils.withNewTransactionAndRollbackOnError({
                AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskRefunded:${date} >> [início: ${new Date()}]")
                stopWatch.start("createForPixCashInRiskRefunded:${date}")
                createForPixCashInRiskRefunded(date)
                stopWatch.stop()
                AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskRefunded:${date} >> [conclusão: ${new Date()}]")
            }, [logErrorMessage: "FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskRefunded >> Erro ao executar da data ${date}."])

            Utils.flushAndClearSession()

            Utils.withNewTransactionAndRollbackOnError({
                AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskReceived:${date} >> [início: ${new Date()}]")
                stopWatch.start("createForPixCashInRiskReceived:${date}")
                createForPixCashInRiskReceived(date)
                stopWatch.stop()
                AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskReceived:${date} >> [conclusão: ${new Date()}]")
            }, [logErrorMessage: "FinancialStatementIndirectPixPrecautionaryBlockService.createForPixCashInRiskReceived >> Erro ao executar da data ${date}."])

            Utils.flushAndClearSession()

            Utils.withNewTransactionAndRollbackOnError({
                AsaasLogger.info("FinancialStatementDirectPixPrecautionaryBlockService.createForRefundedByUserBeforeCashInRiskAnalysis:${date} >> [início: ${new Date()}]")
                stopWatch.start("createForRefundedByUserBeforeCashInRiskAnalysis:${date}")
                createForRefundedByUserBeforeCashInRiskAnalysis(date)
                stopWatch.stop()
                AsaasLogger.info("FinancialStatementDirectPixPrecautionaryBlockService.createForRefundedByUserBeforeCashInRiskAnalysis:${date} >> [conclusão: ${new Date()}]")
            }, [logErrorMessage: "FinancialStatementDirectPixPrecautionaryBlockService.createForRefundedByUserBeforeCashInRiskAnalysis >> Erro ao executar da data ${date}."])

            Utils.flushAndClearSession()

            Utils.withNewTransactionAndRollbackOnError({
                AsaasLogger.info("FinancialStatementDirectPixPrecautionaryBlockService.createForSettledAfterFailedRefundByAnalysis:${date} >> [início: ${new Date()}]")
                stopWatch.start("createForSettledAfterFailedRefundByAnalysis:${date}")
                createForReceivedAfterFailedRefundByAnalysis(date)
                stopWatch.stop()
                AsaasLogger.info("FinancialStatementDirectPixPrecautionaryBlockService.createForSettledAfterFailedRefundByAnalysis:${date} >> [conclusão: ${new Date()}]")
            }, [logErrorMessage: "FinancialStatementDirectPixPrecautionaryBlockService.createForSettledAfterFailedRefundByAnalysis >> Erro ao executar da data ${date}."])

            Utils.flushAndClearSession()
        }

        AsaasLogger.info(stopWatch.prettyPrint())
        AsaasLogger.info("FinancialStatementIndirectPixPrecautionaryBlockService.createFinancialStatements >> [conclusão: ${new Date()}]")
    }

    private void createForPixCashInRiskConfirmed(Date date) {
        String startDate = CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String endDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, 1), CustomDateUtils.DATABASE_DATETIME_FORMAT)

        StringBuilder sql = new StringBuilder()
        sql.append("select p.id, pt.value from cash_in_risk_analysis_request_reason cirarr")
        sql.append("    inner join pix_transaction pt on cirarr.pix_transaction_id = pt.id")
        sql.append("    inner join payment p on pt.payment_id = p.id")
        sql.append("    where cirarr.cash_in_risk_analysis_reason = :cashInRiskAnalysisReason")
        sql.append("        and cirarr.date_created >= :cirarrStartDateCreated")
        sql.append("        and cirarr.date_created < :cirarrEndDateCreated")
        sql.append("        and cirarr.deleted = :cirarrDeleted")
        sql.append("        and pt.type = :pixTransactionType")
        sql.append("        and pt.status in (:pixTransactionStatusList)")
        sql.append("        and pt.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("        and p.confirmed_date = :confirmedDate")
        sql.append("        and not exists")
        sql.append("            (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("                join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("                where fsi.payment_id=p.id")
        sql.append("                    and fs.deleted = :fsDeleted")
        sql.append("                    and fs.financial_statement_type in (:financialStatementTypeList))")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("cashInRiskAnalysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("cirarrStartDateCreated", startDate)
        query.setString("cirarrEndDateCreated", endDate)
        query.setBoolean("cirarrDeleted", false)
        query.setString("pixTransactionType", PixTransactionType.CREDIT.toString())
        query.setParameterList("pixTransactionStatusList", [PixTransactionStatus.DONE.toString(), PixTransactionStatus.AWAITING_CASH_IN_RISK_ANALYSIS_REQUEST.toString()])
        query.setBoolean("receivedWithAsaasQrCode", true)
        query.setString("confirmedDate", CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setBoolean("fsDeleted", false)
        query.setParameterList("financialStatementTypeList", [FinancialStatementType.PIX_INDIRECT_CASH_IN_RISK_CUSTOMER_REVENUE.toString(), FinancialStatementType.PIX_CASH_IN_RISK_TRANSITORY_DEBIT.toString()])

        List<Map> paymentInfoList = query.list().collect { [id: Utils.toLong(it[0]), value: Utils.toBigDecimal(it[1])] }

        if (!paymentInfoList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementCustomerRevenueInfoMap = [
            financialStatementType: FinancialStatementType.PIX_INDIRECT_CASH_IN_RISK_CUSTOMER_REVENUE,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementCustomerRevenueInfoMap], date, bank)

        Map financialStatementTransitoryInfoMap = [
            financialStatementType: FinancialStatementType.PIX_CASH_IN_RISK_TRANSITORY_DEBIT,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementTransitoryInfoMap], date, bank)
    }

    private void createForPixCashInRiskRefunded(Date date) {
        Integer toleranceDaysToCashInRiskStartDate = 2
        String cashInRiskStartDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, -1 * (CashInRiskAnalysisRequest.PIX_ANALYSIS_LIMIT_TIME_IN_DAYS + toleranceDaysToCashInRiskStartDate)), CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String transactionEffectivateStartDate = CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String endDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, 1), CustomDateUtils.DATABASE_DATETIME_FORMAT)

        StringBuilder sql = new StringBuilder()
        sql.append("select p.id, ptRefund.value from cash_in_risk_analysis_request_reason cirarr")
        sql.append("    inner join cash_in_risk_analysis_request cirar on cirar.id = cirarr.cash_in_risk_analysis_request_id")
        sql.append("    inner join pix_transaction ptOrigin on cirarr.pix_transaction_id = ptOrigin.id")
        sql.append("    inner join pix_transaction_refund ptr on ptr.refunded_transaction_id = ptOrigin.id")
        sql.append("    inner join pix_transaction ptRefund on ptr.transaction_id = ptRefund.id")
        sql.append("    inner join payment p on ptOrigin.payment_id = p.id")
        sql.append("    where cirar.status = :cirarStatus")
        sql.append("        and cirar.deleted = :cirarDeleted")
        sql.append("        and cirarr.cash_in_risk_analysis_reason = :cashInRiskAnalysisReason")
        sql.append("        and cirarr.date_created >= :cirarrStartDateCreated")
        sql.append("        and cirarr.date_created < :cirarrEndDateCreated")
        sql.append("        and cirarr.deleted = :cirarrDeleted")
        sql.append("        and ptOrigin.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("        and ptRefund.status = :pixTransactionStatus")
        sql.append("        and ptRefund.type = :pixTransactionType")
        sql.append("        and ptRefund.effective_date >= :startEffectivateDate")
        sql.append("        and ptRefund.effective_date < :endEffectivateDate")
        sql.append("        and ptr.reason = :ptrReason")
        sql.append("        and p.status = :paymentStatus")
        sql.append("        and not exists")
        sql.append("            (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("                join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("                where fsi.payment_id=p.id")
        sql.append("                    and fs.deleted = :fsDeleted")
        sql.append("                    and fs.financial_statement_type in (:financialStatementTypeList))")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("cirarStatus", AnalysisRequestStatus.DENIED.toString())
        query.setBoolean("cirarDeleted", false)
        query.setString("cashInRiskAnalysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("cirarrStartDateCreated", cashInRiskStartDate)
        query.setString("cirarrEndDateCreated", endDate)
        query.setBoolean("cirarrDeleted", false)
        query.setBoolean("receivedWithAsaasQrCode", true)
        query.setString("pixTransactionStatus", PixTransactionStatus.DONE.toString())
        query.setString("pixTransactionType", PixTransactionType.CREDIT_REFUND.toString())
        query.setString("startEffectivateDate", transactionEffectivateStartDate)
        query.setString("endEffectivateDate", endDate)
        query.setString("ptrReason", PixTransactionRefundReason.FRAUD.toString())
        query.setString("paymentStatus", PaymentStatus.REFUNDED.toString())
        query.setBoolean("fsDeleted", false)
        query.setParameterList("financialStatementTypeList", [FinancialStatementType.PIX_INDIRECT_CASH_IN_RISK_CUSTOMER_REVENUE_REVERSAL.toString(), FinancialStatementType.PIX_CASH_IN_RISK_REFUNDED_TRANSITORY_CREDIT.toString()])

        List<Map> paymentInfoList = query.list().collect { [id: Utils.toLong(it[0]), value: Utils.toBigDecimal(it[1])] }

        if (!paymentInfoList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementCustomerRevenueInfoMap = [
            financialStatementType: FinancialStatementType.PIX_INDIRECT_CASH_IN_RISK_CUSTOMER_REVENUE_REVERSAL,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementCustomerRevenueInfoMap], date, bank)

        Map financialStatementTransitoryInfoMap = [
            financialStatementType: FinancialStatementType.PIX_CASH_IN_RISK_REFUNDED_TRANSITORY_CREDIT,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementTransitoryInfoMap], date, bank)
    }

    private void createForPixCashInRiskReceived(Date date) {
        Integer toleranceDaysToCashInRiskStartDate = 2
        String cashInRiskStartDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, -1 * (CashInRiskAnalysisRequest.PIX_ANALYSIS_LIMIT_TIME_IN_DAYS + toleranceDaysToCashInRiskStartDate)), CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String transactionEffectivateStartDate = CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String endDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, 1), CustomDateUtils.DATABASE_DATETIME_FORMAT)

        StringBuilder sql = new StringBuilder()
        sql.append("select p.id, pt.value from cash_in_risk_analysis_request_reason cirarr")
        sql.append("    inner join cash_in_risk_analysis_request cirar on cirar.id = cirarr.cash_in_risk_analysis_request_id")
        sql.append("    inner join pix_transaction pt on cirarr.pix_transaction_id = pt.id")
        sql.append("    inner join payment p on pt.payment_id = p.id")
        sql.append("    where cirar.status = :cirarStatus")
        sql.append("        and cirar.deleted = :cirarDeleted")
        sql.append("        and cirarr.cash_in_risk_analysis_reason = :cashInRiskAnalysisReason")
        sql.append("        and cirarr.date_created >= :cirarrStartDateCreated")
        sql.append("        and cirarr.date_created < :cirarrEndDateCreated")
        sql.append("        and cirarr.deleted = :cirarrDeleted")
        sql.append("        and pt.status = :pixTransactionStatus")
        sql.append("        and pt.effective_date >= :startEffectivateDate")
        sql.append("        and pt.effective_date < :endEffectivateDate")
        sql.append("        and pt.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("        and not exists")
        sql.append("            (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("                join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("                where fsi.payment_id=p.id")
        sql.append("                    and fs.deleted = :fsDeleted")
        sql.append("                    and fs.financial_statement_type = :financialStatementType)")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("cirarStatus", AnalysisRequestStatus.APPROVED.toString())
        query.setBoolean("cirarDeleted", false)
        query.setString("cashInRiskAnalysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("cirarrStartDateCreated", cashInRiskStartDate)
        query.setString("cirarrEndDateCreated", endDate)
        query.setBoolean("cirarrDeleted", false)
        query.setBoolean("receivedWithAsaasQrCode", true)
        query.setString("pixTransactionStatus", PixTransactionStatus.DONE.toString())
        query.setString("startEffectivateDate", transactionEffectivateStartDate)
        query.setString("endEffectivateDate", endDate)
        query.setBoolean("fsDeleted", false)
        query.setString("financialStatementType", FinancialStatementType.PIX_CASH_IN_RISK_SETTLED_TRANSITORY_CREDIT.toString())

        List<Map> paymentInfoList = query.list().collect { [id: Utils.toLong(it[0]), value: Utils.toBigDecimal(it[1])] }

        if (!paymentInfoList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())

        Map financialStatementTransitoryInfoMap = [
            financialStatementType: FinancialStatementType.PIX_CASH_IN_RISK_SETTLED_TRANSITORY_CREDIT,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementTransitoryInfoMap], date, bank)
    }

    private void createForRefundedByUserBeforeCashInRiskAnalysis(Date date) {
        Integer toleranceDaysToCashInRiskStartDate = 2
        String cashInRiskStartDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, -1 * (CashInRiskAnalysisRequest.PIX_ANALYSIS_LIMIT_TIME_IN_DAYS + toleranceDaysToCashInRiskStartDate)), CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String transactionEffectivateStartDate = CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String endDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, 1), CustomDateUtils.DATABASE_DATETIME_FORMAT)

        StringBuilder sql = new StringBuilder()
        sql.append("select p.id, ptRefund.value from cash_in_risk_analysis_request_reason cirarr")
        sql.append("    inner join cash_in_risk_analysis_request cirar on cirarr.cash_in_risk_analysis_request_id = cirar.id")
        sql.append("    inner join pix_transaction ptOrigin on cirarr.pix_transaction_id = ptOrigin.id")
        sql.append("    inner join pix_transaction_refund ptr on ptr.refunded_transaction_id = ptOrigin.id")
        sql.append("    inner join pix_transaction ptRefund on ptr.transaction_id = ptRefund.id")
        sql.append("    inner join payment p on ptOrigin.payment_id = p.id")
        sql.append("    where cirarr.cash_in_risk_analysis_reason = :cashInRiskAnalysisReason")
        sql.append("        and cirarr.date_created >= :cirarrStartDateCreated")
        sql.append("        and cirarr.date_created < :cirarrEndDateCreated")
        sql.append("        and cirarr.deleted = :cirarrDeleted")
        sql.append("        and ptOrigin.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("        and ptRefund.status = :pixTransactionStatus")
        sql.append("        and ptRefund.type = :pixTransactionType")
        sql.append("        and ptRefund.effective_date >= :startEffectivateDate")
        sql.append("        and ptRefund.effective_date < :endEffectivateDate")
        sql.append("        and ptr.reason = :ptrReason")
        sql.append("        and p.status = :paymentStatus")
        sql.append("        and (cirar.analysis_date is null or cirar.analysis_date > ptRefund.effective_date)")
        sql.append("        and not exists")
        sql.append("            (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("                join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("                where fsi.payment_id=p.id")
        sql.append("                    and fs.deleted = :fsDeleted")
        sql.append("                    and fs.financial_statement_type in (:financialStatementTypeList))")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("cashInRiskAnalysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("cirarrStartDateCreated", cashInRiskStartDate)
        query.setString("cirarrEndDateCreated", endDate)
        query.setBoolean("cirarrDeleted", false)
        query.setBoolean("receivedWithAsaasQrCode", true)
        query.setString("pixTransactionStatus", PixTransactionStatus.DONE.toString())
        query.setString("pixTransactionType", PixTransactionType.CREDIT_REFUND.toString())
        query.setString("startEffectivateDate", transactionEffectivateStartDate)
        query.setString("endEffectivateDate", endDate)
        query.setString("ptrReason", PixTransactionRefundReason.REQUESTED_BY_RECEIVER.toString())
        query.setString("paymentStatus", PaymentStatus.REFUNDED.toString())
        query.setBoolean("fsDeleted", false)
        query.setParameterList("financialStatementTypeList", [FinancialStatementType.PIX_INDIRECT_CASH_IN_RISK_CUSTOMER_REVENUE_REVERSAL.toString(), FinancialStatementType.PIX_CASH_IN_RISK_REFUNDED_TRANSITORY_CREDIT.toString()])

        List<Map> paymentInfoList = query.list().collect { [id: Utils.toLong(it[0]), value: Utils.toBigDecimal(it[1])] }

        if (!paymentInfoList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementCustomerRevenueInfoMap = [
            financialStatementType: FinancialStatementType.PIX_INDIRECT_CASH_IN_RISK_CUSTOMER_REVENUE_REVERSAL,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementCustomerRevenueInfoMap], date, bank)

        Map financialStatementTransitoryInfoMap = [
            financialStatementType: FinancialStatementType.PIX_CASH_IN_RISK_REFUNDED_TRANSITORY_CREDIT,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementTransitoryInfoMap], date, bank)
    }

    private void createForReceivedAfterFailedRefundByAnalysis(Date date) {
        Integer toleranceDaysToCashInRiskStartDate = 2
        String cashInRiskStartDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, -1 * (CashInRiskAnalysisRequest.PIX_ANALYSIS_LIMIT_TIME_IN_DAYS + toleranceDaysToCashInRiskStartDate)), CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String transactionEffectivateStartDate = CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT)
        String endDate = CustomDateUtils.fromDate(CustomDateUtils.sumDays(date.clone() as Date, 1), CustomDateUtils.DATABASE_DATETIME_FORMAT)

        StringBuilder sql = new StringBuilder()
        sql.append("select p.id, pt.value from cash_in_risk_analysis_request_reason cirarr")
        sql.append("    inner join cash_in_risk_analysis_request cirar on cirar.id = cirarr.cash_in_risk_analysis_request_id")
        sql.append("    inner join pix_transaction pt on cirarr.pix_transaction_id = pt.id")
        sql.append("    inner join payment p on pt.payment_id = p.id")
        sql.append("    where cirar.status = :cirarStatus")
        sql.append("        and cirar.deleted = :cirarDeleted")
        sql.append("        and cirarr.cash_in_risk_analysis_reason = :cashInRiskAnalysisReason")
        sql.append("        and cirarr.date_created >= :cirarrStartDateCreated")
        sql.append("        and cirarr.date_created < :cirarrEndDateCreated")
        sql.append("        and cirarr.deleted = :cirarrDeleted")
        sql.append("        and pt.status = :pixTransactionStatus")
        sql.append("        and pt.effective_date >= :startEffectivateDate")
        sql.append("        and pt.effective_date < :endEffectivateDate")
        sql.append("        and pt.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("        and p.status = :paymentStatus")
        sql.append("        and not exists")
        sql.append("            (SELECT fsi.id FROM financial_statement_item fsi")
        sql.append("                join financial_statement fs ON fsi.financial_statement_id=fs.id")
        sql.append("                where fsi.payment_id=p.id")
        sql.append("                    and fs.deleted = :fsDeleted")
        sql.append("                    and fs.financial_statement_type = :financialStatementType)")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("cirarStatus", AnalysisRequestStatus.DENIED.toString())
        query.setBoolean("cirarDeleted", false)
        query.setString("cashInRiskAnalysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("cirarrStartDateCreated", cashInRiskStartDate)
        query.setString("cirarrEndDateCreated", endDate)
        query.setBoolean("cirarrDeleted", false)
        query.setBoolean("receivedWithAsaasQrCode", true)
        query.setString("pixTransactionStatus", PixTransactionStatus.DONE.toString())
        query.setString("startEffectivateDate", transactionEffectivateStartDate)
        query.setString("endEffectivateDate", endDate)
        query.setString("paymentStatus", PaymentStatus.RECEIVED.toString())
        query.setBoolean("fsDeleted", false)
        query.setString("financialStatementType", FinancialStatementType.PIX_CASH_IN_RISK_SETTLED_TRANSITORY_CREDIT.toString())

        List<Map> paymentInfoList = query.list().collect { [id: Utils.toLong(it[0]), value: Utils.toBigDecimal(it[1])] }

        if (!paymentInfoList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())

        Map financialStatementTransitoryInfoMap = [
            financialStatementType: FinancialStatementType.PIX_CASH_IN_RISK_SETTLED_TRANSITORY_CREDIT,
            totalValue: paymentInfoList.value.sum()
        ]
        financialStatementService.saveForPaymentIdList(paymentInfoList.collect { it.id } as List<Long>, [financialStatementTransitoryInfoMap], date, bank)
    }
}
