package com.asaas.service.integration.creditbureaureport

import com.asaas.domain.creditbureaureport.CreditBureauReportSerasaConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.creditbureaureport.CreditBureauReportRestManager
import com.asaas.integration.creditbureaureport.dto.RegisterCustomerDTO
import com.asaas.integration.creditbureaureport.dto.RegisterCustomerResponseDTO
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CreditBureauReportSerasaRegisterCustomerManagerService extends CreditBureauReportSerasaManagerService {

    def creditBureauReportLogService
    def revenueServiceRegisterService

    public Map register(Customer customer) {
        if (!AsaasEnvironment.isProduction()) return [success: true]

        Boolean mustUseAsaasCnpj = CreditBureauReportSerasaConfig.find(customer)?.mustUseAsaasCnpj

        if (mustUseAsaasCnpj) return [success: true]

        Map response = registerCustomer(customer)

        if (response) {
            if (isCustomerRegisterProcessing(response.message)) {
                throw new BusinessException("Como é a sua primeira consulta no Asaas, o seu cadastro está sendo avaliado pelo Serasa no momento. Realize uma nova tentativa de consulta mais tarde.")
            }

            CreditBureauReportSerasaConfig creditBureauReportSerasaConfig = saveCreditBureauReportSerasaConfig(customer, response)

            if (creditBureauReportSerasaConfig?.enabled) {
                response = buildResponseFromCreditBureauReportSerasaConfig(creditBureauReportSerasaConfig)
            }

            return response
        }

        throw new RuntimeException("CreditBureauReportSerasaRegisterCustomerManagerService.register >> Ocorreu um erro ao realizar chamada da inclusão de cadastro pontual pelo distribuidor")
    }

    private Map registerCustomer(Customer customer) {
        final String registerCustomerRequestPath = "/sales/v1/partners/orders"
        String url = buildRequestUrlPath(registerCustomerRequestPath)
        User contactUser = User.admin(customer, [:]).get()

        RevenueServiceRegister revenueServiceRegister = revenueServiceRegisterService.findLegalPerson(customer.getLastCpfCnpj())
        RegisterCustomerDTO registerCustomerDTO = new RegisterCustomerDTO(customer, revenueServiceRegister, contactUser)

        CreditBureauReportRestManager creditBureauReportRestManager = new CreditBureauReportRestManager()
        creditBureauReportRestManager.post(url, registerCustomerDTO.toMap())

        String responseBodyAsJson = (creditBureauReportRestManager.getResponseBody() as JSON).toString()

        creditBureauReportLogService.save(customer, null, responseBodyAsJson)

        Map response = [:]

        if (creditBureauReportRestManager.isSuccessful()) {
            response = parseResponse(responseBodyAsJson)
        }

        return response
    }

    private Map parseResponse(String responseBody) {
        RegisterCustomerResponseDTO registerCustomerResponseDTO = GsonBuilderUtils.buildClassFromJson(responseBody, RegisterCustomerResponseDTO)

        Map parsedResponse = [:]
        parsedResponse.code = registerCustomerResponseDTO.codigoDeRetorno
        parsedResponse.success = CreditBureauReportSerasaManagerService.SUCCESS_CODE == registerCustomerResponseDTO.codigoDeRetorno
        parsedResponse.partnerCodeDescription = registerCustomerResponseDTO.descricaoRetorno
        if (!parsedResponse.success) parsedResponse.message = CreditBureauReportSerasaManagerService.UNKNOWN_ERROR
        if (isCustomerRegisterProcessing(registerCustomerResponseDTO.mensagem)) parsedResponse.message = registerCustomerResponseDTO.mensagem

        return parsedResponse
    }

    private Boolean isCustomerRegisterProcessing(String message) {
        return [CreditBureauReportSerasaManagerService.REGISTER_CUSTOMER_PROCESSING_MESSAGE].contains(message)
    }
}
