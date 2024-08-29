package com.asaas.service.integration.creditbureaureport

import com.asaas.creditbureaureport.adapter.CreditBureauReportInfoAdapter
import com.asaas.customer.PersonType
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportSerasaConfig
import com.asaas.integration.creditbureaureport.CreditBureauReportBuilder
import com.asaas.integration.creditbureaureport.CreditBureauReportInfoAdapterBuilder
import com.asaas.integration.creditbureaureport.CreditBureauReportManager
import grails.transaction.Transactional

@Transactional
class CreditBureauReportSerasaQueryManagerService {

    def creditBureauReportLogService
    def creditBureauReportSerasaRegisterCustomerManagerService
    def creditBureauReportSerasaCustomerAccessionManagerService

    public CreditBureauReportInfoAdapter processQuery(CreditBureauReport creditBureauReport, PersonType personType, Boolean isFirstCreditBureauReport) {
        if (!creditBureauReportSerasaCustomerAccessionManagerService.validate(creditBureauReport.customer).success) throw new Exception("CreditBureauReportSerasaQueryManagerService >> Tentativa de uso da consulta Serasa para cliente não válido")

        if (isFirstCreditBureauReport) {
            Map validatedRegisterCustomer = creditBureauReportSerasaRegisterCustomerManagerService.register(creditBureauReport.customer)
            if (!validatedRegisterCustomer.success) throw new Exception(validatedRegisterCustomer.partnerCodeDescription ?: validatedRegisterCustomer.message)
        }

        CreditBureauReportInfoAdapter creditBureauReportInfoAdapter = executeSerasaQuery(creditBureauReport, personType)

        return creditBureauReportInfoAdapter
    }

    private CreditBureauReportInfoAdapter executeSerasaQuery(CreditBureauReport creditBureauReport, PersonType personType) {
        Boolean mustUseAsaasCnpj = CreditBureauReportSerasaConfig.find(creditBureauReport.customer)?.mustUseAsaasCnpj

        CreditBureauReportBuilder creditBureauReportBuilder = new CreditBureauReportBuilder()
        CreditBureauReportManager creditBureauReportManager = new CreditBureauReportManager()
        creditBureauReportManager.post(creditBureauReportBuilder.buildCreditBureauReportRequestBody(creditBureauReport, personType, mustUseAsaasCnpj))

        creditBureauReportLogService.save(creditBureauReport.customer, creditBureauReport, creditBureauReportManager.getResponseBody())

        if (creditBureauReportManager.isSuccessful()) {
            CreditBureauReportInfoAdapterBuilder creditBureauReportInfoAdapterBuilder = new CreditBureauReportInfoAdapterBuilder()
            return creditBureauReportInfoAdapterBuilder.build(personType, creditBureauReportManager.getResponseBody())
        }

        throw new Exception("CreditBureauReportSerasaQueryManagerService >> Ocorreu erro ao realizar consulta de pagador no Serasa")
    }
}
