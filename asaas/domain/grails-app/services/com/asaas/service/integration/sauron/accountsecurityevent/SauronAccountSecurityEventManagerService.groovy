package com.asaas.service.integration.sauron.accountsecurityevent

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sauron.adapter.accountsecurityevent.AccountSecurityEventRequestAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.accountsecurityevent.SauronSaveAccountSecurityEventRequestDTO
import grails.transaction.Transactional

@Transactional
class SauronAccountSecurityEventManagerService {

    public void saveList(List<AccountSecurityEventRequestAdapter> accountSecurityEventRequestAdapterList) {
        if (!AsaasEnvironment.isProduction()) return

        List<SauronSaveAccountSecurityEventRequestDTO> requestDTOList = accountSecurityEventRequestAdapterList.collect { new SauronSaveAccountSecurityEventRequestDTO(it) }

        SauronManager sauronManager = buildSauronManager()
        sauronManager.post("/accountSecurityEvents/saveList", requestDTOList)

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("SauronAccountSecurityEventManagerService.saveList >> Ocorreu um erro no Sauron ao tentar salvar os eventos de segurança das contas. ResponseBody: [${sauronManager.responseBody}].")
        }
    }

    private SauronManager buildSauronManager() {
        final Integer timeout = 10000

        SauronManager sauronManager = new SauronManager()
        sauronManager.setTimeout(timeout)

        return sauronManager
    }
}
