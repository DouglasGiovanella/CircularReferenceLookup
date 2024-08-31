package com.asaas.service.asaascard

import com.asaas.asaascard.AsaasCardStatus
import com.asaas.asaascardtransaction.AsaasCardTransactionNotificationEvent
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.customer.Customer
import com.asaas.exception.AsaasCardNotFoundException
import com.asaas.exception.BusinessException
import com.asaas.file.FileManager
import com.asaas.file.FileManagerFactory
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCreditCardLimitAdapter
import com.asaas.integration.bifrost.adapter.notification.NotifyCardBillAdapter
import com.asaas.integration.bifrost.adapter.notification.NotifyTransactionAdapter
import com.asaas.integration.bifrost.adapter.notification.sendmail.SendMailAdapter
import com.asaas.integration.bifrost.adapter.notification.sendmail.children.SendMailAttachmentAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasCardNotificationService {

    def asaasCardBillService
    def asaasCardCustomerMessageService
    def bifrostCardService
    def customerAlertNotificationService
    def grailsApplication
    def messageService
    def mobilePushNotificationService

    public void notifyDeliveredCard(AsaasCard asaasCard) {
        asaasCardCustomerMessageService.notifyAsaasCardDelivered(asaasCard)
        customerAlertNotificationService.notifyAsaasCardDelivered(asaasCard)
        mobilePushNotificationService.notifyAsaasCardDelivered(asaasCard)
    }

    public void notifyAsaasCardsDeliveredNotActivated() {
        Date initialDeliveredDate = CustomDateUtils.sumDays(new Date(), -7, false)
        Date finalDeliveredDate = CustomDateUtils.setTimeToEndOfDay(initialDeliveredDate)

        List<Long> asaasCardIdList = AsaasCard.query([column: "id", status: AsaasCardStatus.DELIVERED, "deliveredDate[ge]": initialDeliveredDate, "deliveredDate[le]": finalDeliveredDate]).list()

        Utils.forEachWithFlushSession(asaasCardIdList, 50, { Long asaasCardId ->
            Utils.withNewTransactionAndRollbackOnError({
                AsaasCard asaasCard = AsaasCard.get(asaasCardId)

                asaasCardCustomerMessageService.notifyAsaasCardDeliveredNotActivated(asaasCard.customer)
                customerAlertNotificationService.notifyAsaasCardDeliveredNotActivated(asaasCard.customer)
                mobilePushNotificationService.notifyAsaasCardDeliveredNotActivated(asaasCard)
            })
        })
    }

    public void notifyAsaasCardActivated(AsaasCard asaasCard) {
        asaasCardCustomerMessageService.notifyAsaasCardActivated(asaasCard)
        customerAlertNotificationService.notifyAsaasCardActivated(asaasCard)
        mobilePushNotificationService.notifyAsaasCardActivated(asaasCard)
    }

    public void notifyAsaasCardBillClosed(NotifyCardBillAdapter notifyCardBillAdapter) {
        AsaasCard asaasCard = AsaasCard.get(notifyCardBillAdapter.asaasCardId)
        if (!asaasCard) throw new BusinessException("Cartão não encontrado.")

        Map cardBillFileMap = asaasCardBillService.buildCardBillFile(asaasCard, notifyCardBillAdapter.billId)

        asaasCardCustomerMessageService.notifyAsaasCardBillClosed(asaasCard.customer, notifyCardBillAdapter.value, notifyCardBillAdapter.dueDate, cardBillFileMap)
        customerAlertNotificationService.notifyAsaasCardBillClosed(asaasCard.customer, notifyCardBillAdapter)
        mobilePushNotificationService.notifyAsaasCardBillClosed(asaasCard.customer, notifyCardBillAdapter)
    }

    public void notifyUpcomingAsaasCardBillDueDate(NotifyCardBillAdapter notifyCardBillAdapter) {
        Customer customer = AsaasCard.query([id: notifyCardBillAdapter.asaasCardId, column: "customer"]).get()
        asaasCardCustomerMessageService.notifyUpcomingAsaasCardBillDueDate(customer, notifyCardBillAdapter)
        customerAlertNotificationService.notifyUpcomingAsaasCardBillDueDate(customer, notifyCardBillAdapter)
        mobilePushNotificationService.notifyUpcomingAsaasCardBillDueDate(customer)
    }

    public void notifyOverdueAsaasCardBill(NotifyCardBillAdapter notifyCardBillAdapter) {
        Customer customer = AsaasCard.query([id: notifyCardBillAdapter.asaasCardId, column: "customer"]).get()
        asaasCardCustomerMessageService.notifyOverdueAsaasCardBill(customer, notifyCardBillAdapter)
        customerAlertNotificationService.notifyOverdueAsaasCardBill(customer, notifyCardBillAdapter)
        mobilePushNotificationService.notifyOverdueAsaasCardBill(customer, notifyCardBillAdapter)
    }

    public void notifyAsaasCardBillPaidByAutomaticDebit(AsaasCardBillPayment asaasCardBillPayment, Long billId, Date dueDate) {
        asaasCardCustomerMessageService.notifyAsaasCardBillPaidByAutomaticDebit(asaasCardBillPayment, billId, dueDate)
        customerAlertNotificationService.notifyAsaasCardBillPaidByAutomaticDebit(asaasCardBillPayment, billId, dueDate)
        mobilePushNotificationService.notifyAsaasCardBillPaidByAutomaticDebit(asaasCardBillPayment, billId, dueDate)
    }

    public void notifyAsaasCardBillPaymentReceived(AsaasCardBillPayment asaasCardBillPayment) {
        asaasCardCustomerMessageService.notifyAsaasCardBillPaymentReceived(asaasCardBillPayment)
        customerAlertNotificationService.notifyAsaasCardBillPaymentReceived(asaasCardBillPayment.asaasCard.customer)
        mobilePushNotificationService.notifyAsaasCardBillPaymentReceived(asaasCardBillPayment.asaasCard)
    }

    public void notifyAsaasCardUnblockedAfterBalanceAcquittance(AsaasCard asaasCard) {
        AsaasCreditCardLimitAdapter asaasCreditCardLimitAdapter = bifrostCardService.getCreditCardLimit(asaasCard)

        asaasCardCustomerMessageService.notifyAsaasCardUnblockedAfterBalanceAcquittance(asaasCard.customer, asaasCreditCardLimitAdapter.availableLimit)
        customerAlertNotificationService.notifyAsaasCardUnblockedAfterBalanceAcquittance(asaasCard.customer)
        mobilePushNotificationService.notifyAsaasCardUnblockedAfterBalanceAcquittance(asaasCard)
    }

    public void notifyAsaasCardAutomaticDebitActivated(Customer customer, Integer billDueDay) {
        asaasCardCustomerMessageService.notifyAsaasCardAutomaticDebitActivated(customer, billDueDay)
        customerAlertNotificationService.notifyAsaasCardAutomaticDebitActivated(customer)
        mobilePushNotificationService.notifyAsaasCardAutomaticDebitActivated(customer)
    }

    public void notifyCardUnpaidBillBlock(Long customerId) {
        Customer customer = Customer.read(customerId)

        asaasCardCustomerMessageService.notifyAsaasCardUnpaidBillBlock(customer)
        customerAlertNotificationService.notifyAsaasCardUnpaidBillBlock(customer)
        mobilePushNotificationService.notifyAsaasCardUnpaidBillBlock(customer)
    }

    public void notifyTransaction(NotifyTransactionAdapter notifyTransactionAdapter) {
        AsaasCard asaasCard = AsaasCard.read(notifyTransactionAdapter.asaasCardId)
        if (!asaasCard) throw new AsaasCardNotFoundException(notifyTransactionAdapter.asaasCardId.toString(), "Não foi possível localizar o cartão [asaasCardId: ${notifyTransactionAdapter.asaasCardId}].")

        switch (notifyTransactionAdapter.event) {
            case AsaasCardTransactionNotificationEvent.AUTHORIZED:
                customerAlertNotificationService.notifyAsaasCardTransactionAuthorized(asaasCard.customer, notifyTransactionAdapter)
                mobilePushNotificationService.notifyAsaasCardTransactionAuthorized(asaasCard, notifyTransactionAdapter)
                break
            case AsaasCardTransactionNotificationEvent.REFUSED:
                customerAlertNotificationService.notifyAsaasCardTransactionRefused(asaasCard, notifyTransactionAdapter)
                mobilePushNotificationService.notifyAsaasCardTransactionRefused(asaasCard, notifyTransactionAdapter)
                break
            case AsaasCardTransactionNotificationEvent.REFUNDED:
                customerAlertNotificationService.notifyAsaasCardTransactionRefunded(asaasCard, notifyTransactionAdapter)
                mobilePushNotificationService.notifyAsaasCardCreditTransactionRefund(asaasCard, notifyTransactionAdapter)
                break
            case AsaasCardTransactionNotificationEvent.REFUND_CANCELLED:
                customerAlertNotificationService.notifyAsaasCardTransactionRefundCancelled(asaasCard, notifyTransactionAdapter)
                mobilePushNotificationService.notifyAsaasCardCreditTransactionRefundCancelled(asaasCard, notifyTransactionAdapter)
                break
            default:
                throw new RuntimeException("Evento não encontrado.")
        }
    }

    public void sendMail(SendMailAdapter sendMailAdapter) {
        if (!sendMailAdapter.to) throw new BusinessException("Informe quem receberá o e-mail.")
        if (!sendMailAdapter.subject) throw new BusinessException("Informe o assunto do e-mail.")
        if (!sendMailAdapter.body) throw new BusinessException("Informe o corpo do e-mail.")

        Boolean emailSent = messageService.send(grailsApplication.config.asaas.sender, sendMailAdapter.to, sendMailAdapter.bcc, sendMailAdapter.subject, sendMailAdapter.body, sendMailAdapter.isHtml, buildMailOptions(sendMailAdapter))

        if (!emailSent) throw new RuntimeException("E-mail não enviado.")
    }

    public Map buildMailOptions(SendMailAdapter sendMailAdapter) {
        Map options = [:]

        if (sendMailAdapter.attachments) {
            options.multipart = true

            List<Map> attachmentList = []

            for (SendMailAttachmentAdapter attachment : sendMailAdapter.attachments) {
                FileManager fileManager = FileManagerFactory.getFileManager(attachment.source, attachment.path, attachment.uploadName)

                Map attachmentMap = [:]
                attachmentMap.attachmentName = attachment.originalName
                attachmentMap.attachmentBytes = fileManager.readBytes()

                attachmentList.add(attachmentMap)
            }

            options.attachmentList = attachmentList
        }

        return options
    }
}
