package com.asaas.service.integration.sendgrid.dto

import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils

class SendGridCreateSubUserRequestDTO {

    String username

    String password

    String email

    List<String> ips

    public SendGridCreateSubUserRequestDTO(Customer customer, Map credentials, List<String> ipList) {
        this.username = credentials.username
        this.password = credentials.password
        this.email = customer.email
        this.ips = ipList
    }

    public Map toMap() {
        return Utils.bindPropertiesFromDomainClass(this, [])
    }
}
