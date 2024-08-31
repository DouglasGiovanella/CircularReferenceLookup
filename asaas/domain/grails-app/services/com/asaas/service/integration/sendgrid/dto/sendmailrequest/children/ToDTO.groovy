package com.asaas.service.integration.sendgrid.dto.sendmailrequest.children

import com.asaas.utils.Utils

class ToDTO {

    String email

    String name

    public ToDTO(String email, String name) {
        this.email = email
        this.name = name
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
