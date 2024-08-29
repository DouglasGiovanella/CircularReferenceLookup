package com.asaas.service.integration.jsdpb

import com.asaas.integration.jdspb.api.JdSpbManager
import com.asaas.integration.jdspb.api.builder.JdSpbTedRequestBuilder
import com.asaas.integration.jdspb.api.dto.JdSpbGetMessageResponseDTO
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class JdSpbTedManagerService {

    private final static String PATH = "/JDInterfaceCab/WS/JDSPB_WS_IntegraLegado.dll/soap/IJDSPBCAB"

    public JdSpbGetMessageResponseDTO receiveMessage() {
        JdSpbTedRequestBuilder jdSpbTedRequestBuilder = new JdSpbTedRequestBuilder()
        String requestBody = jdSpbTedRequestBuilder.buildReceiveMessageXmlRequestBody()

        JdSpbManager jdSpbManager = new JdSpbManager()
        jdSpbManager.post(PATH, requestBody)

        if (jdSpbManager.isSuccessful()) {
            return new JdSpbGetMessageResponseDTO(jdSpbManager, true)
        }

        AsaasLogger.error("JdSpbTedManagerService.receiveMessage() >> Ocorreu um erro ao receber a mensagem!")
        return null
    }


    public JdSpbGetMessageResponseDTO findMessageByExternalIdentifier(String externalIdentifier) {
        JdSpbTedRequestBuilder jdSpbTedRequestBuilder = new JdSpbTedRequestBuilder()
        String requestBody = jdSpbTedRequestBuilder.buildRetrieveMessageXmlRequestBody(externalIdentifier)

        JdSpbManager jdSpbManager = new JdSpbManager()
        jdSpbManager.post(PATH, requestBody)

        if (jdSpbManager.isSuccessful()) {
            return new JdSpbGetMessageResponseDTO(jdSpbManager, false)
        }

        AsaasLogger.error("JdSpbTedManagerService.findMessageByExternalIdentifier() >> Ocorreu um erro ao recuperar a mensagem! ExternalIdentifier: ${externalIdentifier}")
        return null
    }
}
