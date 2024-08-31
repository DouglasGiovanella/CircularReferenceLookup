package com.asaas.service.webhook

import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import grails.transaction.Transactional

@Transactional
class VortxWebhookRequestService {

    def webhookRequestService

    public WebhookRequest save(Map json) {
        json = removeUnnecessaryInfoFromJson(json)
        WebhookRequest webhookRequest = webhookRequestService.save(json, WebhookRequestType.VORTX_GENERAL_CALLBACK, WebhookRequestProvider.VORTX)
        return webhookRequest
    }

    private Map removeUnnecessaryInfoFromJson(Map json) {
        if (json.containsKey("Termo")) json.remove("Termo")
        return json
    }
}
