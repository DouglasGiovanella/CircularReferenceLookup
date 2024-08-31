package com.asaas.service.creditbureaureport

import com.asaas.creditbureaureport.adapter.CreditBureauReportBankChequeAdapter
import com.asaas.creditbureaureport.adapter.CreditBureauReportInfoAdapter
import com.asaas.creditbureaureport.adapter.CreditBureauReportPendencyAdapter
import com.asaas.creditbureaureport.adapter.CreditBureauReportProtestAdapter
import com.asaas.creditbureaureport.adapter.CreditBureauReportSerasaQueryAdapter
import com.asaas.creditbureaureport.adapter.CreditBureauReportStolenDocumentAdapter
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportBankCheque
import com.asaas.domain.creditbureaureport.CreditBureauReportFinancialPendency
import com.asaas.domain.creditbureaureport.CreditBureauReportInfo
import com.asaas.domain.creditbureaureport.CreditBureauReportInternalPendency
import com.asaas.domain.creditbureaureport.CreditBureauReportProtest
import com.asaas.domain.creditbureaureport.CreditBureauReportSerasaQuery
import com.asaas.domain.creditbureaureport.CreditBureauReportStolenDocument

import grails.transaction.Transactional

@Transactional
class CreditBureauReportInfoService {

    public CreditBureauReportInfo save(CreditBureauReport creditBureauReport, CreditBureauReportInfoAdapter creditBureauReportInfoAdapter) {
        CreditBureauReportInfo creditBureauReportInfo = new CreditBureauReportInfo()
        creditBureauReportInfo.creditBureauReportId = creditBureauReport.id
        creditBureauReportInfo.name = creditBureauReportInfoAdapter.name
        creditBureauReportInfo.motherName = creditBureauReportInfoAdapter.motherName
        creditBureauReportInfo.cpfCnpjStatus = creditBureauReportInfoAdapter.cpfCnpjStatus
        creditBureauReportInfo.cpfCnpjStatusDate = creditBureauReportInfoAdapter.cpfCnpjStatusDate
        creditBureauReportInfo.birthDate = creditBureauReportInfoAdapter.birthDate
        creditBureauReportInfo.foundationDate = creditBureauReportInfoAdapter.foundationDate
        creditBureauReportInfo.score = creditBureauReportInfoAdapter.score
        creditBureauReportInfo.scoreDescription =  creditBureauReportInfoAdapter.scoreDescription
        creditBureauReportInfo.probabilityNonPaymentTax = creditBureauReportInfoAdapter.probabilityNonPaymentTax
        creditBureauReportInfo.riskClassification = creditBureauReportInfoAdapter.riskClassification
        creditBureauReportInfo.riskClassificationDescription = creditBureauReportInfoAdapter.riskClassificationDescription
        creditBureauReportInfo.riskClassificationClassDescription = creditBureauReportInfoAdapter.riskClassificationClassDescription
        creditBureauReportInfo.save(failOnError: true)

        for (CreditBureauReportStolenDocumentAdapter stolenDocumentAdapter : creditBureauReportInfoAdapter.stolenDocumentAdapterList) {
            CreditBureauReportStolenDocument creditBureauReportStolenDocument = new CreditBureauReportStolenDocument()
            creditBureauReportStolenDocument.creditBureauReportInfo = creditBureauReportInfo
            creditBureauReportStolenDocument.properties[
                "type",
                "number",
                "reason",
                "date"
            ] = stolenDocumentAdapter.properties
            creditBureauReportStolenDocument.save(failOnError: true)
        }

        for (CreditBureauReportPendencyAdapter internalPendencyAdapter : creditBureauReportInfoAdapter.internalPendencyAdapterList) {
            CreditBureauReportInternalPendency creditBureauReportInternalPendency = new CreditBureauReportInternalPendency()
            creditBureauReportInternalPendency.creditBureauReportInfo = creditBureauReportInfo
            creditBureauReportInternalPendency.properties[
                "date",
                "type",
                "isGuarantor",
                "currencyType",
                "value",
                "contract",
                "origin",
                "area"
            ] = internalPendencyAdapter.properties
            creditBureauReportInternalPendency.save(failOnError: true)
        }

        for (CreditBureauReportPendencyAdapter financialPendencyAdapter : creditBureauReportInfoAdapter.financialPendencyAdapterList) {
            CreditBureauReportFinancialPendency creditBureauReportFinancialPendency = new CreditBureauReportFinancialPendency()
            creditBureauReportFinancialPendency.creditBureauReportInfo = creditBureauReportInfo
            creditBureauReportFinancialPendency.properties[
                "date",
                "type",
                "isGuarantor",
                "currencyType",
                "value",
                "contract",
                "origin",
                "area"
            ] = financialPendencyAdapter.properties
            creditBureauReportFinancialPendency.save(failOnError: true)
        }

        for (CreditBureauReportProtestAdapter protestAdapter : creditBureauReportInfoAdapter.protestAdapterList) {
            CreditBureauReportProtest creditBureauReportProtest = new CreditBureauReportProtest()
            creditBureauReportProtest.creditBureauReportInfo = creditBureauReportInfo
            creditBureauReportProtest.properties[
                "date",
                "currencyType",
                "value",
                "registry",
                "origin",
                "area",
                "description",
                "type"
            ] = protestAdapter.properties
            creditBureauReportProtest.save(failOnError: true)
        }

        for (CreditBureauReportBankChequeAdapter bankChequeAdapter : creditBureauReportInfoAdapter.bankChequeAdapterList) {
            CreditBureauReportBankCheque creditBureauReportBankCheque = new CreditBureauReportBankCheque()
            creditBureauReportBankCheque.creditBureauReportInfo = creditBureauReportInfo
            creditBureauReportBankCheque.properties[
                "date",
                "number",
                "totalValue",
                "bankNumber",
                "bankName",
                "agencyNumber",
                "agencyCity",
                "agencyState"
            ] = bankChequeAdapter.properties
            creditBureauReportBankCheque.save(failOnError: true)
        }

        for (CreditBureauReportSerasaQueryAdapter serasaQueryAdapter : creditBureauReportInfoAdapter.serasaQueryAdapterList) {
            CreditBureauReportSerasaQuery creditBureauReportSerasaQuery = new CreditBureauReportSerasaQuery()
            creditBureauReportSerasaQuery.creditBureauReportInfo = creditBureauReportInfo
            creditBureauReportSerasaQuery.properties[
                "type",
                "firstDate",
                "lastDate",
                "totalChequeIssue15Days",
                "totalChequeIssue30Days",
                "totalChequeIssueBetween31and60Days",
                "totalChequeIssueBetween61and90Days",
                "totalChequeIssue"
            ] = serasaQueryAdapter.properties
            creditBureauReportSerasaQuery.save(failOnError: true)
        }
        return creditBureauReportInfo
    }
}
