package com.asaas.service.integration.sendgrid.dto.sendmailrequest.children

import com.asaas.utils.Utils

class BccDTO {

    String email

    public BccDTO(String bccMail) {
        this.email = bccMail
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
