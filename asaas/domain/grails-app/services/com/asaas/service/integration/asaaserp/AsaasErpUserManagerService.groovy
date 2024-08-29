package com.asaas.service.integration.asaaserp

import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.asaaserp.AsaasErpUserConfig
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.asaaserp.adapter.user.AsaasErpCreateUserAdapter
import com.asaas.integration.asaaserp.adapter.user.AsaasErpUpdateUserAdapter
import com.asaas.integration.asaaserp.api.AsaasErpManager
import com.asaas.integration.asaaserp.dto.user.CreateUserRequestDTO
import com.asaas.integration.asaaserp.dto.user.CreateUserResponseDTO
import com.asaas.integration.asaaserp.dto.user.UpdateUserRequestDTO
import com.asaas.integration.asaaserp.dto.user.UpdateUserResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils

@Transactional
class AsaasErpUserManagerService {

    public AsaasErpCreateUserAdapter create(User user, String userAccessToken, Map asyncActionData) {
        if (AsaasEnvironment.isDevelopment()) return new AsaasErpCreateUserAdapter(new CreateUserResponseDTO(id: Long.valueOf(RandomStringUtils.randomNumeric(6)), tokenOriginal: UUID.randomUUID().toString()))

        CreateUserRequestDTO createdUserDTO = new CreateUserRequestDTO(asyncActionData, userAccessToken)
        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customerId: user.customerId]).get()
        String apiKey = asaasErpCustomerConfig.getDecryptedApiKey()

        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.apiUserIdentifier = asaasErpCustomerConfig.externalId
        asaasErpManager.post("/api/asaas/users", createdUserDTO.properties)

        if (!asaasErpManager.isSuccessful()) {
            AsaasLogger.error("AsaasErpUserManagerService.create >> O seguinte erro foi retornado ao criar novo usuário [${asyncActionData}]. ID Usuário de integração do cliente [${user.customerId}] no Asaas ERP: ${asaasErpCustomerConfig.externalId}: ${asaasErpManager.getErrorMessage()}")
            throw new BusinessException(asaasErpManager.getErrorMessage())
        }

        CreateUserResponseDTO createUserResponseDTO = GsonBuilderUtils.buildClassFromJson((asaasErpManager.responseBody as JSON).toString(), CreateUserResponseDTO)

        return new AsaasErpCreateUserAdapter(createUserResponseDTO)
    }

    public AsaasErpUpdateUserAdapter update(User user, Map asyncActionData) {
        if (AsaasEnvironment.isDevelopment()) return new AsaasErpUpdateUserAdapter(user)

        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customerId: user.customerId]).get()
        String apiKey = asaasErpCustomerConfig.getDecryptedApiKey()

        UpdateUserRequestDTO updatedUserRequestDTO = new UpdateUserRequestDTO(asyncActionData)
        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.apiUserIdentifier = asaasErpCustomerConfig.externalId

        Map userRequestPropertiesWithValue = updatedUserRequestDTO.properties.findAll { it.value != null }

        Long userExternalId = AsaasErpUserConfig.query([column: "externalId", user: user]).get()
        if (!userExternalId) throw new RuntimeException("AsaasErpUserManagerService.update >> Identificador externo do usuário no Asaas ERP não encontrado [ID: ${user.id} - ${user.username}]. ID Usuário de integração do cliente [${user.customerId}] no Asaas ERP: ${asaasErpCustomerConfig.externalId}")

        asaasErpManager.put("/api/asaas/users/${userExternalId}", userRequestPropertiesWithValue)

        if (!asaasErpManager.isSuccessful()) {
            AsaasLogger.error("AsaasErpUserManagerService.update >> O seguinte erro foi retornado ao alterar o usuário [#ID: ${user.id} - ${user.username}]: ${asaasErpManager.getErrorMessage()}")
            throw new BusinessException(asaasErpManager.getErrorMessage())
        }

        UpdateUserResponseDTO updatedUserResponseDTO = GsonBuilderUtils.buildClassFromJson((asaasErpManager.responseBody as JSON).toString(), UpdateUserResponseDTO)

        return new AsaasErpUpdateUserAdapter(updatedUserResponseDTO)
    }

    public void delete(User user, Long asaasErpCustomerConfigId) {
        if (AsaasEnvironment.isDevelopment()) return

        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.read(asaasErpCustomerConfigId)
        String apiKey = asaasErpCustomerConfig.getDecryptedApiKey()

        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.apiUserIdentifier = asaasErpCustomerConfig.externalId

        Long userExternalId = AsaasErpUserConfig.query([column: "externalId", user: user]).get()
        if (!userExternalId) throw new RuntimeException("AsaasErpUserManagerService.delete >> Identificador externo do usuário no Asaas ERP não encontrado [ID: ${user.id} - ${user.username}]. ID Usuário de integração do cliente [${user.customerId}] no Asaas ERP: ${asaasErpCustomerConfig.externalId}")

        asaasErpManager.delete("/api/asaas/users/${userExternalId}", null)

        if (!asaasErpManager.isSuccessful()) {
            AsaasLogger.error("AsaasErpUserManagerService.delete >> O seguinte erro foi retornado ao deletar o usuário [#ID: ${user.id} - ${user.username}]: ${asaasErpManager.getErrorMessage()}")
            throw new BusinessException(asaasErpManager.getErrorMessage())
        }
    }
}
