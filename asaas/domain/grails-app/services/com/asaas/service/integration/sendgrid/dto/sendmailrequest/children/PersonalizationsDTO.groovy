package com.asaas.service.integration.sendgrid.dto.sendmailrequest.children

import com.asaas.domain.email.AsaasSecurityMailMessageBcc

class PersonalizationsDTO {

    List<ToDTO> to = []
    List<BccDTO> bcc = []
    Map dynamic_template_data = [:]

    public PersonalizationsDTO(String email, String name, List<AsaasSecurityMailMessageBcc> bccMailList, Map dynamicTemplateData) {
        this.to.add(new ToDTO(email, name))
        for (AsaasSecurityMailMessageBcc bccMail : bccMailList) {
            this.bcc.add(new BccDTO(bccMail.email))
        }
        this.dynamic_template_data = dynamicTemplateData
    }

    public Map toMapWithoutNullProperties() {
        Map properties = [:]

        properties.to = this.to.collect { it.toMap() }

        if (this.bcc) properties.bbc = this.bcc.collect { it.toMap() }
        if (this.dynamic_template_data) properties.dynamic_template_data = this.dynamic_template_data

        return properties
    }
}
