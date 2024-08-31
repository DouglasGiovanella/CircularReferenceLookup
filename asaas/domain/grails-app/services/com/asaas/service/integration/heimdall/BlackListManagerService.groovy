package com.asaas.service.integration.heimdall

import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.blacklist.UnscSanctionOccurrenceDTO
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BlackListManagerService {

    public UnscSanctionOccurrenceDTO getUnscSanctionOccurrence(Long id) {
        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get("/blackList/getUnscSanctionOccurrence/${id}", [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("Erro ao buscar ocorrência de sanção da UNSC. id ${id}, StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        UnscSanctionOccurrenceDTO unscSanctionOccurrenceDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, UnscSanctionOccurrenceDTO)
        return unscSanctionOccurrenceDTO
    }
}
