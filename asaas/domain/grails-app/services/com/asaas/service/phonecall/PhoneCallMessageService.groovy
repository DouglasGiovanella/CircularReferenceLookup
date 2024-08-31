package com.asaas.service.phonecall

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.callcenter.phonecallrecord.adapter.NotFoundPhoneCallRecordAdapter
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class PhoneCallMessageService {

    def grailsApplication
    def messageService

    public void notifyScurraAboutRecordNotFound(List<NotFoundPhoneCallRecordAdapter> notFoundPhoneCallRecordAdapterList) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailSubject = "ASAAS | Identificada gravação telefônica que não foi sincronizada com o S3"

            String emailBody = """
            Favor verificar e providenciar o envio da gravação. Seguem as informações das gravações: <br><br>
            """

            for (NotFoundPhoneCallRecordAdapter notFoundPhoneCallRecordAdapter : notFoundPhoneCallRecordAdapterList) {
                emailBody += """
                    path: ${notFoundPhoneCallRecordAdapter.filepath} <br><br>
                    duration: ${notFoundPhoneCallRecordAdapter.duration} <br><br>
                    phoneNumber: ${notFoundPhoneCallRecordAdapter.phoneNumber} <br><br>
                    asteriskId: ${notFoundPhoneCallRecordAdapter.asteriskId} <br><br>
                    ---------------------------------------------------------------
                    <br><br>
                    """
            }

            emailBody += """
                    ASAAS.COM
                    """

            messageService.send(grailsApplication.config.asaas.devTeam.alert.callcenter.email, "suporte@scurra.com.br", null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("PhoneCallMessageService.notifyScurraAboutRecordNotFound >> Falha ao notificar Scurra sobre as gravações não encontradas", exception)
        }
    }
}
