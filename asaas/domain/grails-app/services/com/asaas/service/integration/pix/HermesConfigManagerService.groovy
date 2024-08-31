package com.asaas.service.integration.pix

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.config.HermesGetConfigResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.config.HermesConfigAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HermesConfigManagerService {

    public HermesConfigAdapter get() {
        if (AsaasEnvironment.isDevelopment()) return new HermesConfigAdapter(new MockJsonUtils("pix/HermesConfigManagerService/getConfig.json").buildMock(HermesGetConfigResponseDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.timeout = 5000
        hermesManager.get("/config", null)

        if (hermesManager.isSuccessful()) {
            HermesGetConfigResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetConfigResponseDTO)
            return new HermesConfigAdapter(responseDto)
        }

        AsaasLogger.error("HermesConfigManagerService.get >> Erro ao buscar as configurações do Hermes")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

}
