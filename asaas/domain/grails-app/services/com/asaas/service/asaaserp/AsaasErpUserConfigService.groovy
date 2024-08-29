package com.asaas.service.asaaserp

import com.asaas.domain.asaaserp.AsaasErpUserConfig
import com.asaas.domain.user.User
import grails.transaction.Transactional

@Transactional
class AsaasErpUserConfigService {

    def crypterService

    public AsaasErpUserConfig save(User user, Long externalId, String apiKey) {
        AsaasErpUserConfig asaasErpUserConfig = new AsaasErpUserConfig()
        asaasErpUserConfig.user = user
        asaasErpUserConfig.externalId = externalId

        asaasErpUserConfig.save(failOnError: true)

        asaasErpUserConfig.apiKey = crypterService.encryptDomainProperty(asaasErpUserConfig, "apiKey", apiKey)

        asaasErpUserConfig = asaasErpUserConfig.save(failOnError: true)

        return asaasErpUserConfig
    }

    public AsaasErpUserConfig delete(AsaasErpUserConfig asaasErpUserConfig) {
        asaasErpUserConfig.deleted = true
        asaasErpUserConfig.save(failOnError: true)

        return asaasErpUserConfig
    }
}
