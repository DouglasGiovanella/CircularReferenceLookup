package com.asaas.service.notification

import com.asaas.domain.notification.InstantTextMessage
import com.asaas.integration.instanttextmessage.adapter.InstantTextMessageAdapter
import grails.transaction.Transactional

@Transactional
class InstantTextMessageSenderService {

    def instantTextMessageTwilioWhatsAppManagerService

    public InstantTextMessageAdapter send(InstantTextMessage instantTextMessage) {
        if (!instantTextMessage.isWhatsAppMessage()) {
            throw new RuntimeException("Erro ao enviar mensagem instantânea, através de ${instantTextMessage.type.toString()}. Serviço de mensagem não suportado.")
        }

        InstantTextMessageAdapter instantTextMessageAdapter = instantTextMessageTwilioWhatsAppManagerService.sendNotification(instantTextMessage.message, instantTextMessage.fromPhoneNumber, instantTextMessage.toPhoneNumber)

        return instantTextMessageAdapter
    }
}
