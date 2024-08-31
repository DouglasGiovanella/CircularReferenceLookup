package com.asaas.service.bradesco

import grails.transaction.Transactional

@Transactional
class BradescoMessageService {

    def grailsApplication

    def messageService

    public Boolean sendPobjCsv(String subject, String fileName, byte[] content) {
        String mailFrom = grailsApplication.config.asaas.sender
        String mailTo = grailsApplication.config.asaas.bradesco.pobj.emailTo
        List<String> bccMails = grailsApplication.config.asaas.bradesco.pobj.emailsBbc
        String mailSubject = subject
        String mailBody = "Segue POBJ '${fileName}' anexo"

        List<Map> attachmentList = []
        attachmentList.add([attachmentName: fileName, attachmentBytes: content])

        Map options = [:]
        options.attachmentList = attachmentList
        options.multipart = true

        return messageService.send(mailFrom, mailTo, bccMails, mailSubject, mailBody, false, options)
    }
}
