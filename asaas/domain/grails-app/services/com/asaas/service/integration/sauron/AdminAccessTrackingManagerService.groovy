package com.asaas.service.integration.sauron

import com.asaas.integration.sauron.adapter.adminaccesstracking.AdminAccessEventAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.SauronEventDTO
import grails.transaction.Transactional

@Transactional
class AdminAccessTrackingManagerService {

    public void save(AdminAccessEventAdapter adminAccessEventAdapter) {
        SauronManager sauronManager = new SauronManager()
        sauronManager.post("/event/save", new SauronEventDTO(adminAccessEventAdapter))

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("AdminAccessTrackingManagerService.save >> Erro ao enviar tracking de acesso administrativo StatusCode: [${sauronManager.statusCode}], ResponseBody: [${sauronManager.responseBody}]")
        }
    }
}
