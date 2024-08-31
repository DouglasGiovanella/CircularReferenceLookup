package com.asaas.service.integration.creditbureaureport

import com.asaas.domain.creditbureaureport.CreditBureauReportSerasaConfig
import com.asaas.domain.customer.Customer
import com.asaas.integration.creditbureaureport.CreditBureauReportRestManager
import com.asaas.integration.creditbureaureport.dto.ValidateCustomerAccessionDTO
import com.asaas.integration.creditbureaureport.dto.ValidateCustomerAccessionResponseDTO
import com.asaas.integration.creditbureaureport.enums.ResponseCode
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CreditBureauReportSerasaCustomerAccessionManagerService extends CreditBureauReportSerasaManagerService {

    def creditBureauReportLogService

    public Map validate(Customer customer) {
        CreditBureauReportSerasaConfig creditBureauReportSerasaConfig = CreditBureauReportSerasaConfig.find(customer)

        if (creditBureauReportSerasaConfig) return buildResponseFromCreditBureauReportSerasaConfig(creditBureauReportSerasaConfig)

        Map response = validateSerasaCustomerAccession(customer)

        if (response) {
            creditBureauReportSerasaConfig = saveCreditBureauReportSerasaConfig(customer, response)

            if (creditBureauReportSerasaConfig?.enabled) {
                response = buildResponseFromCreditBureauReportSerasaConfig(creditBureauReportSerasaConfig)
            }

            return response
        }

        throw new Exception("CreditBureauReportSerasaCustomerAccessionManagerService >> Ocorreu um erro ao realizar consulta de filtro de adesão de indiretos no Serasa")
    }

    private Map validateSerasaCustomerAccession(Customer customer) {
        final String registerCustomerRequestPath = "/sales/v1/analyse-sales-distributor"
        ValidateCustomerAccessionDTO validateCustomerAccessionDTO = new ValidateCustomerAccessionDTO(customer)
        String url = buildRequestUrlPath(registerCustomerRequestPath)

        CreditBureauReportRestManager creditBureauReportRestManager = new CreditBureauReportRestManager()
        creditBureauReportRestManager.get(url, validateCustomerAccessionDTO.properties)

        String responseBodyAsJson = (creditBureauReportRestManager.getResponseBody() as JSON).toString()

        creditBureauReportLogService.save(customer, null, responseBodyAsJson)

        Map response = [:]

        if (creditBureauReportRestManager.isSuccessful()) {
            response = parseResponse(responseBodyAsJson)
        }

        return response
    }

    private Map parseResponse(String responseBody) {
        ValidateCustomerAccessionResponseDTO validateCustomerAccessionResponseDTO = GsonBuilderUtils.buildClassFromJson(responseBody, ValidateCustomerAccessionResponseDTO)
        ResponseCode responseCode = ResponseCode.findByDescription(validateCustomerAccessionResponseDTO.description)
        final String successCustomerAccessionResponseCode = "00"

        Map parsedResponse = [:]
        parsedResponse.success = successCustomerAccessionResponseCode == responseCode?.code
        parsedResponse.code = responseCode?.code
        parsedResponse.partnerCodeDescription = validateCustomerAccessionResponseDTO.description
        if (!parsedResponse.success) parsedResponse.message = CreditBureauReportSerasaManagerService.UNKNOWN_ERROR

        return parsedResponse
    }
}
