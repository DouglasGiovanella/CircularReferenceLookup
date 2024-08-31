package com.asaas.service.integration.sauron

import com.asaas.connectedaccountgroup.ConnectedAccountGroupListAdapter
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sauron.adapter.ConnectedAccountEventAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.SauronEventListDTO
import com.asaas.integration.sauron.dto.connectedaccountgroup.SauronGetConnectedAccountGroupListResponseDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class ConnectedAccountManagerService {

    public void saveList(List<ConnectedAccountEventAdapter> eventList) {
        SauronManager sauronManager = new SauronManager()
        sauronManager.post("/event/saveList", new SauronEventListDTO(eventList))

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("ConnectedAccountManagerService.saveList >> CustomerId: [${eventList.first().customerId}] Origem: [${eventList.first().eventData.origin}]")
        }
    }

    public ConnectedAccountGroupListAdapter getList(Map requestParams) {
        if (!AsaasEnvironment.isProduction()) {
            SauronGetConnectedAccountGroupListResponseDTO accountGroupListResponseDTO = new MockJsonUtils("sauron/ConnectedAccountManagerService/getList.json").buildMock(SauronGetConnectedAccountGroupListResponseDTO)
            return new ConnectedAccountGroupListAdapter(accountGroupListResponseDTO)
        }

        final Integer timeoutInMilliseconds = 30000
        SauronManager sauronManager = buildSauronManager()
        sauronManager.setTimeout(timeoutInMilliseconds)
        sauronManager.get("/accountInfoConnection/getConnectedAccountGroupList", requestParams)

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("ConnectedAccountManagerService.getList >> CustomerId: [${requestParams.accountId}]")
        }

        String responseBodyJson = (sauronManager.responseBody as JSON).toString()
        SauronGetConnectedAccountGroupListResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, SauronGetConnectedAccountGroupListResponseDTO)

        return new ConnectedAccountGroupListAdapter(responseDTO)
    }

    private SauronManager buildSauronManager() {
        final Integer timeout = 10000

        SauronManager sauronManager = new SauronManager()
        sauronManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        sauronManager.setTimeout(timeout)

        return sauronManager
    }
}
