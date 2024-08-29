package com.asaas.service.integration.sendgrid.dto.sendmailrequest

import com.asaas.domain.email.AsaasMailMessage
import com.asaas.email.EventNotificationType
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.FromDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.PersonalizationsDTO

class SendGridSendTemplateMailRequestDTO {

    List<PersonalizationsDTO> personalizations = []
    FromDTO from
    String template_id
    Map custom_args = [:]

    public SendGridSendTemplateMailRequestDTO(AsaasMailMessage mail, String templateId, Map templateData) {
        this.personalizations.add(new PersonalizationsDTO(mail.to, mail.to, null, templateData))
        this.from = new FromDTO(mail.from, mail.fromName)
        this.template_id = templateId

        if (mail.notificationRequest) {
            custom_args.notificationId = mail.notificationRequest.id.toString()
            custom_args.notificationType = EventNotificationType.NOTIFICATION_REQUEST.toString()
            custom_args.notificationRequestToId = (mail.notificationRequest.notificationRequestToList.find { it.email == mail.to }.id).toString()
        }
    }

    public Map toMap() {
        Map properties = [:]

        properties.template_id = this.template_id
        properties.personalizations = [this.personalizations.first().toMapWithoutNullProperties()]
        properties.from = this.from.toMap()

        if (this.custom_args) properties.custom_args = this.custom_args

        return properties
    }
}
