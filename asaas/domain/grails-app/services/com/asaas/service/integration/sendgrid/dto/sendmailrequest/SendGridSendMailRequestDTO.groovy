package com.asaas.service.integration.sendgrid.dto.sendmailrequest

import com.asaas.domain.email.AsaasSecurityMailMessage
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.ContentDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.FromDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.MailSettingsByPassBounceManagementDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.MailSettingsDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.children.PersonalizationsDTO
import com.asaas.utils.Utils

class SendGridSendMailRequestDTO {

    List<PersonalizationsDTO> personalizations = []

    FromDTO from

    String subject

    List<ContentDTO> content = []

    MailSettingsDTO mail_settings

    Map custom_args

    public SendGridSendMailRequestDTO(AsaasSecurityMailMessage mail) {
        this.personalizations.add(new PersonalizationsDTO(mail.mailTo, mail.toName, mail.getBccMailList(), null))
        this.from = new FromDTO(mail.mailFrom)
        this.subject = mail.subject
        this.content.add(new ContentDTO(mail.text, mail.html))

        MailSettingsByPassBounceManagementDTO byPassListManagementDTO = new MailSettingsByPassBounceManagementDTO(true)
        this.mail_settings = new MailSettingsDTO(byPassListManagementDTO)

        if (mail.mailHistoryEntry) {
            addCustomArg("auditableMailId", mail.mailHistoryEntry.id)
        }
    }

    public void addCustomArg(String key, def value) {
        if (!this.custom_args) {
            this.custom_args = [:]
        }

        this.custom_args.put(key, value)
    }

    public Map toMap() {
        Map sendGridSendMailRequestMap = Utils.bindPropertiesFromDomainClass(this, [])
        sendGridSendMailRequestMap.personalizations = [personalizations.first().toMapWithoutNullProperties()]

        return sendGridSendMailRequestMap
    }
}
