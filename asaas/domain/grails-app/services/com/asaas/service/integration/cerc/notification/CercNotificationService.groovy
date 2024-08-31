package com.asaas.service.integration.cerc.notification

import com.asaas.domain.integration.cerc.notification.CercNotification
import com.asaas.integration.cerc.adapter.notification.CercNotificationAdapter
import com.asaas.integration.cerc.adapter.optin.CercOptInAdapter
import com.asaas.integration.cerc.enums.webhook.CercNotificationType
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional
import grails.validation.ValidationException
import org.apache.commons.lang.NotImplementedException

@Transactional
class CercNotificationService {

    def cercOptInService

    public void process(Map event) {
        CercNotificationAdapter notificationAdapter = new CercNotificationAdapter(event)

        if (notificationAdapter.notificationType.isOptIn()) {
            CercOptInAdapter cercOptInAdapter = new CercOptInAdapter(notificationAdapter.message, notificationAdapter.protocol)
            Boolean hasAllOptIn = cercOptInService.hasAllOptIn(cercOptInAdapter)
            if (hasAllOptIn) {
                AsaasLogger.info("CercNotificationService.process >> Todos OPT-In já existem")
                return
            }
        }

        CercNotification cercNotification = save(notificationAdapter)
        if (cercNotification.hasErrors()) throw new ValidationException("CercNotificationService.process > Falha ao salvar notificação", cercNotification.errors)

        switch (cercNotification.notificationType) {
            case CercNotificationType.AFTER_CONTRACTED:
            case CercNotificationType.CONTRACTUAL_EFFECT_APPLIED:
            case CercNotificationType.OPT_OUT:
                AsaasLogger.info("CercNotificationService.process >> Notificação do tipo [${cercNotification.notificationType}] foi recebida")
                break
            case CercNotificationType.OPT_IN:
                CercOptInAdapter optInAdapter = new CercOptInAdapter(notificationAdapter.message, notificationAdapter.protocol)
                cercOptInService.save(optInAdapter, cercNotification)
                break
            default:
                throw new NotImplementedException("CercNotificationService.process >> Tipo de notificação [${cercNotification.notificationType}] não implementado")
        }
    }

    private CercNotification save(CercNotificationAdapter notificationAdapter) {
        CercNotification cercNotification = new CercNotification()
        cercNotification.notificationType = notificationAdapter.notificationType
        cercNotification.protocol = notificationAdapter.protocol
        cercNotification.registerCpfCnpj = notificationAdapter.registerCpfCnpj

        return cercNotification.save(failOnError: true)
    }
}
