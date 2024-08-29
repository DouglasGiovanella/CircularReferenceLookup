package com.asaas.service.integration.sendgrid.dto

import com.asaas.utils.Utils

class SendGridCreateTemplateVersionRequestDTO {

    Integer active

    String name

    String html_content

    String subject

    public SendGridCreateTemplateVersionRequestDTO(String name, String subject, String body) {
        this.active = 1
        this.name = name
        this.subject = subject
        this.html_content = body
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
