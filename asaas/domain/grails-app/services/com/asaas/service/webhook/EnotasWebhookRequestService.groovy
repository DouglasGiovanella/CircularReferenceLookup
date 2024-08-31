package com.asaas.service.webhook

import com.asaas.domain.invoice.Invoice
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.integration.enotas.parser.EnotasInvoiceWebhookRequestParser
import com.asaas.integration.invoice.api.vo.WebhookRequestInvoiceVO
import com.asaas.invoice.InvoiceStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.asaas.webhook.WebhookRequestStatus
import grails.transaction.Transactional

@Transactional
class EnotasWebhookRequestService {

    def invoiceAuthorizationRequestService
    def webhookRequestService

    public void processPendingWebhookRequests() {
        final Integer maximunNumberOfWebhooks = 200
        List<Long> pendingWebhookRequestList = WebhookRequest.query([column: "id", statusList: [WebhookRequestStatus.PENDING], requestProvider: WebhookRequestProvider.ENOTAS]).list(max: maximunNumberOfWebhooks)
        for (Long id in pendingWebhookRequestList) {
            processWebhookRequest(id)
        }
    }

    public reprocessWebhookWithError(Long webhookRequestId) {
        WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

        if (!webhookRequest || !webhookRequest.requestBody) throw new Exception("O WebhookRequest é inválido.")
        if (!webhookRequest.status.isError()) throw new Exception("O WebhookRequest não está com erro.")
        if (!webhookRequest.requestProvider.isEnotas() || !webhookRequest.requestType.isEnotasInvoiceResult()) throw new Exception("O WebhookRequest informado não é do tipo correto.")

        Invoice invoice = Invoice.get(EnotasInvoiceWebhookRequestParser.parse(webhookRequest.requestBody).invoiceId)
        if (!invoice.status.isError() && !invoice.status.isSynchronized()) throw new Exception("A nota fiscal precisa estar com o status ERROR ou SYNCHRONIZED.")

        if (invoice.status.isError()) {
            invoice.status = InvoiceStatus.SYNCHRONIZED
            invoice.save(failOnError: true, flush: true)
        }

        webhookRequestService.setAsPending(webhookRequest)

        processWebhookRequest(webhookRequest.id)
    }

    private void processWebhookRequest(Long webhookRequestId){
        WebhookRequestInvoiceVO webhookRequestInvoiceVO

        Boolean result = true

        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            if (!webhookRequest?.requestBody) throw new Exception("WebhookRequest with [${webhookRequestId}] not found!")
            webhookRequestInvoiceVO = EnotasInvoiceWebhookRequestParser.parse(webhookRequest.requestBody)

            Long invoiceId = Utils.toLong(webhookRequestInvoiceVO.invoiceId)

            if (!invoiceId) {
                webhookRequestService.setAsIgnored(webhookRequest)
                return
            }

            Invoice invoice = Invoice.get(invoiceId)
            if (webhookRequestInvoiceVO.status == "Cancelada" && !invoice.status.isProcessingCancellation() && !invoice.status.isCancellationDenied()) {
                AsaasLogger.warn("${this.class.simpleName}.processWebhookRequest >> A nota fiscal de ID ${invoice.id} teve cancelamento autorizado mas não está cancelada no Asaas. Deveria estar com status ${InvoiceStatus.PROCESSING_CANCELLATION} mas está com status ${invoice.status}. Verifique o retorno WebhookRequest com ID [${webhookRequestId}]")
                webhookRequestService.setAsError(webhookRequest)
                return
            }

            invoiceAuthorizationRequestService.save(webhookRequestInvoiceVO, null)

            webhookRequestService.setAsProcessed(webhookRequest)
        }, [onError: { Exception exception ->
            result = false
            AsaasLogger.error("${this.class.simpleName}.processWebhookRequest >> Erro ao processar WebhookRequest [Id. ${webhookRequestId}]", exception)
        }, ignoreStackTrace: true])

        if (result) return

        Utils.withNewTransactionAndRollbackOnError({
            webhookRequestService.setAsError(WebhookRequest.get(webhookRequestId))
        })
    }
}
