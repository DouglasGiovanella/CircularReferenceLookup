package com.asaas.service.pix.bacenreport

import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.cashinriskanalysis.CashInRiskAnalysisReason
import com.asaas.cashinriskanalysis.CashInRiskAnalysisRequestFinishReason
import com.asaas.chargedfee.ChargedFeeStatus
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.customer.PersonType
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequestReason
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.file.AsaasFile
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.springframework.util.StopWatch

import static grails.async.Promises.task

@Transactional
class PixBacenReportService {

    def fileService
    def pixBacenReportManagerService
    def messageService

    public void sendToEmail(String bacenReportXml, Long id) {
        AsaasFile asaasFile = fileService.createFile("tmp-pix-arquivo-apix001-${id}", bacenReportXml)
        messageService.sendPixBacenReport(asaasFile)
    }

    public void sendBacenReportBase64ToEmail(String bacenReportXmlBase64, Long id) {
        String bacenReportXml = new String(bacenReportXmlBase64.decodeBase64())

        sendToEmail(bacenReportXml, id)
    }

    public void getBacenReportInfo(Date initialDate, Date finalDate) {
        task {
            Utils.withNewTransactionAndRollbackOnError {
                feeAmountsInfo(initialDate, finalDate)
                savePrecautionaryBlockInfo(initialDate, finalDate)
            }
        }
    }

    private void feeAmountsInfo(Date initialDate, Date finalDate) {
        AsaasLogger.info("PixBacenReportService.feeAmountsInfo() >> [${new Date()}]")

        Map revenueInfo = calculateFeeAmountsInfo(initialDate, finalDate)
        pixBacenReportManagerService.saveFeeAmountsInfo(revenueInfo)
    }

    private Map calculateFeeAmountsInfo(Date initialDate, Date finalDate) {
        AsaasLogger.info("PixBacenReportService.feeAmountsInfo.task >> [Início: ${new Date()}]")

        StopWatch stopWatch = new StopWatch("PixBacenReportService")
        Map revenueInfo = [:]
        stopWatch.start("legalPersonDebitFeeRevenue")
        revenueInfo.legalPersonDebitFeeRevenue = ChargedFee.sumValueAbs(['dateCreated[ge]': initialDate, 'dateCreated[le]': finalDate, status: ChargedFeeStatus.DEBITED, type: ChargedFeeType.PIX_DEBIT]).get()
        stopWatch.stop()

        stopWatch.start("legalPersonCreditFeeRevenue")
        revenueInfo.legalPersonCreditFeeRevenue = getCreditFeeRevenue(initialDate, finalDate, PersonType.JURIDICA)
        stopWatch.stop()

        stopWatch.start("naturalPersonCreditFeeRevenue")
        revenueInfo.naturalPersonCreditFeeRevenue = getCreditFeeRevenue(initialDate, finalDate, PersonType.FISICA)
        stopWatch.stop()

        AsaasLogger.info(stopWatch.prettyPrint())
        AsaasLogger.info("PixBacenReportService.feeAmountsInfo.task >> [Conclusão: ${new Date()}]")

        return revenueInfo
    }

    private BigDecimal getCreditFeeRevenue(Date initialDate, Date finalDate, PersonType personType) {
        BigDecimal pixWithPaymentFee = getCreditWithPaymentFeeRevenue(initialDate, finalDate, personType)
        BigDecimal pixWithoutPaymentFee = getCreditWithoutPaymentFeeRevenue(initialDate, finalDate, personType)

        return pixWithPaymentFee + pixWithoutPaymentFee
    }

