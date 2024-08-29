package com.asaas.service.integration.sendgrid.dto.sendmailrequest.children

class MailSettingsDTO {

    MailSettingsByPassBounceManagementDTO bypass_bounce_management

    MailSettingsDTO(MailSettingsByPassBounceManagementDTO byPassBounceManagementDTO) {
        this.bypass_bounce_management = byPassBounceManagementDTO
    }
}
