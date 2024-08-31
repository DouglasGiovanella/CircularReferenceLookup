package com.asaas.service.notification

import com.asaas.integration.instanttextmessage.adapter.InstantTextMessageAdapter
import com.asaas.integration.instanttextmessage.twilio.adapter.TwilioWhatsAppNotificationDTO
import com.asaas.integration.instanttextmessage.twilio.adapter.TwilioWhatsAppNotificationFailedResponseDTO
import com.asaas.integration.instanttextmessage.twilio.adapter.TwilioWhatsAppNotificationResponseDTO
import com.asaas.integration.instanttextmessage.twilio.manager.TwilioWhatsAppManager
import com.asaas.integration.instanttextmessage.twilio.parser.TwilioWhatsAppNotificationErrorReasonParser
import com.asaas.notification.InstantTextMessageStatus
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.PhoneNumberUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class InstantTextMessageTwilioWhatsAppManagerService {

    def grailsApplication

    public InstantTextMessageAdapter sendNotification(String message, String fromPhoneNumber, String toPhoneNumber) {
        toPhoneNumber = PhoneNumberUtils.buildFullPhoneNumber(toPhoneNumber)
        TwilioWhatsAppNotificationDTO twilioWhatsAppNotificationDTO = new TwilioWhatsAppNotificationDTO(message, fromPhoneNumber, toPhoneNumber, buildStatusCallbackUrl())

        TwilioWhatsAppManager twilioWhatsAppManager = new TwilioWhatsAppManager()
        twilioWhatsAppManager.post(twilioWhatsAppNotificationDTO.toMap())

        InstantTextMessageAdapter instantTextMessageAdapter = buildInstantTextMessageAdapterFromResponse(twilioWhatsAppManager)

        return instantTextMessageAdapter
    }

    private InstantTextMessageAdapter buildInstantTextMessageAdapterFromResponse(TwilioWhatsAppManager twilioWhatsAppManager) {
        InstantTextMessageAdapter instantTextMessageAdapter = new InstantTextMessageAdapter()
        instantTextMessageAdapter.success = twilioWhatsAppManager.isSuccessful()

        if (twilioWhatsAppManager.isSuccessful()) {
            TwilioWhatsAppNotificationResponseDTO twilioWhatsAppNotificationResponseDTO = GsonBuilderUtils.buildClassFromJson((twilioWhatsAppManager.responseBody as JSON).toString(), TwilioWhatsAppNotificationResponseDTO)

            instantTextMessageAdapter.id = twilioWhatsAppNotificationResponseDTO.sid
            instantTextMessageAdapter.status = InstantTextMessageStatus.convert(twilioWhatsAppNotificationResponseDTO.status.toString().toUpperCase())
            instantTextMessageAdapter.errorMessage = twilioWhatsAppNotificationResponseDTO.error_message ?: twilioWhatsAppNotificationResponseDTO.error_code
        } else {
            TwilioWhatsAppNotificationFailedResponseDTO twilioWhatsAppNotificationFailedResponseDTO = GsonBuilderUtils.buildClassFromJson((twilioWhatsAppManager.responseBody as JSON).toString(), TwilioWhatsAppNotificationFailedResponseDTO)

            instantTextMessageAdapter.status = InstantTextMessageStatus.FAILED
            instantTextMessageAdapter.errorMessage = "${twilioWhatsAppNotificationFailedResponseDTO.code} - ${twilioWhatsAppNotificationFailedResponseDTO.message}"
            instantTextMessageAdapter.errorReason = TwilioWhatsAppNotificationErrorReasonParser.parse(twilioWhatsAppNotificationFailedResponseDTO.code?.toString())
        }

        return instantTextMessageAdapter
    }

    private String buildStatusCallbackUrl() {
        final String twilioWhatsAppAccountId = grailsApplication.config.twilio.whatsapp.account.sid

        String statusCallbackUrl = grailsApplication.config.twilio.whatsapp.sqs.statusCallbackUrl
        if (!statusCallbackUrl.endsWith("/")) statusCallbackUrl += "/"
        statusCallbackUrl += "?accountsid=${twilioWhatsAppAccountId}"

        return statusCallbackUrl
    }
}
