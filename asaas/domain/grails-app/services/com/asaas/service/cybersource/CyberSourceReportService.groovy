package com.asaas.service.cybersource

import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.integration.cybersource.CyberSourceManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class CyberSourceReportService {

    public void processCurrentTransactionExceptionReport() {
        Date reportDate = new Date()

        String csvReport = retrieveTransactionExceptionReport(reportDate)

        List<Map> parsedDataMapList = parseTransactionExceptionReport(csvReport)

        if (!parsedDataMapList.size()) {
            return
        }

        for (Map itemDataMap in parsedDataMapList) {
            CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.query([requestKey: itemDataMap.paymentRequestID, transactionKey: itemDataMap.transactionReferenceNumber, transactionReference: itemDataMap.merchantRefNumber]).get()
            itemDataMap.tid = creditCardAuthorizationInfo ? creditCardAuthorizationInfo.transactionIdentifier : ""
        }

        AsaasLogger.warn("CyberSourceReportService.processCurrentTransactionExceptionReport >> Transações com problema na CyberSource: ${parsedDataMapList}")
    }

    private String retrieveTransactionExceptionReport(Date reportDate) {
        String reportName = "TransactionExceptionDetailReport_Daily_Classic"

        String reportDateString = CustomDateUtils.fromDate(reportDate, CustomDateUtils.DATABASE_DATE_FORMAT)

        String reportPath = "/reporting/v3/report-downloads?organizationId=${CyberSourceManager.ASAAS_MERCHANT_ID}&reportDate=${reportDateString}&reportName=${reportName}"

        CyberSourceManager cybersourceManager = new CyberSourceManager()
        cybersourceManager.responseInJsonFormat = false
        cybersourceManager.get(reportPath, null)

        String csvReport

        if (cybersourceManager.isSuccessful()) {
            csvReport = cybersourceManager.responseString
        } else {
            AsaasLogger.error("CyberSourceReportService.retrieveTransactionExceptionReport >> Falha ao solicitar relatório de exceções de transações. ${cybersourceManager.responseBody?.message}")
        }

        return csvReport
    }

    private List<Map> parseTransactionExceptionReport(String csvReport) {
        List<Map> resultDataMapList = []

        csvReport.splitEachLine(',') {fields ->
            if (fields[0] == CyberSourceManager.ASAAS_MERCHANT_ID || fields[0] == "request_id") return

            BigDecimal amount = fields[6].toBigDecimal()
            if (BigDecimalUtils.abs(amount) == 0.01) return

            Map itemDataMap = [
                transactionDate: fields[1],
                merchantRefNumber: fields[3],
                transactionReferenceNumber: fields[4],
                amount: amount,
                paymentRequestID: fields[9],
                exceptionCategory: fields[12],
                exceptionMessage: fields[13],
                icsApplications: [fields[39]?.replace('"', '')]
            ]

            if (fields[40] && fields[40].indexOf("ics_") > -1) {
                itemDataMap.icsApplications.add(fields[40]?.replace('"', ''))
            }

            resultDataMapList.add(itemDataMap)
        }

        return resultDataMapList
    }

}
