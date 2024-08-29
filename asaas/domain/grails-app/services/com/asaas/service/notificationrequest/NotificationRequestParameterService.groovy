package com.asaas.service.notificationrequest

import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerInfoFormatter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.domain.notification.CustomNotificationTemplateGroup
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationTemplatePropertyCondition
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.domain.payment.Payment
import com.asaas.generatereceipt.PaymentGenerateReceiptUrl
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationMessageType
import com.asaas.notification.NotificationType
import com.asaas.notificationtemplate.NotificationTemplateProperty
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils

@Transactional
class NotificationRequestParameterService {

    def boletoService
    def linkService
    def grailsApplication

    public Map buildInternalLinkParams(NotificationRequest notificationRequest) {
        Map linkParams = [:]

        if (!notificationRequest.type.isSms()) linkParams.notificationRequestId = notificationRequest.id
        if (notificationRequest.type == NotificationType.SMS) linkParams.sentBySms = true
        if (notificationRequest.type == NotificationType.EMAIL) linkParams.sentByEmail = true
        if (notificationRequest.type == NotificationType.WHATSAPP) linkParams.sentByWhatsApp = true

        linkParams.baseUrl = getBaseUrl(notificationRequest.customerAccount.providerId)

        return linkParams
    }

    public Integer calculateAbsoluteDaysUntilDueDate(NotificationRequest notificationRequest) {
        Integer dueDateDays = CustomDateUtils.calculateDifferenceInDays(
            notificationRequest.scheduledDate ?: new Date(),
            notificationRequest.payment.dueDate
        )

        return Math.abs(dueDateDays)
    }

