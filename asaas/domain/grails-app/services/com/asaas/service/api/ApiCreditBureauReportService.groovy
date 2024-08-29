package com.asaas.service.api

import com.asaas.api.ApiAsaasErrorParser
import com.asaas.api.ApiCreditBureauReportParser
import com.asaas.api.ApiMobileUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.PersonType
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportAgreement
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.environment.AsaasEnvironment
import com.asaas.user.UserUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ApiCreditBureauReportService extends ApiBaseService {

    def apiResponseBuilderService
    def creditBureauReportService

    public Map find(Map params) {
        CreditBureauReport creditBureauReport = CreditBureauReport.find(params.id, getProvider(params))
        return apiResponseBuilderService.buildSuccess(ApiCreditBureauReportParser.buildResponseItem(creditBureauReport))
    }

    public Map list(Map params) {
        Map filterParams = ApiCreditBureauReportParser.parseFilters(params)
        Customer customer = getProviderInstance(params)

        List<CreditBureauReport> creditBureauReportList = CreditBureauReport.query(filterParams + [customerId: customer.id]).list(max: getLimit(params), offset: getOffset(params))

        List<Map> buildedCreditBureauReportList = creditBureauReportList.collect { ApiCreditBureauReportParser.buildResponseItem(it) }
        List<Map> extraData = [[isNotFullyApproved: customer.isNotFullyApproved()]]

        return apiResponseBuilderService.buildList(buildedCreditBureauReportList, getLimit(params), getOffset(params), creditBureauReportList.totalCount, extraData)
    }

    public Map save(Map params) {
        if (!canRequestReport(params)) return apiResponseBuilderService.buildForbidden("Para realizar consultas Serasa via API solicite a liberação junto ao seu gerente de contas.")

        Customer customer = getProviderInstance(params)
        Map parsedParams = ApiCreditBureauReportParser.parseSaveParams(params)

        CreditBureauReport creditBureauReport = creditBureauReportService.save(customer, UserUtils.getCurrentUser(), parsedParams)

        if (creditBureauReport.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(creditBureauReport)
        }

        return apiResponseBuilderService.buildSuccess(ApiCreditBureauReportParser.buildResponseItem(creditBureauReport, true))
    }

    public Map requestWizardIndex(Map params) {
        Customer customer = getProviderInstance(params)

        Map response = [:]
        response.terms = CreditBureauReportAgreement.getCurrentContractTextIfNecessary(customer)

        BusinessValidation naturalPersonRequestValidation = creditBureauReportService.canRequestCreditBureauReport(customer, PersonType.FISICA)
        BusinessValidation legalPersonRequestValidation = creditBureauReportService.canRequestCreditBureauReport(customer, PersonType.JURIDICA)

        response.naturalPersonRequestValidation = ApiAsaasErrorParser.buildResponseList(naturalPersonRequestValidation)
        response.legalPersonRequestValidation = ApiAsaasErrorParser.buildResponseList(legalPersonRequestValidation)

        return apiResponseBuilderService.buildSuccess(response)
    }

    public Map downloadReport(Map params) {
        CreditBureauReport creditBureauReport = CreditBureauReport.find(params.id, getProvider(params))

        byte[] file = creditBureauReportService.buildCreditBureauReportFile(creditBureauReport)
        return apiResponseBuilderService.buildFile(file, "Consulta.pdf")
    }

    private Boolean canRequestReport(Map params) {
        if (!AsaasEnvironment.isProduction()) return true
        if (ApiMobileUtils.isMobileAppRequest()) return true
        if (CustomerParameter.getValue(getProviderInstance(params), CustomerParameterName.ALLOW_CREDIT_BUREAU_REPORT_REQUEST_VIA_API)) return true

        return false
    }
}
