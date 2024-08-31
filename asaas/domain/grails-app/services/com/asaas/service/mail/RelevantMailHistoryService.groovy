package com.asaas.service.mail

import com.asaas.domain.customer.Customer
import com.asaas.domain.email.RelevantMailHistory
import com.asaas.domain.email.RelevantMailHistoryLoginLinkValidationRequest
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequest
import com.asaas.email.AsaasMailMessageType
import com.asaas.email.adapter.RelevantMailHistoryAdapter
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class RelevantMailHistoryService {

    public RelevantMailHistory save(Customer customer, String mailFrom, String mailTo, List<String> bccMails, String mailSubject, String mailBody, AsaasMailMessageType mailType) {
        try {
            RelevantMailHistory mail = new RelevantMailHistory()
            mail.customer = customer
            mail.mailFrom = mailFrom
            mail.mailTo = mailTo
            mail.bccMails = bccMails?.join(", ")
            mail.subject = mailSubject
            mail.body = mailBody
            mail.mailType = mailType
            mail.save(failOnError: true)

            return mail
        } catch (Exception exception) {
            AsaasLogger.error("RelevantMailHistoryService.save - Erro ao salvar RelevantMailHistory [customerId: ${customer.id},  subject: [${mailSubject}]", exception)
            return null
        }
    }

    public RelevantMailHistory save(RelevantMailHistoryAdapter adapter, LoginLinkValidationRequest loginLinkValidationRequest) {
        RelevantMailHistory mailHistoryEntry = save(
            adapter.customer,
            adapter.mailFrom,
            adapter.mailTo,
            adapter.bccMailList,
            adapter.mailSubject,
            adapter.mailBody,
            AsaasMailMessageType.LOGIN_LINK_VALIDATION_REQUEST
        )

        RelevantMailHistoryLoginLinkValidationRequest relevantMailHistoryLoginLinkValidationRequest = new RelevantMailHistoryLoginLinkValidationRequest()
        relevantMailHistoryLoginLinkValidationRequest.mailHistoryEntry = mailHistoryEntry
        relevantMailHistoryLoginLinkValidationRequest.loginLinkValidationRequest = loginLinkValidationRequest

        relevantMailHistoryLoginLinkValidationRequest.save(failOnError: true)

        return mailHistoryEntry
    }
}
