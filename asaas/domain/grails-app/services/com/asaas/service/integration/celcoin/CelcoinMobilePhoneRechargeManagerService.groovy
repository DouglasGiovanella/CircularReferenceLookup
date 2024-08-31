package com.asaas.service.integration.celcoin

import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.exception.BusinessException
import com.asaas.integration.celcoin.adapter.mobilephonerecharge.providers.ProviderAdapter
import com.asaas.integration.celcoin.api.CelcoinManager
import com.asaas.integration.celcoin.dto.mobilephonerecharge.confirmation.ConfirmDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.confirmation.ConfirmationResponseDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.providers.ProviderDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.providers.ProviderResponseDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.providervalues.ProviderValuesDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.providervalues.ProviderValuesResponseDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.registration.RegisterDTO
import com.asaas.integration.celcoin.dto.mobilephonerecharge.registration.RegistrationResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.PhoneNumberUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CelcoinMobilePhoneRechargeManagerService {

    public ProviderAdapter findProvider(String phoneNumber) {
        List phoneNumberList = PhoneNumberUtils.splitAreaCodeAndNumber(phoneNumber)

        ProviderDTO providerDTO = new ProviderDTO(phoneNumberList[0], phoneNumberList[1])

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.get("/v5/transactions/topups/find-providers", providerDTO.properties)

        ProviderResponseDTO providerResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), ProviderResponseDTO)

        if (!celcoinManager.isSuccessful()) {
            final String providerNotFoundErrorCode = "682"

            if (providerResponseDTO?.errorCode == providerNotFoundErrorCode) throw new BusinessException('Provedor não encontrado para este número.')

            throw new BusinessException('Falha ao consultar o provedor deste número.')
        }

        ProviderValuesResponseDTO providerValuesResponseDTO = findProviderValues(phoneNumberList[0], providerResponseDTO.providerId)

        return new ProviderAdapter(providerResponseDTO, providerValuesResponseDTO)
    }

    public String processRecharge(MobilePhoneRecharge mobilePhoneRecharge) {
        ProviderAdapter providerAdapter = findProvider(mobilePhoneRecharge.phoneNumber)

        String transactionId = register(mobilePhoneRecharge, providerAdapter.id)

        confirm(mobilePhoneRecharge.id, transactionId)

        return transactionId
    }

    private ProviderValuesResponseDTO findProviderValues(String areaCode, Integer providerId) {
        ProviderValuesDTO providerValuesDTO = new ProviderValuesDTO(areaCode, providerId)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.get("/v5/transactions/topups/provider-values", providerValuesDTO.properties)

        if (!celcoinManager.isSuccessful()) throw new BusinessException('Erro ao consultar os valores disponíveis para este provedor.')

        ProviderValuesResponseDTO providerValuesResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), ProviderValuesResponseDTO)

        return providerValuesResponseDTO
    }

    private String register(MobilePhoneRecharge mobilePhoneRecharge, String providerId) {
        RegisterDTO registerDTO = new RegisterDTO(mobilePhoneRecharge, providerId)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.post("/v5/transactions/topups", registerDTO.properties)

        RegistrationResponseDTO registrationResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), RegistrationResponseDTO)

        if (!celcoinManager.isSuccessful()) {
            AsaasLogger.error("CelcoinMobilePhoneRechargeManagerService.register >>> Falha ao cadastrar a recarga de celular [${mobilePhoneRecharge.id}] | Erro: ${registrationResponseDTO?.message}")
            throw new BusinessException("Não foi possível registrar a recarga para processamento.")
        }

        return registrationResponseDTO.transactionId
    }

    private void confirm(Long mobilePhoneRechargeId, String transactionId) {
        ConfirmDTO confirmDTO = new ConfirmDTO(mobilePhoneRechargeId)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.put("/v5/transactions/topups/${transactionId}/capture", confirmDTO.properties)

        ConfirmationResponseDTO confirmationResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), ConfirmationResponseDTO)

        if (!celcoinManager.isSuccessful()) {
            AsaasLogger.error("CelcoinMobilePhoneRechargeManagerService.confirm >>> Falha ao confirmar a recarga de celular [${mobilePhoneRechargeId}] | Erro: ${confirmationResponseDTO?.message}")
            throw new BusinessException("Não foi possível confirmar a recarga.")
        }
    }
}
