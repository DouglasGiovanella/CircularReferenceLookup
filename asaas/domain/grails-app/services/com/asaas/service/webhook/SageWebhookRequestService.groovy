package com.asaas.service.webhook

import com.asaas.customerreceivableanticipationconfig.adapter.UpdateCreditCardLimitAdapter
import com.asaas.customerreceivableanticipationconfig.adapter.UpdateCreditCardPercentageLimitAdapter
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.integration.sage.adapter.ReceivableAnticipationCreditPolicyAnalysisAdapter
import com.asaas.integration.sage.dto.SageProcessCreditPolicyAnalysisRequestDTO
import com.asaas.integration.sage.dto.webhook.SageBaseWebhookEventDTO
import com.asaas.integration.sage.dto.webhook.SageWebhookEventType
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class SageWebhookRequestService {

    def customerReceivableAnticipationConfigService
    def webhookRequestService

    public void processPendingSageEvents() {
        final Integer maxItemsByExecution = 100
        List<Long> webhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.SAGE, requestType: WebhookRequestType.SAGE_EVENT]).list(max: maxItemsByExecution)

        for (Long webhookRequestId : webhookRequestIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                processSageEvent(WebhookRequest.get(webhookRequestId))
            }, [logErrorMessage: "SageWebhookRequestService.processPendingSageEvents -> Não foi possível processar o webhook ${webhookRequestId}",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    webhookRequestService.setAsError(WebhookRequest.get(webhookRequestId))
                }, [logErrorMessage: "SageWebhookRequestService.processPendingSageEvents -> Não foi possível marcar o webhook ${webhookRequestId} como erro"])
            }
        }
    }

    private void processSageEvent(WebhookRequest webhookRequest) {
        SageBaseWebhookEventDTO webhookDTO = GsonBuilderUtils.buildClassFromJson(webhookRequest.requestBody, SageBaseWebhookEventDTO)
        if (!webhookDTO.event) throw new NotImplementedException("Não foi possível identificar o tipo de evento do webhook ${webhookRequest.id}")

        switch (webhookDTO.event) {
            case SageWebhookEventType.ACCOUNT_RECEIVABLE_ANTICIPATION_CONFIG_UPDATED:
                processReceivableAnticipationConfigUpdatedEvent(webhookRequest.requestBody)
                break
            default:
                throw new NotImplementedException("Não foi implementado processamento para o evento do tipo [${webhookDTO.event}]")
        }

        webhookRequestService.setAsProcessed(webhookRequest)
    }

    private void processReceivableAnticipationConfigUpdatedEvent(String requestBody) {
        SageProcessCreditPolicyAnalysisRequestDTO requestDTO = GsonBuilderUtils.buildClassFromJson(requestBody, SageProcessCreditPolicyAnalysisRequestDTO)
        ReceivableAnticipationCreditPolicyAnalysisAdapter adapter = new ReceivableAnticipationCreditPolicyAnalysisAdapter(requestDTO)

        if (adapter.analysis.isPercentageLimitUpdate) {
            UpdateCreditCardPercentageLimitAdapter updateCreditCardPercentageAdapter = new UpdateCreditCardPercentageLimitAdapter(adapter)

            customerReceivableAnticipationConfigService.updateCreditCardPercentage(updateCreditCardPercentageAdapter)
            customerReceivableAnticipationConfigService.toggleUseAccountOwnerCreditCardPercentage(adapter.customer, adapter.analysis.useAccountOwnerCreditCardPercentageEnabled)
        } else {
            UpdateCreditCardLimitAdapter updateCreditCardLimitAdapter = new UpdateCreditCardLimitAdapter(adapter)
            customerReceivableAnticipationConfigService.updateCreditCardAnticipationLimit(updateCreditCardLimitAdapter)
            customerReceivableAnticipationConfigService.toggleSharedCreditCardLimit(adapter.customer, adapter.analysis.sharedCreditCardLimitEnabled)
        }
    }
}
