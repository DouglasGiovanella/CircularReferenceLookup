package com.asaas.service.webhook

import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.integration.cielo.creditcard.adapter.TransactionInfoAdapter
import com.asaas.integration.cielo.creditcard.dto.webhook.CieloTransactionCapturedWebhookDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils
import com.asaas.webhook.WebhookRequestStatus
import grails.transaction.Transactional

@Transactional
class CieloWebhookRequestService {

    def cieloManagerService
    def webhookRequestService

    public void processPendingWebhookRequests() {
        Map queryParams = [
            column: "id",
            status: WebhookRequestStatus.PENDING,
            requestProvider: WebhookRequestProvider.CIELO,
            requestType: WebhookRequestType.CIELO_TRANSACTION_CAPTURED,
            "dateCreated[le]": CustomDateUtils.sumMinutes(new Date(), -5)
        ]

        List<Long> pendingWebhookRequestIdList = WebhookRequest.query(queryParams).list(max: 1000)

        if (!pendingWebhookRequestIdList) return

        List<Long> webhookRequestIdErrorList = []

        final Integer batchSize = 100
        final Integer flushEvery = 100

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(pendingWebhookRequestIdList, batchSize, flushEvery, { Long id ->
            try {
                processWebhookRequest(WebhookRequest.get(id))
                webhookRequestService.setAsProcessed(WebhookRequest.get(id))
            } catch (Exception exception) {
                webhookRequestIdErrorList.add(id)

                AsaasLogger.error("CieloWebhookRequestService.processPendingWebhookRequests >>> Erro ao processar o webhook [webhookRequestId: ${id}].", exception)

                throw exception
            }
        }, [logErrorMessage: "CieloWebhookRequestService.processPendingWebhookRequests >>> Erro ao processar os webhooks", appendBatchToLogErrorMessage: true])

        if (webhookRequestIdErrorList) setAsErrorInBatch(webhookRequestIdErrorList)
    }

    private void processWebhookRequest(WebhookRequest webhookRequest) {
        CieloTransactionCapturedWebhookDTO cieloTransactionCapturedWebhookDTO = GsonBuilderUtils.buildClassFromJson((webhookRequest.requestBody), CieloTransactionCapturedWebhookDTO)
        TransactionInfoAdapter transactionInfoAdapter = cieloManagerService.getTransactionInfo(cieloTransactionCapturedWebhookDTO.PaymentId)

        final Integer transactionCapturedStatus = 2
        if (!transactionInfoAdapter.success || transactionInfoAdapter.status != transactionCapturedStatus) return

        Boolean transactionFound = CreditCardAuthorizationInfo.query([transactionIdentifier: transactionInfoAdapter.transactionIdentifier, column: "id"]).get().asBoolean()

        if (!transactionFound) AsaasLogger.warn("Transacao com TID nao localizada. [webhookRequestId: ${webhookRequest.id} | transactionIdentifier: ${transactionInfoAdapter.transactionIdentifier} | merchantOrderId: ${transactionInfoAdapter.merchantOrderId} | paymentId: ${cieloTransactionCapturedWebhookDTO.PaymentId}]")
    }

    private void setAsErrorInBatch(List<Long> webhookRequestIdErrorList) {
        final Integer batchSize = 100
        final Integer flushEvery = 100

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(webhookRequestIdErrorList, batchSize, flushEvery, { Long id ->
            webhookRequestService.setAsError(WebhookRequest.get(id))
        }, [logErrorMessage: "CieloWebhookRequestService.setAsErrorInBatch >>> Erro ao setar os webhooks como erro.", appendBatchToLogErrorMessage: true])
    }
}
