package com.asaas.service.message

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.customer.Customer
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationCustomerMessageService {

    def grailsLinkGenerator
    def customerMessageService
    def grailsApplication
    def messageService

    public void sendReceivableAnticipationDenied(ReceivableAnticipation anticipation, String customerDenialMessage) {
        if (!customerMessageService.canSendMessage(anticipation.customer)) return

        String requestNewAnticipationUrl = ""

        if (anticipation.isDetachedAndDeniedByInvalidDocument()) {
            Boolean anticipable = PaymentAnticipableInfo.findAnticipableByPaymentId(anticipation.payment.id)
            if (anticipable) {
                requestNewAnticipationUrl = grailsLinkGenerator.link(controller: "receivableAnticipation", action: "request", params: [payment: anticipation.payment.id, hasPreviousAnticipationDenied: true], base: grailsApplication.config.grails.serverURL)
            }
        }

        String anticipationShowUrl = grailsLinkGenerator.link(controller: "receivableAnticipation", action: "show", params: [id: anticipation.id], base: grailsApplication.config.grails.serverURL)

        Map bodyParams = [observation        : customerDenialMessage,
                          anticipation       : anticipation,
                          anticipationShowUrl: anticipationShowUrl,
                          requestNewAnticipationUrl : requestNewAnticipationUrl]

        String body = messageService.buildTemplate("/mailTemplate/receivableAnticipationDenied", bodyParams)
        String title = "A sua antecipação não foi aprovada"
        String emailBody = messageService.buildDefaultTemplate(anticipation.customer.firstName, grailsApplication.config.asaas.message.receivableAnticipationDenied.emailSubject, body, title)

        messageService.send(grailsApplication.config.asaas.sender, anticipation.customer.email, null, grailsApplication.config.asaas.message.receivableAnticipationDenied.emailSubject, emailBody, true)
    }

    public void notifyCustomerAboutReceivableAnticipationCredit(ReceivableAnticipation anticipation) {
        if (!customerMessageService.canSendMessage(anticipation.customer)) return

        String subject = "A sua antecipação foi creditada"
        String emailBody = messageService.buildTemplate("/mailTemplate/receivableAnticipationCreditedToCustomer", [anticipationId: anticipation.id])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, anticipation.customer, subject, emailBody)
    }

    public void notifyCreditCardPercentageLimit(Customer customer) {
        if (!customerMessageService.canSendMessage(customer)) return

        String trackEmailAsViewedLink = grailsLinkGenerator.link(controller: 'anticipation', action: 'trackEmailAsViewed', params: [customerId: customer.id], absolute: true, base: AsaasApplicationHolder.config.asaas.app.shortenedUrl)
        String emailSubject = "Atualização no serviço de antecipação de cobranças no cartão de crédito"
        String emailBody = messageService.buildTemplate("/mailTemplate/receivableAnticipationCreditCardPercentageLimit", [
            customer: customer,
            trackEmailAsViewedLink: trackEmailAsViewedLink
        ])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }
}
