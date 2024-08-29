package com.asaas.service.integration.sendgrid.dto

import com.asaas.utils.Utils

class SendGridCreateTemplateRequestDTO {

    String name

    String generation

    public SendGridCreateTemplateRequestDTO(String name) {
        this.name = name
        this.generation = "dynamic"
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
