package com.asaas.service.message

import com.asaas.asaascard.AsaasCardType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.customer.Customer
import com.asaas.integration.bifrost.adapter.notification.NotifyCardBillAdapter
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class AsaasCardCustomerMessageService {

    def customerMessageService
    def grailsApplication
    def messageService

    public void notifyAsaasCardRequested(AsaasCard asaasCard) {
        if (!customerMessageService.canSendMessage(asaasCard.customer)) return
        if (asaasCard.brand.isMasterCard()) return

        String emailSubject = "Cartão Asaas${asaasCard.type.isPrepaid() ? " pré-pago" : ""} solicitado com sucesso"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/onboardingAsaasCard", [asaasCard: asaasCard])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, asaasCard.customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardDelivered(AsaasCard asaasCard) {
        if (!customerMessageService.canSendMessage(asaasCard.customer)) return

        String emailSubject = "Oba seu cartão Asaas${asaasCard.type.isPrepaid() ? " pré-pago" : ""} chegou!"

        Boolean hasEloPrepaidCard = false
        if (asaasCard.type.isDebit()) hasEloPrepaidCard = AsaasCard.query(exists: true, customer: asaasCard.customer, type: AsaasCardType.PREPAID).get().asBoolean()

        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/asaasCardDelivered", [asaasCard: asaasCard, hasEloPrepaidCard: hasEloPrepaidCard])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, asaasCard.customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardActivated(AsaasCard asaasCard) {
        if (!customerMessageService.canSendMessage(asaasCard.customer)) return

        String emailSubject = "Seu cartão Asaas${asaasCard.type.isPrepaid() ? " pré-pago" : ""} está ativado e pronto para uso!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/asaasCardActivated", [asaasCard: asaasCard])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, asaasCard.customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardDeliveredNotActivated(Customer customer) {
        if (!customerMessageService.canSendMessage(customer)) return

        String emailSubject = "Cartão Asaas: agora falta pouco!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/incentiveForActivation", [:])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardBillClosed(Customer customer, BigDecimal value, Date dueDate, Map cardBillFileMap) {
        if (!customerMessageService.canSendMessage(customer)) return

        String emailSubject = "A fatura do seu cartão Asaas fechou!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/cardBillClosed", [value: value, dueDate: dueDate])

        String recipientName = messageService.getRecipientName(customer)

        Map attachment = [:]
        attachment.attachmentName = cardBillFileMap.fileName
        attachment.attachmentBytes = cardBillFileMap.fileBytes

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, customer.email, null, recipientName, emailSubject, emailBody, [multipart: true, attachmentList: [attachment]])
    }

    public void notifyUpcomingAsaasCardBillDueDate(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        if (!customerMessageService.canSendMessage(customer)) return

        Integer days = CustomDateUtils.calculateDifferenceInDays(new Date().clearTime(), notifyCardBillAdapter.dueDate)
        if (days <= 0) return

        String emailSubject = "A fatura do seu Cartão Asaas vence em ${days} ${days > 1 ? "dias" : "dia"}"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/upcomingCardBillDueDate", [valueAvailableForPayment: notifyCardBillAdapter.valueAvailableForPayment, dueDate: notifyCardBillAdapter.dueDate])

        messageService.sendFormalDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }

    public void notifyOverdueAsaasCardBill(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        if (!customerMessageService.canSendMessage(customer)) return

        String parsedReferenceMonth = CustomDateUtils.getMonthOfDateInPortuguese(notifyCardBillAdapter.referenceMonth)
        String emailSubject = "Atenção: a fatura do seu Cartão Asaas de ${parsedReferenceMonth} está em atraso"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/overdueCardBill", [valueAvailableForPayment: notifyCardBillAdapter.valueAvailableForPayment,
                                                                                                                automaticPaymentValue: notifyCardBillAdapter.automaticPaymentValue,
                                                                                                                referenceMonth: parsedReferenceMonth,
                                                                                                                billId: notifyCardBillAdapter.billId,
                                                                                                                asaasCardId: notifyCardBillAdapter.asaasCardId])

        messageService.sendFormalDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardBillPaidByAutomaticDebit(AsaasCardBillPayment asaasCardBillPayment, Long billId, Date dueDate) {
        if (!customerMessageService.canSendMessage(asaasCardBillPayment.asaasCard.customer)) return

        String emailSubject = "Cartão Asaas: pagamento de fatura realizado com sucesso!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/cardBillPaidByAutomaticDebit", [asaasCardBillPayment: asaasCardBillPayment, billId: billId, dueDate: CustomDateUtils.fromDate(dueDate, "dd/MM")])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, asaasCardBillPayment.asaasCard.customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardBillPaymentReceived(AsaasCardBillPayment asaasCardBillPayment) {
        if (!customerMessageService.canSendMessage(asaasCardBillPayment.asaasCard.customer)) return

        String emailSubject = "Cartão Asaas: pagamento recebido"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/cardBillPaymentReceived", [asaasCardBillPayment: asaasCardBillPayment])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, asaasCardBillPayment.asaasCard.customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardUnblockedAfterBalanceAcquittance(Customer customer, BigDecimal availableLimit) {
        if (!customerMessageService.canSendMessage(customer)) return

        String emailSubject = "Cartão Asaas desbloqueado!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/cardUnblockedAfterBalanceAcquittance", [availableLimit: availableLimit])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardAutomaticDebitActivated(Customer customer, Integer billDueDay) {
        if (!customerMessageService.canSendMessage(customer)) return

        String emailSubject = "Débito automático do cartão Asaas ativado!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/asaasCardAutomaticDebitActivated", [billDueDay: billDueDay])

        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }

    public void notifyAsaasCardUnpaidBillBlock(Customer customer) {
        if (!customerMessageService.canSendMessage(customer)) return

        String emailSubject = "Cartão Asaas bloqueado!"
        String emailBody = messageService.buildTemplate("/mailTemplate/asaascard/asaasCardUnpaidBillBlock", [customerName: customer.name])

        messageService.sendFormalDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }
}
