package com.asaas.service.integration.sendgrid.dto.sendmailrequest.children

class ContentDTO {

    String type

    String value

    public ContentDTO(String value, Boolean isHtml) {
        if (isHtml) {
            this.type = "text/html"
        } else {
            this.type = "text/plain"
        }
        this.value = value
    }
}
