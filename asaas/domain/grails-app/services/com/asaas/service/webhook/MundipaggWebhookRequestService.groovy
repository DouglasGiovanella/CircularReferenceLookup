package com.asaas.service.webhook

import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.CreditCardTransactionAnalysis
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.webhook.WebhookRequestStatus
import grails.transaction.Transactional
import groovy.json.JsonSlurper

@Transactional
class MundipaggWebhookRequestService {

    def webhookRequestService

    public void processPendingWebhookRequests() {
        Map queryParams = [
                column: "id",
                statusList: [WebhookRequestStatus.PENDING],
                requestProvider: WebhookRequestProvider.MUNDIPAGG,
                requestType: WebhookRequestType.MUNDIPAGG_PAID_ORDER,
                "dateCreated[le]": CustomDateUtils.sumMinutes(new Date(), -5)
        ]

        List<Long> pendingWebhookRequestList = WebhookRequest.query(queryParams).list(max: 1000)

        for (Long id in pendingWebhookRequestList) {
            processWebhookRequest(id)
        }
    }

    private void processWebhookRequest(Long webhookRequestId) {
        AsaasLogger.info("MundipaggWebhookRequestService.processWebhookRequest -> Processando [ID ${webhookRequestId}]")

        Boolean result = true

        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

            JsonSlurper jsonSlurper = new JsonSlurper()
            Map webhookDataMap = jsonSlurper.parseText(webhookRequest.requestBody)
            String transactionIdentifier = webhookDataMap.data.charges[0].last_transaction.acquirer_tid
            String status = webhookDataMap.data.charges[0].last_transaction.status
            String chargeId = webhookDataMap.data.charges[0].id

            if (status == "captured") {
                CreditCardTransactionInfo creditCardTransactionInfo = CreditCardTransactionInfo.query([transactionIdentifier: transactionIdentifier]).get()
                CreditCardTransactionAnalysis creditCardTransactionAnalysis = CreditCardTransactionAnalysis.query([transactionIdentifier: transactionIdentifier]).get()

                if (!creditCardTransactionInfo && !creditCardTransactionAnalysis) {
                    AsaasLogger.warn("Transacao com TID[${transactionIdentifier}] nao localizada: Charge ID [${chargeId}]")
                }
            }

            webhookRequestService.setAsProcessed(webhookRequest)
        }, [onError: { Exception exception ->
            result = false

            Exception exceptionCause = exception.getCause() ?: exception
            String logMessage = "MundipaggWebhookRequestService.processWebhookRequest -> Erro ao processar WebhookRequest [ID ${webhookRequestId}]"

            if (exceptionCause instanceof BusinessException) {
                AsaasLogger.warn(logMessage, exceptionCause)
            } else {
                AsaasLogger.error(logMessage, exceptionCause)
            }
        }])

        if (result) return

        Utils.withNewTransactionAndRollbackOnError({
            webhookRequestService.setAsError(WebhookRequest.get(webhookRequestId))
        })
    }
}
