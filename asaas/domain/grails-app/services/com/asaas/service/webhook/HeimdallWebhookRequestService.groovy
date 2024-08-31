package com.asaas.service.webhook

import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.webhook.WebhookRequestStatus
import grails.converters.JSON
import grails.transaction.Transactional
import org.codehaus.groovy.grails.web.json.JSONObject

@Transactional
class HeimdallWebhookRequestService {

    def accountDocumentAnalysisApprovalService
    def facematchCriticalActionService
    def identificationDocumentAnalysisApprovalService
    def revenueServiceRegisterService
    def sanctionOccurrenceService
    def webhookRequestService

    public void processPendingAccountDocumentAnalysisResultWebhookRequests() {
        try {
            List<Long> pendingWebhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.HEIMDALL, requestType: WebhookRequestType.HEIMDALL_ACCOUNT_DOCUMENT_ANALYSIS_RESULT]).list(max: 200)

            for (Long webhookRequestId in pendingWebhookRequestIdList) {
                processPendingAccountDocumentAnalysisResultWebhookRequestWithNewTransaction(webhookRequestId)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar webhook processReceivingLimitAnalysisResult do Heimdall", exception)
        }
    }

    public void processPendingIdentificationDocumentAnalysisResultWebhookRequests() {
        try {
            List<Long> pendingWebhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.HEIMDALL, requestType: WebhookRequestType.HEIMDALL_IDENTIFICATION_DOCUMENT_ANALYSIS_RESULT]).list(max: 200)

            for (Long webhookRequestId in pendingWebhookRequestIdList) {
                processPendingIdentificationDocumentAnalysisResultWebhookRequestWithNewTransaction(webhookRequestId)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar webhook processPendingIdentificationDocumentAnalysisResult do Heimdall", exception)
        }
    }

    public void processPendingUnscSanctionOccurrenceWebhookRequestList() {
        try {
            List<Long> pendingWebhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.HEIMDALL, requestType: WebhookRequestType.HEIMDALL_UNSC_SANCTION_OCCURENCE]).list(max: 200)

            for (Long webhookRequestId in pendingWebhookRequestIdList) {
                processPendingUnscSanctionOccurrenceWebhookRequestWithNewTransaction(webhookRequestId)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar webhook processReceivingLimitAnalysisResult do Heimdall", exception)
        }
    }

    public void processPendingRevenueServiceRegisterSaveWebhookRequest() {
        final Integer maxItemsPerCycle = 200

        Map searchParams = [column: "id", requestProvider: WebhookRequestProvider.HEIMDALL, requestType: WebhookRequestType.HEIMDALL_REVENUE_SERVICE_REGISTER_SAVE]
        List<Long> pendingWebhookRequestIdList = WebhookRequest.pending(searchParams).list(max: maxItemsPerCycle)
        if (!pendingWebhookRequestIdList) return

        List<Long> processedIdList = []
        List<Long> errorIdList = []
        for (Long webhookRequestId : pendingWebhookRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

                JSONObject jsonObject = JSON.parse(webhookRequest.requestBody)
                String cpfCnpj = jsonObject.get("cpfCnpj")

                RevenueServiceRegister revenueServiceRegister = revenueServiceRegisterService.findPerson(cpfCnpj, RevenueServiceRegisterCacheLevel.NORMAL)
                if (revenueServiceRegister.hasErrors()) {
                    AsaasLogger.warn("HeimdallWebhookRequestService.processPendingRevenueServiceRegisterSaveWebhookRequest >> Erro de validação ao processar webhook [${webhookRequestId}]. Validação [${DomainUtils.getFirstValidationMessage(revenueServiceRegister)}]")
                    errorIdList.add(webhookRequestId)
                    return
                }

                processedIdList.add(webhookRequestId)
            }, [onError: { Exception exception ->
                AsaasLogger.error("HeimdallWebhookRequestService.processPendingRevenueServiceRegisterSaveWebhookRequest >> erro ao processar Webhook de RevenueServiceRegisterSave com id [${webhookRequestId}]", exception)
                errorIdList.add(webhookRequestId)
            }])
        }

        webhookRequestService.updateStatusInBatch(processedIdList, WebhookRequestStatus.PROCESSED)
        webhookRequestService.updateStatusInBatch(errorIdList, WebhookRequestStatus.ERROR)
    }

    public void processPendingFacematchValidationResultWebhookRequestList() {
        final Integer maxItemsPerCycle = 200
        try {
            List<Long> pendingWebhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.HEIMDALL, requestType: WebhookRequestType.HEIMDALL_FACEMATCH_VALIDATION_RESULT]).list(max: maxItemsPerCycle)

            for (Long webhookRequestId : pendingWebhookRequestIdList) {
                processPendingFacematchValidationResultWebhookRequestWithNewTransaction(webhookRequestId)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar webhook facematchValidationResult do Heimdall", exception)
        }
    }

    private void processPendingIdentificationDocumentAnalysisResultWebhookRequestWithNewTransaction(Long webhookRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

            JSONObject jsonObject = JSON.parse(webhookRequest.requestBody)
            Long identificationDocumentAnalysisId = jsonObject.get("id")

            identificationDocumentAnalysisApprovalService.process(identificationDocumentAnalysisId)

            webhookRequestService.setAsProcessed(webhookRequest)
        }, [onError: { Exception exception ->
            AsaasLogger.error("Erro ao processar Webhook de IdentificationDocumentAnalysisResult com id [${webhookRequestId}]", exception)
            setWebhookAsError(webhookRequestId)
        }])
    }

    private void processPendingAccountDocumentAnalysisResultWebhookRequestWithNewTransaction(Long webhookRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

            JSONObject jsonObject = JSON.parse(webhookRequest.requestBody)
            Long accountDocumentAnalysisId = jsonObject.get("id")

            accountDocumentAnalysisApprovalService.process(accountDocumentAnalysisId)

            webhookRequestService.setAsProcessed(webhookRequest)
        }, [onError: { Exception exception ->
            AsaasLogger.error("Erro ao processar Webhook de AccountDocumentAnalysisResult com id [${webhookRequestId}]", exception)
            setWebhookAsError(webhookRequestId)
        }])
    }

    private void processPendingUnscSanctionOccurrenceWebhookRequestWithNewTransaction(Long webhookRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

            JSONObject jsonObject = JSON.parse(webhookRequest.requestBody)
            Long unscSanctionOccurrenceId = jsonObject.get("id")

            sanctionOccurrenceService.processCustomerFoundInUnsc(unscSanctionOccurrenceId)

            webhookRequestService.setAsProcessed(webhookRequest)
        }, [onError: { Exception exception ->
            AsaasLogger.error("Erro ao processar Webhook de UnscSanctionOccurrence com id [${webhookRequestId}]", exception)
            setWebhookAsError(webhookRequestId)
        }])
    }

    private void processPendingFacematchValidationResultWebhookRequestWithNewTransaction(Long webhookRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

            JSONObject jsonObject = JSON.parse(webhookRequest.requestBody)
            Long facematchCriticalActionId = jsonObject.getLong("externalOriginEventId")
            Boolean isApproved = jsonObject.getBoolean("isApproved")

            facematchCriticalActionService.processExternalValidation(facematchCriticalActionId, isApproved)

            webhookRequestService.setAsProcessed(webhookRequest)
        }, [onError: { Exception exception ->
            AsaasLogger.error("Erro ao processar Webhook de FacematchValidationResult com id [${webhookRequestId}]", exception)
            setWebhookAsError(webhookRequestId)
        }])
    }

    private void setWebhookAsError(Long webhookRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            webhookRequestService.setAsError(webhookRequest)
        })
    }
}