    private BigDecimal getCreditWithPaymentFeeRevenue(Date initialDate, Date finalDate, PersonType personType) {
        StringBuilder sql = new StringBuilder()
        sql.append("select coalesce(sum(abs(ft.value)), 0) from pix_transaction pix")
        sql.append(" join financial_transaction ft on ft.payment_id = pix.payment_id")
        sql.append(" join customer c on c.id = pix.customer_id")
        sql.append("   where pix.date_created >= :initialDate")
        sql.append("     and pix.date_created <= :finalDate")
        sql.append("     and pix.type = :type")
        sql.append("     and pix.status = :status")
        sql.append("     and pix.received_with_asaas_qr_code = :receivedWithAsaasQrCode")
        sql.append("     and pix.payment_id is not null")
        sql.append("     and c.person_type = :personType")
        sql.append("     and ft.transaction_type = :financialTransactionType")
        sql.append("     and ft.charged_fee_id is null")

        SQLQuery query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("initialDate", CustomDateUtils.fromDate(initialDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("finalDate", CustomDateUtils.fromDate(finalDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("type", PixTransactionType.CREDIT.toString())
        query.setString("status", PixTransactionStatus.DONE.toString())
        query.setString("receivedWithAsaasQrCode", 'false')
        query.setString("personType", personType.toString())
        query.setString("financialTransactionType", FinancialTransactionType.PAYMENT_FEE.toString())

        return query.list().get(0)
    }

    private BigDecimal getCreditWithoutPaymentFeeRevenue(Date initialDate, Date finalDate, PersonType personType) {
        StringBuilder sql = new StringBuilder()
        sql.append("select coalesce(sum(abs(ft.value)), 0) from financial_transaction ft")
        sql.append(" join customer c on c.id = ft.provider_id")
        sql.append("   where ft.date_created >= :initialDate")
        sql.append("     and ft.date_created <= :finalDate")
        sql.append("     and ft.transaction_type = :financialTransactionType")
        sql.append("     and c.person_type = :personType")

        SQLQuery query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery(sql.toString())

        query.setString("initialDate", CustomDateUtils.fromDate(initialDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("finalDate", CustomDateUtils.fromDate(finalDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("financialTransactionType", FinancialTransactionType.PIX_TRANSACTION_CREDIT_FEE.toString())
        query.setString("personType", personType.toString())

        return query.list().get(0)
    }

    private void savePrecautionaryBlockInfo(Date initialDate, Date finalDate) {
        Map precautionaryBlockInfo = calculatePrecautionaryBlockInfo(initialDate, finalDate)
        pixBacenReportManagerService.savePrecautionaryBlockInfo(precautionaryBlockInfo)
    }

    private Map calculatePrecautionaryBlockInfo(Date initialDate, Date finalDate) {
        finalDate = CustomDateUtils.sumDays(finalDate, 1)

        Map precautionaryBlockInfo = [:]

        Object[] awaitingPrecautionaryBlockAnalysisInfo = CashInRiskAnalysisRequestReason.awaitingPrecautionaryBlockAnalysisInfoForBacenReport(["dateCreated[ge]": initialDate, "dateCreated[lt]": finalDate]).get()
        precautionaryBlockInfo.awaitingPrecautionaryBlockAnalysisCount = awaitingPrecautionaryBlockAnalysisInfo[0]
        precautionaryBlockInfo.awaitingPrecautionaryBlockAnalysisTotalValue = awaitingPrecautionaryBlockAnalysisInfo[1]

        Object[] approvedPrecautionaryBlockAnalysisInfo = CashInRiskAnalysisRequestReason.approvedPrecautionaryBlockAnalysisInfoForBacenReport(["dateCreated[ge]": initialDate, "dateCreated[lt]": finalDate]).get()
        precautionaryBlockInfo.approvedPrecautionaryBlockAnalysisCount = approvedPrecautionaryBlockAnalysisInfo[0]
        precautionaryBlockInfo.approvedPrecautionaryBlockAnalysisTotalValue = approvedPrecautionaryBlockAnalysisInfo[1]

        Map deniedPrecautionaryBlockAnalysisInfo = getDeniedPrecautionaryBlockAnalysisInfo(initialDate, finalDate)
        precautionaryBlockInfo.deniedPrecautionaryBlockAnalysisCount = deniedPrecautionaryBlockAnalysisInfo.deniedPrecautionaryBlockAnalysisCount
        precautionaryBlockInfo.deniedPrecautionaryBlockAnalysisTotalValue = deniedPrecautionaryBlockAnalysisInfo.deniedPrecautionaryBlockAnalysisTotalValue

        Map refundedBeforePrecautionaryBlockAnalysisInfo = getRefundedBeforePrecautionaryBlockAnalysisInfo(initialDate, finalDate)
        precautionaryBlockInfo.refundedBeforePrecautionaryBlockAnalysisCount = refundedBeforePrecautionaryBlockAnalysisInfo.refundedBeforePrecautionaryBlockAnalysisCount
        precautionaryBlockInfo.refundedBeforePrecautionaryBlockAnalysisTotalValue = refundedBeforePrecautionaryBlockAnalysisInfo.refundedBeforePrecautionaryBlockAnalysisTotalValue

        BigDecimal maxHoursToAnalyzePrecautionaryBlock = CashInRiskAnalysisRequestReason.getMaxTimeInHoursToAnalyzePrecautionaryBlock(["cashInRiskAnalysisRequestAnalysisDate[ge]": initialDate, "cashInRiskAnalysisRequestAnalysisDate[lt]": finalDate]).get()
        precautionaryBlockInfo.maxHoursToAnalyzePrecautionaryBlock = BigDecimalUtils.roundDown(maxHoursToAnalyzePrecautionaryBlock)

        return precautionaryBlockInfo
    }

    private Map getDeniedPrecautionaryBlockAnalysisInfo(Date initialDate, Date finalDate) {
        StringBuilder sql = new StringBuilder()
        sql.append("select count(*) as quantity, coalesce(sum(abs(ptRefund.value)), 0) as sumPixTransactionValueAbs")
        sql.append(" from cash_in_risk_analysis_request_reason cirarr")
        sql.append("     inner join cash_in_risk_analysis_request cirar on cirarr.cash_in_risk_analysis_request_id = cirar.id")
        sql.append("     inner join pix_transaction_refund ptr on cirarr.pix_transaction_id = ptr.refunded_transaction_id")
        sql.append("     inner join pix_transaction ptRefund on ptr.transaction_id = ptRefund.id")
        sql.append(" where cirarr.deleted = false")
        sql.append("     and cirarr.date_created >= :initialDate")
        sql.append("     and cirarr.date_created < :finalDate")
        sql.append("     and cirarr.cash_in_risk_analysis_reason = :analysisReason")
        sql.append("     and cirar.status = :analysisStatus")
        sql.append("     and cirar.deleted = false")
        sql.append("     and cirar.finish_reason = :analysisFinishReason")
        sql.append("     and ptRefund.type = :pixTransactionType")
        sql.append("     and ptr.reason = :pixTransactionRefundReason")

        SQLQuery query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setString("initialDate", CustomDateUtils.fromDate(initialDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("finalDate", CustomDateUtils.fromDate(finalDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("analysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("analysisStatus", AnalysisRequestStatus.DENIED.toString())
        query.setString("analysisFinishReason", CashInRiskAnalysisRequestFinishReason.CASH_IN_SUSPECTED_OF_FRAUD.toString())
        query.setString("pixTransactionType", PixTransactionType.CREDIT_REFUND.toString())
        query.setString("pixTransactionRefundReason", PixTransactionRefundReason.FRAUD.toString())

        List queryResult = query.list().get(0)

        return [
            deniedPrecautionaryBlockAnalysisCount: queryResult[0] as Integer,
            deniedPrecautionaryBlockAnalysisTotalValue: queryResult[1] as BigDecimal
        ]
    }

    private Map getRefundedBeforePrecautionaryBlockAnalysisInfo(Date initialDate, Date finalDate) {
        StringBuilder sql = new StringBuilder()
        sql.append("select count(*) as quantity, coalesce(sum(abs(ptRefund.value)), 0) as sumPixTransactionValueAbs")
        sql.append(" from cash_in_risk_analysis_request_reason cirarr")
        sql.append("     inner join pix_transaction_refund ptr on ptr.refunded_transaction_id = cirarr.pix_transaction_id")
        sql.append("     inner join pix_transaction ptRefund on ptr.transaction_id = ptRefund.id")
        sql.append("     inner join cash_in_risk_analysis_request cirar on cirarr.cash_in_risk_analysis_request_id = cirar.id")
        sql.append(" where cirarr.deleted = false")
        sql.append("     and cirarr.date_created >= :initialDate")
        sql.append("     and cirarr.date_created < :finalDate")
        sql.append("     and cirarr.cash_in_risk_analysis_reason = :analysisReason")
        sql.append("     and ptRefund.type = :pixTransactionType")
        sql.append("     and ptRefund.status = :pixTransactionStatus")
        sql.append("     and (cirar.status in :pendingStatusList or (cirar.status in :finishedStatusList and cirar.analysis_date > ptRefund.effective_date))")
        sql.append("     and cirar.deleted = false")
        sql.append("     and ptr.reason = :pixTransactionRefundReason")

        SQLQuery query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setString("initialDate", CustomDateUtils.fromDate(initialDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("finalDate", CustomDateUtils.fromDate(finalDate, CustomDateUtils.DATABASE_DATETIME_FORMAT))
        query.setString("analysisReason", CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.toString())
        query.setString("pixTransactionType", PixTransactionType.CREDIT_REFUND.toString())
        query.setString("pixTransactionStatus", PixTransactionStatus.DONE.toString())
        query.setParameterList("pendingStatusList", AnalysisRequestStatus.getPendingList().collect { it.toString() })
        query.setParameterList("finishedStatusList", AnalysisRequestStatus.getFinishedList().collect { it.toString() })
        query.setString("pixTransactionRefundReason", PixTransactionRefundReason.REQUESTED_BY_RECEIVER.toString())

        List queryResult = query.list().get(0)

        return [
            refundedBeforePrecautionaryBlockAnalysisCount: queryResult[0] as Integer,
            refundedBeforePrecautionaryBlockAnalysisTotalValue: queryResult[1] as BigDecimal
        ]
    }
}
