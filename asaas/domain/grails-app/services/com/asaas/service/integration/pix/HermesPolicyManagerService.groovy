package com.asaas.service.integration.pix

import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.policy.HermesGetPolicyResponseDTO
import com.asaas.integration.pix.enums.policy.PolicyType
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.policy.PolicyAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HermesPolicyManagerService {

    public PolicyAdapter get(PolicyType type) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.get("/policies/${type.toString()}", [:])

        if (hermesManager.isSuccessful()) {
            HermesGetPolicyResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetPolicyResponseDTO) as HermesGetPolicyResponseDTO
            return new PolicyAdapter(responseDto)
        }

        AsaasLogger.error("HermesPolicyManagerService.get() -> Erro ao buscar política [policyType: ${type}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }
}