    public String getPropertyValue(NotificationRequest notificationRequest, NotificationTemplateProperty property) {
        Payment payment = notificationRequest.payment
        Boolean isSms = notificationRequest.type.isSms()
        Date scheduledDate = notificationRequest.scheduledDate ?: new Date()

        switch (property) {
            case NotificationTemplateProperty.BANKSLIP_LINHA_DIGITAVEL:
                return boletoService.getLinhaDigitavel(payment)

            case NotificationTemplateProperty.BILLING_TYPE:
                return payment.status.isReceivedInCash()
                    ? getFallbackValueMessageFromProperty(property)
                    : payment.billingType.getLabel()

            case NotificationTemplateProperty.CUSTOMER_ACCOUNT_NAME:
                return payment.customerAccount.name

            case NotificationTemplateProperty.CUSTOMER_NAME:
                return CustomerInfoFormatter.formatName(notificationRequest.customerAccount.provider)

            case NotificationTemplateProperty.DAYS_AFTER_DUE_DATE:
                return calculateDaysAfterOverdue(notificationRequest.payment.dueDate, scheduledDate).toString()

            case NotificationTemplateProperty.DAYS_UNTIL_DUE_DATE:
                return calculateDaysToDueDate(notificationRequest.payment.dueDate, scheduledDate).toString()

            case NotificationTemplateProperty.DELETED_DATE:
                return CustomDateUtils.formatDate(notificationRequest.dateCreated)

            case NotificationTemplateProperty.DUE_DATE:
                Boolean isToday = (payment.dueDate.clone().clearTime() == new Date().clearTime())
                return CustomDateUtils.formatDate(payment.dueDate) + "${isToday ? " (hoje)" : ""}"

            case NotificationTemplateProperty.INSTALLMENT_COUNT:
                return payment.installment
                    ? payment.installment.payments.size().toString()
                    : getFallbackValueMessageFromProperty(property)

            case NotificationTemplateProperty.INSTALLMENT_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(payment.value)

            case NotificationTemplateProperty.INVOICE_URL:
                return isSms
                    ? linkService.viewInvoiceShort(payment, buildInternalLinkParams(notificationRequest))
                    : linkService.viewInvoice(payment, buildInternalLinkParams(notificationRequest))

            case NotificationTemplateProperty.ORIGINAL_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(payment.originalValue ?: payment.value)

            case NotificationTemplateProperty.PAID_VALUE:
                BigDecimal paidValue = (payment.isCreditCard() && payment.installment)
                    ? payment.installment.getValue()
                    : payment.value

                return FormUtils.formatCurrencyWithMonetarySymbol(paidValue)

            case NotificationTemplateProperty.PAYMENT_VALUE:
                BigDecimal paymentValue = payment.installment
                    ? payment.installment.getValue()
                    : payment.value

                return FormUtils.formatCurrencyWithMonetarySymbol(paymentValue)

            case NotificationTemplateProperty.TRANSACTION_RECEIPT_URL:
                Boolean isCustomerPaymentReceivedEvent = notificationRequest.getEventTrigger().isCustomerPaymentReceived()
                if (isCustomerPaymentReceivedEvent && payment.status.isReceivedInCash()) {
                    return getFallbackValueMessageFromProperty(property)
                }

                return new PaymentGenerateReceiptUrl(payment).generateAbsoluteUrl()
        }

        if (isSms) {
            throw new RuntimeException("Variável inválida para esse tipo de notificação")
        }

        switch (property) {
            case NotificationTemplateProperty.INTEREST_VALUE:
                return payment.interestValue
                    ? FormUtils.formatCurrencyWithMonetarySymbol(payment.interestValue)
                    : getFallbackValueMessageFromProperty(property)

            case NotificationTemplateProperty.INVOICE_NUMBER:
                return payment.getInvoiceNumber()

            case NotificationTemplateProperty.PAYMENT_DATE:
                return CustomDateUtils.formatDate(payment.paymentDate)

            case NotificationTemplateProperty.PAYMENT_DESCRIPTION:
                Integer maxDescriptionLength = 180
                return StringUtils.truncateAndAddEllipsisIfNecessary(payment.buildDescription(), maxDescriptionLength)

            case NotificationTemplateProperty.PAYMENT_VALUE_WITH_INTEREST:
                BigDecimal paymentValueWithInterest = payment.getOriginalValueWithInterest()

                return paymentValueWithInterest
                    ? FormUtils.formatCurrencyWithMonetarySymbol(paymentValueWithInterest)
                    : getFallbackValueMessageFromProperty(property)
        }
    }

    public String getEmailMockPropertyValue(Customer customer, NotificationTemplateProperty property) {
        BigDecimal paymentValue = 500
        BigDecimal interestValue = 50
        Date nextBusinessDay = CustomDateUtils.getNextBusinessDay()
        Date today = new Date()
        String urlPrefix = '<a href="#" style="color: #0D6EFD;">https://www.asaas.com/'

        switch (property) {
            case NotificationTemplateProperty.BANKSLIP_LINHA_DIGITAVEL:
                return "0001" * 12

            case NotificationTemplateProperty.BILLING_TYPE:
                return BillingType.BOLETO.getLabel()

            case NotificationTemplateProperty.CUSTOMER_ACCOUNT_NAME:
                return "José da Silva"

            case NotificationTemplateProperty.CUSTOMER_NAME:
                return CustomerInfoFormatter.formatName(customer)

            case NotificationTemplateProperty.DAYS_AFTER_DUE_DATE:
                return calculateDaysAfterOverdue(nextBusinessDay, today)

            case NotificationTemplateProperty.DAYS_UNTIL_DUE_DATE:
                return calculateDaysToDueDate(nextBusinessDay, today)

            case NotificationTemplateProperty.DELETED_DATE:
                return CustomDateUtils.formatDate(today)

            case NotificationTemplateProperty.DUE_DATE:
                return CustomDateUtils.formatDate(nextBusinessDay)

            case NotificationTemplateProperty.INSTALLMENT_COUNT:
                return "1"

            case NotificationTemplateProperty.INSTALLMENT_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(paymentValue)

            case NotificationTemplateProperty.INVOICE_URL:
                return urlPrefix + "i/codigo_da_fatura</a>"

            case NotificationTemplateProperty.ORIGINAL_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(paymentValue)

            case NotificationTemplateProperty.PAID_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(paymentValue + interestValue)

            case NotificationTemplateProperty.PAYMENT_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(paymentValue)

            case NotificationTemplateProperty.TRANSACTION_RECEIPT_URL:
                return urlPrefix + "comprovantes/codigo_do_comprovante</a>"

            case NotificationTemplateProperty.INTEREST_VALUE:
                return FormUtils.formatCurrencyWithMonetarySymbol(interestValue)

            case NotificationTemplateProperty.INVOICE_NUMBER:
                return "10" * 8

            case NotificationTemplateProperty.PAYMENT_DATE:
                return CustomDateUtils.formatDate(today)

            case NotificationTemplateProperty.PAYMENT_DESCRIPTION:
                return (new Payment()).buildDescription()

            case NotificationTemplateProperty.PAYMENT_VALUE_WITH_INTEREST:
                return FormUtils.formatCurrencyWithMonetarySymbol(paymentValue + interestValue)
        }
    }

