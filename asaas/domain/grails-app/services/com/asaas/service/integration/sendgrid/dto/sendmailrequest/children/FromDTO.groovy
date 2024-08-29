package com.asaas.service.integration.sendgrid.dto.sendmailrequest.children

import com.asaas.utils.Utils

class FromDTO {

    String email

    String name

    public FromDTO(String email) {
        this.email = email
    }

    public FromDTO(String email, String name) {
        this.email = email
        this.name = name
    }

    public Map toMap() {
        List<String> unusedPropertyNameList = this.properties.findAll({ !it.value }).collect { it.key }
        return Utils.bindPropertiesFromDomainClass(this, unusedPropertyNameList)
    }
}
