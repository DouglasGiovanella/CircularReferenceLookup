package com.asaas.service.integration.creditbureaureport

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.creditbureaureport.CreditBureauReportSerasaConfig
import com.asaas.domain.customer.Customer
import grails.transaction.Transactional

@Transactional
abstract class CreditBureauReportSerasaManagerService {

    protected static final String SUCCESS_CODE = "00"
    protected static final String REGISTER_CUSTOMER_PROCESSING_MESSAGE = "Processing"
    protected static final String UNKNOWN_ERROR = "unknow.error"

    protected CreditBureauReportSerasaConfig saveCreditBureauReportSerasaConfig(Customer customer, Map parsedResponse) {
        final String invalidatedCnpjResponseCode = "24"
        final List<String> mustUseAsaasCnpjResponseCodeList = ["11", "21", "22", "23", "25", "27", "84"]
        final List<String> serasaForbiddenCnaeList = ["3511502", "69117", "72400", "73203", "74110", "74128", "74136", "7450001", "7460801", "7499305", "78108", "78205", "78302", "80307", "82113", "8291100", "91910", "94910", "95001"]

        Boolean enabled
        Boolean mustUseAsaasCnpj
        String disabledReason
        String customerCnae = customer.getMainEconomicActivity()?.cnaeCode

        if (parsedResponse.success) {
            enabled = true
            mustUseAsaasCnpj = false
            disabledReason = null
        } else if (mustUseAsaasCnpjResponseCodeList.contains(parsedResponse.code)) {
            enabled = true
            mustUseAsaasCnpj = true
            disabledReason = null
        } else if (customerCnae && serasaForbiddenCnaeList.any { customerCnae.startsWith(it) }) {
            enabled = true
            mustUseAsaasCnpj = true
            disabledReason = null
        } else if (parsedResponse.code == invalidatedCnpjResponseCode) {
            enabled = false
            mustUseAsaasCnpj = false
            disabledReason = "creditBureauReport.denied.invalidatedCnpj"
        } else {
            return null
        }

        CreditBureauReportSerasaConfig creditBureauReportSerasaConfig = CreditBureauReportSerasaConfig.query([cpfCnpj: customer.cpfCnpj]).get()
        if (!creditBureauReportSerasaConfig) creditBureauReportSerasaConfig = new CreditBureauReportSerasaConfig()

        creditBureauReportSerasaConfig.cpfCnpj = customer.cpfCnpj
        creditBureauReportSerasaConfig.cnaeCode = customerCnae
        creditBureauReportSerasaConfig.enabled = enabled
        creditBureauReportSerasaConfig.disabledReason = disabledReason
        creditBureauReportSerasaConfig.mustUseAsaasCnpj = mustUseAsaasCnpj
        creditBureauReportSerasaConfig.save(failOnError: true)

        return creditBureauReportSerasaConfig
    }

    protected Map buildResponseFromCreditBureauReportSerasaConfig(CreditBureauReportSerasaConfig creditBureauReportSerasaConfig) {
        return [success: creditBureauReportSerasaConfig.enabled, message: creditBureauReportSerasaConfig.disabledReason]
    }

    protected String buildRequestUrlPath(String path) {
        String cnpjAsaas = AsaasApplicationHolder.getConfig().asaas.cnpj.substring(0, 9)
        return buildRequestUrl("${path}/${cnpjAsaas}")
    }

    protected String buildRequestUrl(String path) {
        return AsaasApplicationHolder.getConfig().creditbureaureport.rest.baseUrl + path
    }
}
