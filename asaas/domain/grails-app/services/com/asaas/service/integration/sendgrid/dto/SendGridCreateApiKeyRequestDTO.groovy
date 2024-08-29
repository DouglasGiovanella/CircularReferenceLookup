package com.asaas.service.integration.sendgrid.dto

import com.asaas.utils.Utils

class SendGridCreateApiKeyRequestDTO {

    String name

    public SendGridCreateApiKeyRequestDTO(String name) {
        this.name = name
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
