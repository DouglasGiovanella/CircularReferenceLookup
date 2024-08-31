package com.asaas.service.message

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestAttempt
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestConfig
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@Transactional
@GrailsCompileStatic
class CustomerExternalAuthorizationRequestMessageService {

    MessageService messageService

    public void sendMaxAttemptsExceededMessage(CustomerExternalAuthorizationRequestConfig config, CustomerExternalAuthorizationRequestAttempt attempt) {
        try {
            String configType = Utils.getMessageProperty("customerExternalAuthorizationRequestConfigType.${config.type}")
            String emailSubject = "Erro na tentativa de saque ${configType} via Webhook"
            String emailBody = messageService.buildTemplate("/mailTemplate/customerExternalAuthorization/customerExternalAuthorizationMaxAttemptsExceeded", [
                customerEmail: config.email,
                configType: configType,
                details: attempt
            ])
            String fromEmail = AsaasApplicationHolder.getConfigValue("asaas.sender")
            messageService.sendDefaultTemplate(fromEmail, config.email, config.customer.name, emailSubject, emailBody)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerExternalAuthorizationRequestMessageService.sendMaxAttemptsExceededMessage >> Erro ao enviar email para o cliente ID: [${config.customer.id}]", exception)
        }
    }

}
