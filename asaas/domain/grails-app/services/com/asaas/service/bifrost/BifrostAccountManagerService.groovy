package com.asaas.service.bifrost

import com.asaas.domain.asaascard.AsaasCardAgreement
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardAccountDisableRestrictionsAdapter
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostDisableRestrictionsResponseDTO
import com.asaas.integration.bifrost.dto.customer.BifrostSaveCardAgreementRequestDTO
import com.asaas.utils.DomainUtils
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostAccountManagerService {

    public AsaasCardAccountDisableRestrictionsAdapter validateToDisable(Long customerId) {
        if (AsaasEnvironment.isDevelopment()) return new AsaasCardAccountDisableRestrictionsAdapter(new BifrostDisableRestrictionsResponseDTO())

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/account/disableRestrictions", [customerId: customerId])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
        BifrostDisableRestrictionsResponseDTO bifrostDisableRestrictionsResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostDisableRestrictionsResponseDTO)

        return new AsaasCardAccountDisableRestrictionsAdapter(bifrostDisableRestrictionsResponseDTO)
    }

    public AsaasCardAgreement saveAsaasCardAgreement(AsaasCardAgreement asaasCardAgreement) {
        BifrostSaveCardAgreementRequestDTO bifrostSaveCardAgreementRequestDTO = new BifrostSaveCardAgreementRequestDTO(asaasCardAgreement)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/account/saveCardAgreement", bifrostSaveCardAgreementRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) asaasCardAgreement = DomainUtils.addError(asaasCardAgreement, "Erro ao salvar contrato")

        return asaasCardAgreement
    }
}
