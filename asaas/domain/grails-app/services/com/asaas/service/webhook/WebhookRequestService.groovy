package com.asaas.service.webhook

import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.log.AsaasLogger
import com.asaas.webhook.WebhookRequestStatus
import grails.transaction.Transactional

import static groovy.json.JsonOutput.toJson

@Transactional
class WebhookRequestService {

    def messageService

    public WebhookRequest save(Map json, WebhookRequestType requestType, WebhookRequestProvider requestProvider) {
        try {
            return save(requestType, requestProvider, json ? toJson(json) : null)
        } catch (Exception e) {
            AsaasLogger.info("WebhookRequestService.save >> Error creating WebhookRequest for Provider [${requestProvider}], Type [${requestType}]")
            throw e
        }
    }

	public WebhookRequest save(WebhookRequestType requestType, WebhookRequestProvider requestProvider, String requestJson) {
		AsaasLogger.info(">> Receiving WebhookRequest from [${requestProvider}] API => ${requestType} API")

        WebhookRequest webhookRequest = new WebhookRequest()
        webhookRequest.requestType = requestType
        webhookRequest.requestProvider = requestProvider
        webhookRequest.requestBody = requestJson
        webhookRequest.status = WebhookRequestStatus.PENDING

		return webhookRequest.save(flush: true, failOnError: true)
    }

    public void setAsPending(WebhookRequest webhookRequest) {
        setStatus(webhookRequest, WebhookRequestStatus.PENDING)
    }

    public void setAsProcessed(WebhookRequest webhookRequest) {
        setStatus(webhookRequest, WebhookRequestStatus.PROCESSED)
    }

    public void setAsError(WebhookRequest webhookRequest) {
        setStatus(webhookRequest, WebhookRequestStatus.ERROR)
    }

    public void setAsIgnored(WebhookRequest webhookRequest) {
        setStatus(webhookRequest, WebhookRequestStatus.IGNORED)
    }

    public void updateStatusInBatch(List<Long> webhookRequestIdList, WebhookRequestStatus status) {
        if (!webhookRequestIdList) return

        Map updateParams = [lastUpdated: new Date(), idList: webhookRequestIdList, status: status]
        WebhookRequest.executeUpdate("UPDATE WebhookRequest SET version = version + 1, lastUpdated = :lastUpdated, status = :status WHERE  id IN (:idList)", updateParams)
    }

    private void setStatus(WebhookRequest webhookRequest, WebhookRequestStatus status) {
        webhookRequest.status = status
        webhookRequest.save(failOnError: true)
    }
}
