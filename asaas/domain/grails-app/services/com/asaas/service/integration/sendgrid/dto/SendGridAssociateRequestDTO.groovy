package com.asaas.service.integration.sendgrid.dto

import com.asaas.utils.Utils

class SendGridAssociateRequestDTO {

    String username

    public SendGridAssociateRequestDTO(String username) {
        this.username = username
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