    public String getBaseUrl(Long customerId) {
        String baseUrl = CustomerNotificationConfig.query([column: "customDomainBaseUrl", customerId: customerId]).get()
        return baseUrl ?: grailsApplication.config.grails.serverURL
    }

    public Map getLogoParameters(Customer customer, String baseUrl) {
        Map parameters = [:]
        String logoUrl = baseUrl + "/static/images/emails/ico_asaas.png"
        if (customer.getNotificationLogoFile()) {
            logoUrl = linkService.customerNotificationLogo(customer, baseUrl) + "?" + RandomStringUtils.randomNumeric(10)
        }
        parameters.put("logoUrl", logoUrl)

        String defaultLogoHeightAndWidth = " width=29 height=23 "
        String customerLogoHeightAndWidth = CustomerNotificationConfig.findIfEnabled(customer.id, [column: "logoHeightAndWidth"]).get()
        parameters.put("logoHeightAndWidth", customerLogoHeightAndWidth ?: defaultLogoHeightAndWidth)
        parameters.put("logoMaxWidth", CustomerNotificationConfig.LOGO_MAX_WIDTH)
        parameters.put("logoMaxHeight", CustomerNotificationConfig.LOGO_MAX_HEIGHT)

        return parameters
    }

    public Map getCustomerNotificationColours(Customer customer) {
        Map parameters = [:]
        Map customerColors = CustomerInvoiceConfig.getCustomerExternalCommunicationColors(customer)
        parameters.put("notificationSecondaryColor", customerColors.secondaryColor)
        parameters.put("notificationFontColor", customerColors.customerInfoFontColor)

        return parameters
    }

    public String getNotificationPreheader(NotificationRequest notificationRequest) {
        NotificationEvent event = notificationRequest.getEventTrigger()
        String messageType = getCustomerNotificationMessageType(notificationRequest).toString().toLowerCase()

        if (event.isPaymentDuedateWarning()) {
            String notificationSchedule = notificationRequest.getNotificationRequestTrigger().schedule.toString().toLowerCase()
            return Utils.getMessageProperty("notificationTemplates.${ messageType }.email.customer.PaymentDuedateWarning.${ notificationSchedule }.preheader")
        }

        return Utils.getMessageProperty("notificationTemplates.${ messageType }.email.customer.${ event }.preheader")
    }

    public String getNotificationCallToActionButtonLabel(Customer customer, NotificationEvent event) {
        String messageType = (customer.getConfig()?.notificationMessageType ?: NotificationMessageType.PAYMENT).toString().toLowerCase()
        return Utils.getMessageProperty("notificationTemplates.${ messageType }.email.customer.${ event }.callToActionButton.label")
    }

