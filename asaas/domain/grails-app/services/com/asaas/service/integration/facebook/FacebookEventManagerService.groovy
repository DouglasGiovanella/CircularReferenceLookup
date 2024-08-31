package com.asaas.service.integration.facebook

import com.asaas.integration.facebook.api.FacebookGraphManager
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class FacebookEventManagerService {

    private static final String WEB_ACTION_SOURCE = "website"

    public Boolean sendEvent(String email, String eventName, String externalId, Map properties) {
        try {
            Map params = parseParams(email, eventName, externalId, properties)

            FacebookGraphManager facebookGraphManager = new FacebookGraphManager()
            facebookGraphManager.post(params)

            if (!facebookGraphManager.isSuccessful()) AsaasLogger.error("FacebookEventManagerService.sendEvent -> Falha na requisição POST. Email: [${email}] eventName [${eventName}] ExternalId [${externalId}] Status: [${facebookGraphManager.statusCode}] Body: [${facebookGraphManager.responseBody}] ErrorMessage: [${facebookGraphManager.errorMessage}]")

            return facebookGraphManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("FacebookEventManagerService.sendEvent -> Erro ao marcar o identificador Email: [${email}] eventName [${eventName}] ExternalId ${externalId}", e)
            return false
        }
    }

    private Map parseParams(String email, String eventName, String externalId, Map properties) {
        Map userData = [
            "country": "br".encodeAsSHA256(),
            "em": email.encodeAsSHA256(),
            "external_id": externalId.encodeAsSHA256()
        ]

        if (properties.fbc) userData.fbc = properties.fbc
        if (properties.fbp) userData.fbp = properties.fbp
        if (properties.requestIp) userData."client_ip_address" = properties.requestIp
        if (properties.userAgent) userData."client_user_agent" = properties.userAgent

        Map eventData = [
            "action_source": WEB_ACTION_SOURCE,
            "event_name": eventName,
            "event_time": properties.timestampDate,
            "event_id": UUID.randomUUID(),
            "user_data": userData
        ]

        if (properties.eventSourceUrl) eventData."event_source_url" = properties.eventSourceUrl

        Map params = [
            "data": [eventData]
        ]

        return params
    }
}
