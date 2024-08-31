package com.asaas.service.message.correios

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.service.message.MessageService

import grails.transaction.Transactional
import grails.util.Environment

@Transactional
class CorreiosMessageService extends MessageService {

	public void sendNotificationAboutFileSentToCorreiosFtp(String fileName, Integer totalOfItens) {
		Payment.withNewTransaction { transaction ->
			try {
				if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

				String emailBody = buildTemplate("/mailTemplate/notificationAboutFileSentToCorreiosFtp", [fileName: fileName, totalOfItens: totalOfItens])

				send(AsaasApplicationHolder.config.asaas.sender, AsaasApplicationHolder.config.asaas.info.email, ["remessas.correios@asaas.com.br"], "Asaas: Arquivo remessa enviado para o FTP", emailBody, true)
			} catch (Exception exception) {
                AsaasLogger.error("CorreiosMessageService.sendNotificationAboutFileSentToCorreiosFtp >> Erro ao enviar notificação. [fileName: ${fileName}]", exception)
			}
		}
	}
}