    public String getNotificationCallToActionButtonLabel(NotificationRequest notificationRequest) {
        NotificationEvent event = notificationRequest.getEventTrigger()
        String messageType = getCustomerNotificationMessageType(notificationRequest).toString().toLowerCase()

        return Utils.getMessageProperty("notificationTemplates.${ messageType }.email.customer.${ event }.callToActionButton.label")
    }

    public Map buildNotificationTemplatePropertiesMap(NotificationRequest notificationRequest) {
        NotificationTrigger notificationTrigger = notificationRequest.getNotificationRequestTrigger()

        List<NotificationTemplateProperty> propertyList = NotificationTemplatePropertyCondition.query([
            notificationEvent: notificationTrigger.event,
            notificationSchedule: notificationTrigger.schedule,
            type: notificationRequest.type,
            column: "property"
        ]).list(readOnly: true) as List<NotificationTemplateProperty>

        if (notificationRequest.type.isSms()) {
            return propertyList.collectEntries {
                ["\\{\\{" + it.getLabel() + "}}", getPropertyValue(notificationRequest, it)]
            }
        }

        return propertyList.collectEntries {
            [it.toString(), getPropertyValue(notificationRequest, it)]
        }
    }

    public List<Map> buildCustomEmailTemplateMockPropertiesMap(Customer customer, CustomNotificationTemplateGroup templateGroup) {
        return NotificationTemplatePropertyCondition.query([
            type: NotificationType.EMAIL,
            templateGroup: templateGroup,
            column: "property"
        ]).list(readOnly: true).collect { NotificationTemplateProperty it ->
            [propertyLabel: ">{{" + it.getLabel() + "}}<", propertyValue: ">" + getEmailMockPropertyValue(customer, it) + "<"]
        }
    }

    public String getNotificationTitle(NotificationRequest notificationRequest, Map notificationParameters) {
        if (!notificationRequest.receiver.isProvider()) return ""

        NotificationEvent notificationEvent = notificationRequest.getEventTrigger()

        if (notificationEvent.isCustomerPaymentOverdue()) return "Cobrança vencida"
        if (notificationEvent.isPaymentDeleted()) return "Cobrança excluída"
        if (notificationEvent.isCustomerPaymentReceived()) {
            return notificationParameters.receivedValueIsDifferentThanExpected
                ? "Pagamento confirmado com valor divergente"
                : "Pagamento confirmado"
        }

        return ""
    }

    public Map buildDefaultMapForExternalTemplateNotification(NotificationRequest notificationRequest) {
        Customer customer = notificationRequest.customerAccount.provider

        Map defaultMap = [:]
        defaultMap.put("customer", customer)
        defaultMap.put("boletoURLCustomer", linkService.viewInvoice(notificationRequest.payment, buildInternalLinkParams(notificationRequest)))
        defaultMap.put("callToActionLabel", getNotificationCallToActionButtonLabel(notificationRequest))

        return defaultMap
    }

    public NotificationMessageType getCustomerNotificationMessageType(NotificationRequest notificationRequest) {
        return notificationRequest.customerAccount.provider.getConfig()?.notificationMessageType ?: NotificationMessageType.PAYMENT
    }

    private Integer calculateDaysToDueDate(Date dueDate, Date dateToCalculate) {
        final Integer minDaysToDueDate = 0
        return Math.max(CustomDateUtils.calculateDifferenceInDays(dateToCalculate, dueDate), minDaysToDueDate)
    }

    private Integer calculateDaysAfterOverdue(Date dueDate, Date dateToCalculate) {
        final Integer minDaysAfterOverdue = 0
        return Math.max(CustomDateUtils.calculateDifferenceInDays(dueDate, dateToCalculate), minDaysAfterOverdue)
    }

    private String getFallbackValueMessageFromProperty(NotificationTemplateProperty property) {
        return Utils.getMessageProperty("notificationTemplateProperty.${ property }.fallbackValueMessage")
    }
}
