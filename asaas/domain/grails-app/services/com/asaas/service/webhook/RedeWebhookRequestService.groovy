package com.asaas.service.webhook

import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.integration.rede.creditcard.dto.webhook.RedeTransactionRefundedWebhookDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils
import com.asaas.webhook.WebhookRequestStatus
import grails.transaction.Transactional

@Transactional
class RedeWebhookRequestService {

    def webhookRequestService

    public void processRefundWebhookRequests() {
        Map queryParams = [:]
        queryParams.column = "id"
        queryParams.status = WebhookRequestStatus.PENDING
        queryParams.requestProvider = WebhookRequestProvider.REDE
        queryParams.requestType = WebhookRequestType.REDE_TRANSACTION_REFUNDED
        queryParams."dateCreated[le]" = CustomDateUtils.sumMinutes(new Date(), -5)

        List<Long> pendingWebhookRequestIdList = WebhookRequest.query(queryParams).list(max: 1000)

        for (Long id : pendingWebhookRequestIdList) {
            processRefundWebhookRequest(id)
        }
    }

    private void processRefundWebhookRequest(Long webhookRequestId) {
        Boolean done = false

        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            RedeTransactionRefundedWebhookDTO redeTransactionRefundedWebhookDTO = GsonBuilderUtils.buildClassFromJson((webhookRequest.requestBody), RedeTransactionRefundedWebhookDTO)

            if (redeTransactionRefundedWebhookDTO.Status == "Denied") {
                AsaasLogger.warn("RedeWebhookRequestService.processRefundWebhookRequest >>> Transacao [TID: ${redeTransactionRefundedWebhookDTO.tid}] não estornada. WebhookRequest [ID ${webhookRequestId}]")
            }

            webhookRequestService.setAsProcessed(webhookRequest)

            done = true
        }, [logErrorMessage: "RedeWebhookRequestService.processRefundWebhookRequest >>> Erro ao processar WebhookRequest [ID ${webhookRequestId}]"])

        if (done) return

        Utils.withNewTransactionAndRollbackOnError({
            webhookRequestService.setAsError(WebhookRequest.get(webhookRequestId))
        })
    }
}
