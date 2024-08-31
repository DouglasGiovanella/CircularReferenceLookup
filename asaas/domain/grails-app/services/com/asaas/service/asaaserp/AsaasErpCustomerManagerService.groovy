package com.asaas.service.asaaserp

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asaaserp.adapter.AsaasErpCustomerCreateAdapter
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.AsaasErpUndefinedErrorException
import com.asaas.exception.BusinessException
import com.asaas.integration.asaaserp.api.AsaasErpManager
import com.asaas.integration.asaaserp.dto.customer.CustomerRequestDTO
import com.asaas.integration.asaaserp.dto.customer.CustomerResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils

@Transactional
class AsaasErpCustomerManagerService {

    public Map create(AsaasErpCustomerCreateAdapter asaasErpCustomerCreateAdapter, String integrationUsername, String apiAccessToken) {
        if (AsaasEnvironment.isDevelopment()) return [ apiKey: UUID.randomUUID().toString(), externalId: RandomStringUtils.randomNumeric(6)]

        CustomerRequestDTO newCustomer = new CustomerRequestDTO(asaasErpCustomerCreateAdapter, integrationUsername, apiAccessToken)

        String apiKey = AsaasApplicationHolder.getConfig().asaaserp.accessToken

        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.changeApiKeyNameParameterToCreateCustomeInAsaasERP = true

        LinkedHashMap bodyRequest = newCustomer.properties
        bodyRequest.remove("class")

        asaasErpManager.isLegacy = false
        asaasErpManager.post("/api/register/new-account", bodyRequest)

        if (!asaasErpManager.isSuccessful()) {
            AsaasLogger.error("AsaasErpCustomerManagerService -> O seguinte erro foi retornado ao criar novo usuário [username: ${asaasErpCustomerCreateAdapter.name}]: ${asaasErpManager.getErrorMessage()}")
            throw new BusinessException(asaasErpManager.getErrorMessage())
        }

        CustomerResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((asaasErpManager.responseBody as JSON).toString(), CustomerResponseDTO)

        return [ apiKey: responseDTO.token, externalId: responseDTO.idUser ]
    }

    public void notifyDisabledCustomer(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        if (AsaasEnvironment.isDevelopment()) return

        String apiKey = asaasErpCustomerConfig.getDecryptedApiKey()
        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.post("/api/contracts/cancel-contract", [:])

        if (!asaasErpManager.isSuccessful()) {
            if (asaasErpManager.isErrorWithRetryEnabled()) throw new AsaasErpUndefinedErrorException("Ocorreu um erro ao cancelar a integração do cliente. [CustomerId: ${asaasErpCustomerConfig.customerId}].")

            String errorMessage = asaasErpManager.getErrorMessage()
            AsaasLogger.error("AsaasErpCustomerManagerService.notifyDisabledCustomer -> O seguinte erro ocorreu um erro ao cancelar a integração do cliente. [Erro: ${errorMessage}]")
            throw new RuntimeException(errorMessage)
        }
    }
}
