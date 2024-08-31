package com.asaas.service.integration.sauron

import com.asaas.integration.sauron.adapter.adminsensitivedataaccess.AdminSensitiveDataAccessAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.adminsensitivedataaccess.SauronSaveAdminSensitiveDataAccessRequestDTO
import com.asaas.utils.LoggerUtils

import grails.transaction.Transactional

@Transactional
class SauronAdminSensitiveDataAccessManagerService {

    public void saveList(List<AdminSensitiveDataAccessAdapter> adapterList) {
        List<SauronSaveAdminSensitiveDataAccessRequestDTO> dtoList = adapterList.collect { it -> new SauronSaveAdminSensitiveDataAccessRequestDTO(it) }

        SauronManager sauronManager = new SauronManager()
        sauronManager.post("/adminSensitiveDataAccesses/saveList", dtoList)

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("SauronAdminSensitiveDataAccessManagerService.saveList >> Erro ao salvar lista de eventos no Sauron. [StatusCode: ${sauronManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(sauronManager.responseBody)}]")
        }
    }
}
