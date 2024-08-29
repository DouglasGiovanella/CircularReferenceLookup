package com.asaas.service.webhook

import com.asaas.domain.customer.Customer
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.dto.webhook.PixWebhookEventDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixWebhookEventType
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import groovy.json.JsonSlurper

@Transactional
class PixWebhookRequestService {

    def customerAlertNotificationService
    def customerPixConfigService
    def mobilePushNotificationService
    def webhookRequestService

    public void processPendingPixEvents() {
         List<Long> pendingWebhookRequestIdList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.PIX, requestType: WebhookRequestType.PIX_EVENT, order: "asc", sort: "id"]).list(max: 50)

         for (Long webhookRequestId in pendingWebhookRequestIdList) {
             Utils.withNewTransactionAndRollbackOnError({
                 processPixEvent(webhookRequestId)
             }, [onError: { Exception exception ->
                 AsaasLogger.error("PixWebhookRequestService.processPendingPixEvent >> Erro ao processar Webhook com id [${webhookRequestId}]", exception)
                 setWebhookAsError(webhookRequestId)
             }])
         }
    }

    private void processPixEvent(Long webhookRequestId) {
        WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)

        PixWebhookEventDTO pixWebhookEventDTO = GsonBuilderUtils.buildClassFromJson(webhookRequest.requestBody, PixWebhookEventDTO)

        Customer customer = Customer.query([id: pixWebhookEventDTO.customerId]).get()

        switch (pixWebhookEventDTO.type) {
            case PixWebhookEventType.ADDRESS_KEY_ACTIVATED:
                processAddressKeyActivatedWebhook(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.ADDRESS_KEY_ACTIVATE_REFUSED:
                processAddressKeyActivateRefusedWebhook(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.ADDRESS_KEY_DELETED:
                processAddressKeyDeletedWebhook(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.CLAIM_DONE:
                processClaimDone(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.CLAIM_CANCELLED:
                processClaimCancelledWebhook(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.CLAIM_REQUEST_APPROVED:
                processClaimRequestApproved(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.CLAIM_REQUEST_REFUSED:
                processClaimRequestRefused(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.CLAIM_CANCELLED_BY_OWNER:
                processClaimCancelledByOwner(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.EXTERNAL_CLAIM_APPROVAL_SENT:
                processExternalClaimApprovalSent(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.EXTERNAL_CLAIM_APPROVAL_REFUSED:
                processExternalClaimApprovalRefused(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.EXTERNAL_CLAIM_CANCELLATION_SENT:
                processExternalClaimCancellationSent(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.EXTERNAL_CLAIM_CANCELLATION_REFUSED:
                processExternalClaimCancellationRefused(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.EXTERNAL_CLAIM_REQUESTED:
                processExternalClaimRequested(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.EXTERNAL_CLAIM_CANCELLED_BY_CLAIMER:
                processExternalClaimCancelledByClaimer(pixWebhookEventDTO, customer)
                break
            case PixWebhookEventType.CUSTOMER_CONFIG_UPDATED:
                processCustomerConfigUpdated(pixWebhookEventDTO, customer)
                break
            default:
                throw new BusinessException("Processamento para este PixWebhookEventRequestType não implementado.")
        }

        webhookRequestService.setAsProcessed(webhookRequest)
    }

    private void processCustomerConfigUpdated(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        Map pixConfig = new JsonSlurper().parseText(pixWebhookEventDTO.additionalInfo) as Map
        customerPixConfigService.update(pixConfig, customer)
    }

    private void processAddressKeyActivatedWebhook(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixAddressKeyActivated(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixAddressKeyActivated(pixWebhookEventDTO.pixKey, customer)
    }

    private void processAddressKeyActivateRefusedWebhook(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        String pixKey = pixWebhookEventDTO.pixKey ?: "aleatória"
        String reason = pixWebhookEventDTO.additionalInfo ?: "Por favor tente novamente."
        String description = Utils.getMessageProperty("alertNotification.PixAddressKeyActivateRefused.description", [pixKey, reason])

        customerAlertNotificationService.notifyPixAddressKeyActivateRefused(pixWebhookEventDTO.pixKey, customer, description)
        mobilePushNotificationService.notifyPixAddressKeyActivateRefused(pixWebhookEventDTO.pixKey, customer, description)
    }

    private void processAddressKeyDeletedWebhook(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixAddressKeyDeleted(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixAddressKeyDeleted(pixWebhookEventDTO.pixKey, customer)
    }

    private void processClaimDone(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixClaimDone(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixClaimDone(pixWebhookEventDTO.pixKey, customer)
    }

    private void processClaimCancelledWebhook(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        String description = Utils.getMessageProperty("alertNotification.PixAddressKeyClaimCancelled.description", [pixWebhookEventDTO.pixKey])

        customerAlertNotificationService.notifyPixClaimCancelled(pixWebhookEventDTO.pixKey, customer, description)
        mobilePushNotificationService.notifyPixClaimCancelled(pixWebhookEventDTO.pixKey, customer)
    }

    private void processClaimRequestApproved(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixClaimRequestApproved(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixClaimRequestApproved(pixWebhookEventDTO.pixKey, customer)
    }

    private void processClaimRequestRefused(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        String reason = pixWebhookEventDTO.additionalInfo ?: "Por favor tente novamente."
        String description = Utils.getMessageProperty("alertNotification.PixAddressKeyClaimRequestRefused.description", [pixWebhookEventDTO.pixKey, reason])

        customerAlertNotificationService.notifyPixClaimRequestRefused(pixWebhookEventDTO.pixKey, customer, description)
        mobilePushNotificationService.notifyPixClaimRequestRefused(pixWebhookEventDTO.pixKey, customer, description)
    }

    private void processClaimCancelledByOwner(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixClaimCancelledByOwner(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixClaimCancelledByOwner(pixWebhookEventDTO.pixKey, customer)
    }

    private void processExternalClaimApprovalSent(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixExternalClaimApprovalSent(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixExternalClaimApprovalSent(pixWebhookEventDTO.pixKey, customer)
    }

    private void processExternalClaimApprovalRefused(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        String reason = pixWebhookEventDTO.additionalInfo ?: "Por favor tente novamente."
        String description = Utils.getMessageProperty("alertNotification.PixAddressKeyExternalClaimApprovalRefused.description", [pixWebhookEventDTO.pixKey, reason])

        customerAlertNotificationService.notifyPixExternalClaimApprovalRefused(pixWebhookEventDTO.pixKey, customer, description)
        mobilePushNotificationService.notifyPixExternalClaimApprovalRefused(pixWebhookEventDTO.pixKey, customer, description)
    }

    private void processExternalClaimCancellationSent(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixExternalClaimCancellationSent(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixExternalClaimCancellationSent(pixWebhookEventDTO.pixKey, customer)
    }

    private void processExternalClaimCancellationRefused(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        String reason = pixWebhookEventDTO.additionalInfo ?: "Por favor tente novamente."
        String description = Utils.getMessageProperty("alertNotification.PixAddressKeyExternalClaimCancellationRefused.description", [pixWebhookEventDTO.pixKey, reason])

        customerAlertNotificationService.notifyPixExternalClaimCancellationRefused(pixWebhookEventDTO.pixKey, customer, description)
        mobilePushNotificationService.notifyPixExternalClaimCancellationRefused(pixWebhookEventDTO.pixKey, customer, description)
    }

    private void processExternalClaimRequested(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixExternalClaimRequested(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixExternalClaimRequested(pixWebhookEventDTO.pixKey, customer)
    }

    private void processExternalClaimCancelledByClaimer(PixWebhookEventDTO pixWebhookEventDTO, Customer customer) {
        customerAlertNotificationService.notifyPixExternalClaimCancelledByClaimer(pixWebhookEventDTO.pixKey, customer)
        mobilePushNotificationService.notifyPixExternalClaimCancelledByClaimer(pixWebhookEventDTO.pixKey, customer)
    }

    private void setWebhookAsError(Long webhookRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            webhookRequestService.setAsError(webhookRequest)
        })
    }
}
