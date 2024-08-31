package com.asaas.service.chargedfee

import com.asaas.chargedfee.ChargedFeeStatus
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.PersonType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.invoice.ConsumerInvoice
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.invoice.ProductInvoice
import com.asaas.domain.knownyourcustomer.KnownYourCustomerRequestBatch
import com.asaas.domain.notification.InstantTextMessage
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.PaymentNotificationSent
import com.asaas.domain.notification.PhoneCallNotification
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentDunningAccountability
import com.asaas.domain.payment.PaymentOriginRequesterInfo
import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.promotionalcode.PromotionalCodeUse
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig
import com.asaas.exception.BusinessException
import com.asaas.notification.NotificationType
import com.asaas.originrequesterinfo.OriginChannel
import com.asaas.pix.PixTransactionOriginType
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class ChargedFeeService {

    def financialTransactionService
    def paymentOriginChannelFeeConfigService
    def promotionalCodeService

    public ChargedFee refundDunningRequestFee(PaymentDunning paymentDunning) {
        String refundTransactionDescription = "Estorno da taxa para negativação - fatura nr. ${paymentDunning.payment.getInvoiceNumber()}"
        return refund(paymentDunning.customer, ChargedFeeType.PAYMENT_DUNNING_REQUESTED, paymentDunning.fee, paymentDunning, refundTransactionDescription)
    }

    public ChargedFee saveDunningRequestFee(PaymentDunning paymentDunning) {
        BigDecimal dunningRequestFee = paymentDunning.fee
        if (dunningRequestFee < ChargedFee.MINIMUM_VALUE) return null

        String description = "Taxa para negativação"
        String financialDescription = buildDefaultFinancialDescription(description, paymentDunning.payment.getInvoiceNumber(), paymentDunning.payment.customerAccount.name)
        return save(paymentDunning.customer, [value: dunningRequestFee, type: ChargedFeeType.PAYMENT_DUNNING_REQUESTED, dunning: paymentDunning, financialDescription: financialDescription])
    }

    public ChargedFee saveRecurrentChargedFeeConfigForPlan(RecurrentChargedFeeConfig recurrentChargedFeeConfig, ChargedFeeType chargedFeeType) {
        if (!chargedFeeType.isContractedCustomerPlan()) throw new NotImplementedException("Descrição financeira não implementada para o tipo: ${chargedFeeType}.")
        String planName = CustomerPlan.query([column: "plan.name", customer: recurrentChargedFeeConfig.customer]).get()
        String financialDescription = "Taxa da mensalidade do plano ${Utils.getMessageProperty("customerPlan.${planName}.label")} do Asaas"

        return save(recurrentChargedFeeConfig.customer, [value: recurrentChargedFeeConfig.value, type: chargedFeeType, financialDescription: financialDescription])
    }

    public ChargedFee saveAccountInactivityFee(Customer customer, ChargedFeeType chargedFeeType, BigDecimal value) {
        String financialDescription = "Taxa de conta inativa"

        return save(customer, [value: value, type: chargedFeeType, financialDescription: financialDescription])
    }

    public ChargedFee refundAccountInactivityFee(Customer customer, ChargedFee chargedFee) {
        String refundTransactionDescription = "Estorno da taxa de conta inativa"
        return refund(customer, ChargedFeeType.ACCOUNT_INACTIVITY,  chargedFee.value, null, refundTransactionDescription)
    }

    public ChargedFee refundLastRecurrentChargedFeeConfig(RecurrentChargedFeeConfig recurrentChargedFeeConfig, ChargedFeeType type) {
        Customer customer = recurrentChargedFeeConfig.customer
        String refundTransactionDescription
        if (!type.isContractedCustomerPlan()) {
            throw new NotImplementedException("Descrição financeira de estorno não implementada para o tipo: ${type}.")
        }

        String planName = CustomerPlan.query([column: "plan.name", customer: customer]).get()
        refundTransactionDescription = "Estorno da taxa de mensalidade do plano ${Utils.getMessageProperty("customerPlan.${planName}.label")} do Asaas"

        return refund(customer, type, recurrentChargedFeeConfig.value, null, refundTransactionDescription)
    }

    public ChargedFee saveDunningAccountabilityFee(PaymentDunningAccountability accountability) {
        String description = "Taxa para negativação"
        String financialDescription = buildDefaultFinancialDescription(description, accountability.dunning.payment.getInvoiceNumber(), accountability.dunning.payment.customerAccount.name)
        return save(accountability.dunning.payment.provider, [value: accountability.negotiationFee, type: ChargedFeeType.PAYMENT_DUNNING_RECEIVED, dunning: accountability.dunning, financialDescription: financialDescription])
    }

    public ChargedFee saveDunningReceivedInCashFee(PaymentDunning paymentDunning) {
        String description = "Taxa para negativação em dinheiro"
        String financialDescription = buildDefaultFinancialDescription(description, paymentDunning.payment.getInvoiceNumber(), paymentDunning.payment.customerAccount.name)
        return save(paymentDunning.payment.provider, [value: paymentDunning.fee, type: ChargedFeeType.PAYMENT_DUNNING_RECEIVED_IN_CASH, dunning: paymentDunning, financialDescription: financialDescription])
    }

    public ChargedFee saveDunningCancellationFee(PaymentDunning paymentDunning) {
        String description = "Taxa para cancelamento de negativação"
        String financialDescription = buildDefaultFinancialDescription(description, paymentDunning.payment.getInvoiceNumber(), paymentDunning.payment.customerAccount.name)
        return save(paymentDunning.customer, [value: paymentDunning.fee, type: ChargedFeeType.PAYMENT_DUNNING_CANCELLED, dunning: paymentDunning, financialDescription: financialDescription])
    }

    public ChargedFee savePaymentSmsNotificationFeeIfNecessary(Payment payment) {
        BigDecimal paymentSmsNotificationFee = CustomerFee.calculateSmsNotificationFee(payment.provider)
        if (paymentSmsNotificationFee < ChargedFee.MINIMUM_VALUE) return null

        if (!CustomerParameter.getValue(payment.provider, CustomerParameterName.ENABLE_PAYMENT_SMS_NOTIFICATION_FEE)) return null
        Boolean hasSentNotification = NotificationRequest.smsSentOrAwaitingProcessing([exists: true, payment: payment]).get().asBoolean()
        if (!hasSentNotification) hasSentNotification = PaymentNotificationSent.query([exists: true, paymentId: payment.id, type: NotificationType.SMS]).get().asBoolean()
        if (!hasSentNotification) return null

        Boolean alreadyFee = ChargedFee.query(["exists": true, customer: payment.provider, type: ChargedFeeType.PAYMENT_SMS_NOTIFICATION, payment: payment, status: ChargedFeeStatus.DEBITED]).get().asBoolean()
        if (alreadyFee) return null

        String description = "Taxa de notificação por SMS"
        String financialDescription = buildDefaultFinancialDescription(description, payment.getInvoiceNumber(), payment.customerAccount.name)
        return save(payment.provider, [value: paymentSmsNotificationFee, type: ChargedFeeType.PAYMENT_SMS_NOTIFICATION, payment: payment, financialDescription: financialDescription])
    }

    public ChargedFee savePaymentMessagingNotificationFeeIfNecessary(Payment payment) {
        BigDecimal paymentMessagingNotificationFee = CustomerFee.calculateMessagingNotificationFee(payment.provider)
        if (paymentMessagingNotificationFee < ChargedFee.MINIMUM_VALUE) return null

        Boolean hasNotificationSent = NotificationRequest.smsSentOrAwaitingProcessing(["exists": true, payment: payment, "notification[isNotNull]": true]).get().asBoolean()
        if (!hasNotificationSent) hasNotificationSent = NotificationRequest.emailSentOrAwaitingProcessing(["exists": true, payment: payment, "notification[isNotNull]": true]).get().asBoolean()
        if (!hasNotificationSent) hasNotificationSent = PaymentNotificationSent.query([exists: true, paymentId: payment.id]).get().asBoolean()
        if (!hasNotificationSent) return null

        Boolean alreadyHasMessagingFee = ChargedFee.query(["exists": true, customer: payment.provider, type: ChargedFeeType.PAYMENT_MESSAGING_NOTIFICATION, payment: payment, status: ChargedFeeStatus.DEBITED]).get().asBoolean()
        if (alreadyHasMessagingFee) return null

        if (payment.installment?.billingType?.isCreditCard()) {
            List<Long> installmentPaymentIdList = payment.installment.payments.collect { it.id }
            Boolean alreadyHasCreditCardInstallmentWithMessagingFee = ChargedFee.query(["exists": true, customer: payment.provider, type: ChargedFeeType.PAYMENT_MESSAGING_NOTIFICATION, "paymentId[in]": installmentPaymentIdList, status: ChargedFeeStatus.DEBITED]).get().asBoolean()
            if (alreadyHasCreditCardInstallmentWithMessagingFee) return null
        }

        String description = "Taxa de mensageria"
        String financialDescription = buildDefaultFinancialDescription(description, payment.getInvoiceNumber(), payment.customerAccount.name)
        return save(payment.provider, [value: paymentMessagingNotificationFee, type: ChargedFeeType.PAYMENT_MESSAGING_NOTIFICATION, payment: payment, financialDescription: financialDescription])
    }

    public ChargedFee savePixTransactionDebitFee(PixTransaction pixTransaction) {
        if (!pixTransaction.type.isDebit()) throw new RuntimeException("ChargedFeeService.savePixTransactionDebitFee() -> Tipo inválido para cobrança de taxa Pix [pixTransaction.type: ${pixTransaction.type}, pixTransaction.id: ${pixTransaction.id}, pixTransaction.endToEndIdentifier: ${pixTransaction.endToEndIdentifier}]")

        BigDecimal value = CustomerFee.calculatePixDebitFee(pixTransaction.customer)
        if (value < ChargedFee.MINIMUM_VALUE) return null

        Map feeParameters = [value: value, type: ChargedFeeType.PIX_DEBIT, pixTransaction: pixTransaction, financialDescription: buildPixDebitFinancialDescription(pixTransaction)]
        return save(pixTransaction.customer, feeParameters)
    }

    public ChargedFee refundPixTransactionDebitFeeIfNecessary(PixTransaction pixTransaction) {
        ChargedFee chargedFee = ChargedFee.debited([customer: pixTransaction.customer, status: ChargedFeeStatus.DEBITED, object: pixTransaction]).get()
        if (!chargedFee) return null
        String refundTransactionDescription = "Estorno da taxa para ${Utils.getMessageProperty("PixTransactionOriginType.${chargedFee.pixTransaction.originType}")}"

        return refund(chargedFee.customer, ChargedFeeType.PIX_DEBIT, chargedFee.value, pixTransaction, refundTransactionDescription)
    }

    public ChargedFee savePixTransactionCreditFee(PixTransaction pixTransaction) {
        if (pixTransaction.payment) throw new RuntimeException("ChargedFeeService.savePixTransactionCreditFee() -> Taxa Pix de crédito inválida pois existe uma cobrança vinculada com a transferência. [pixTransaction.paymentId: ${pixTransaction.paymentId}, pixTransaction.id: ${pixTransaction.id}, pixTransaction.endToEndIdentifier: ${pixTransaction.endToEndIdentifier}]")
        if (!pixTransaction.type.isCredit()) throw new RuntimeException("ChargedFeeService.savePixTransactionCreditFee() -> Tipo inválido para cobrança de taxa Pix [pixTransaction.type: ${pixTransaction.type}, pixTransaction.id: ${pixTransaction.id}, pixTransaction.endToEndIdentifier: ${pixTransaction.endToEndIdentifier}]")

        BigDecimal value = PixCreditFee.calculateFee(pixTransaction.customer, pixTransaction.value)

        if (value < ChargedFee.MINIMUM_VALUE) return null

        Map feeParameters = [value: value, type: ChargedFeeType.PIX_CREDIT, pixTransaction: pixTransaction, financialDescription: buildPixCreditFinancialDescription(pixTransaction)]
        return save(pixTransaction.customer, feeParameters)
    }

    public ChargedFee saveCreditBureauReportFee(Customer customer, CreditBureauReport creditBureauReport, PersonType personType) {
        BigDecimal creditBureauReportFee = CustomerFee.calculateCreditBureauReport(customer, personType)
        if (creditBureauReportFee < ChargedFee.MINIMUM_VALUE) return null

        String description = "Taxa de consulta Serasa do "
        String cpfCnpj = creditBureauReport.cpfCnpj

        if (CpfCnpjUtils.isCpf(cpfCnpj)) {
            description += "CPF "
        } else {
            description += "CNPJ "
        }
        description += CpfCnpjUtils.formatCpfCnpj(cpfCnpj)

        return save(customer, [value: creditBureauReportFee, creditBureauReport: creditBureauReport, financialDescription: description, type: ChargedFeeType.CREDIT_BUREAU_REPORT])
    }

    public ChargedFee saveProductInvoiceFee(ProductInvoice productInvoice) {
        BigDecimal productInvoiceFee = CustomerFee.calculateProductInvoiceFee(productInvoice.customer)
        if (productInvoiceFee < ChargedFee.MINIMUM_VALUE) return null

        String productInvoiceProvider = Utils.getMessageProperty("productInvoiceProvider.${productInvoice.productInvoiceProvider}")
        String description = "Taxa de emissão da nota fiscal de produto emitida via ${productInvoiceProvider} nr. ${productInvoice.number}"
        return save(productInvoice.customer, [value: productInvoiceFee, type: ChargedFeeType.PRODUCT_INVOICE, productInvoice: productInvoice, financialDescription: description])
    }

    public ChargedFee saveConsumerInvoiceFee(ConsumerInvoice consumerInvoice) {
        String providerName = Utils.getMessageProperty("consumerInvoiceProvider.${consumerInvoice.consumerInvoiceProvider}")
        String description = Utils.getMessageProperty("customer.consumerInvoice.financialDescription", [providerName, consumerInvoice.number])

        Map chargedFeeMap = [
            value: CustomerFee.calculateConsumerInvoiceFee(consumerInvoice.customer),
            type: ChargedFeeType.CONSUMER_INVOICE,
            financialDescription: description
        ]

        return save(consumerInvoice.customer, chargedFeeMap)
    }

    public ChargedFee savePhoneCallNotificationFee(Customer customer, PhoneCallNotification phoneCallNotification) {
        BigDecimal phoneCallNotificationFee = CustomerFee.calculatePhoneCallNotificationFee(customer)
        if (phoneCallNotificationFee < ChargedFee.MINIMUM_VALUE) return null

        String description = Utils.getMessageProperty("feeDescription.phoneCallNotificationFee").capitalize()
        String financialDescription = buildDefaultFinancialDescription(description, phoneCallNotification.notificationRequest.payment.getInvoiceNumber(), phoneCallNotification.notificationRequest.payment.customerAccount.name)
        return save(customer, [value: phoneCallNotificationFee, type: ChargedFeeType.PAYMENT_PHONE_CALL_NOTIFICATION, phoneCallNotification: phoneCallNotification, financialDescription: financialDescription])
    }

    public ChargedFee savePhoneCallNotificationSentFee(Payment payment) {
        BigDecimal phoneCallNotificationFee = CustomerFee.calculatePhoneCallNotificationFee(payment.provider)
        if (phoneCallNotificationFee < ChargedFee.MINIMUM_VALUE) return null

        String description = Utils.getMessageProperty("feeDescription.phoneCallNotificationFee").capitalize()
        String financialDescription = buildDefaultFinancialDescription(description, payment.getInvoiceNumber(), payment.customerAccount.name)
        return save(payment.provider, [value: phoneCallNotificationFee, type: ChargedFeeType.PAYMENT_PHONE_CALL_NOTIFICATION, payment: payment, financialDescription: financialDescription])
    }

    public ChargedFee saveInstantTextMessageFee(Customer customer, InstantTextMessage instantTextMessage) {
        BigDecimal instantTextMessageFee = CustomerFee.calculateWhatsAppNotificationFeeValue(customer)
        if (instantTextMessageFee < ChargedFee.MINIMUM_VALUE) return null

        ChargedFeeType instantTextMessageFeeType = ChargedFeeType.PAYMENT_INSTANT_TEXT_MESSAGE
        String financialDescription = "Taxa de notificação por WhatsApp da cobrança ${instantTextMessage.notificationRequest.payment.getInvoiceNumber()} ${instantTextMessage.notificationRequest.payment.customerAccount.name}"

        return save(customer, [value: instantTextMessageFee, type: instantTextMessageFeeType, instantTextMessage: instantTextMessage, financialDescription: financialDescription])
    }

    public ChargedFee saveInstantNotificationSentFee(Payment payment) {
        BigDecimal instantTextMessageFee = CustomerFee.calculateWhatsAppNotificationFeeValue(payment.provider)
        if (instantTextMessageFee < ChargedFee.MINIMUM_VALUE) return null

        ChargedFeeType instantTextMessageFeeType = ChargedFeeType.PAYMENT_INSTANT_TEXT_MESSAGE
        String financialDescription = "Taxa de notificação por WhatsApp da cobrança ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"

        return save(payment.provider, [value: instantTextMessageFee, type: instantTextMessageFeeType, payment: payment, financialDescription: financialDescription])
    }

    public ChargedFee saveAsaasCardRequestFee(Customer customer, AsaasCard asaasCard, BigDecimal value) {
        String financialDescription = "Taxa de adesão do cartão ${asaasCard.holder.name}"
        ChargedFeeType asaasCardRequestFeeType
        if (asaasCard.type.isDebit()) {
            asaasCardRequestFeeType = ChargedFeeType.ASAAS_DEBIT_CARD_REQUEST
        } else {
            asaasCardRequestFeeType = ChargedFeeType.ASAAS_PREPAID_CARD_REQUEST
        }

        return save(customer, [value: value, type: asaasCardRequestFeeType, financialDescription: financialDescription, asaasCard: asaasCard])
    }

    public ChargedFee refundAsaasCardRequestFee(AsaasCard asaasCard) {
        ChargedFeeType type = asaasCard.type.isDebit() ? ChargedFeeType.ASAAS_DEBIT_CARD_REQUEST : ChargedFeeType.ASAAS_PREPAID_CARD_REQUEST
        ChargedFee chargedFee = ChargedFee.debited([customer: asaasCard.customer, type: type, object: asaasCard]).get()
        if (!chargedFee) throw new BusinessException("A taxa não foi cobrada ou já foi estornada")
        String refundTransactionDescription = "Estorno da taxa de adesão do cartão ${chargedFee.asaasCard.holder.name}"

        return refund(chargedFee.customer, type, chargedFee.value, asaasCard, refundTransactionDescription)
    }

    public ChargedFee saveKnownYourCustomerRequestBatchFee(Customer customer, KnownYourCustomerRequestBatch knownYourCustomerRequestBatch) {
        BigDecimal value = knownYourCustomerRequestBatch.value
        ChargedFeeType knownYourCustomerBatchFeeType = ChargedFeeType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_BATCH
        String financialDescription = "Taxa de criação de subconta - ${knownYourCustomerRequestBatch.getFinancialDescriptionDetail()}"

        return save(customer, [value: value, type: knownYourCustomerBatchFeeType, knownYourCustomerRequestBatch: knownYourCustomerRequestBatch, financialDescription: financialDescription])
    }

    public ChargedFee savePaymentOriginChannelFeeIfNecessary(Payment payment) {
        OriginChannel originChannel = PaymentOriginRequesterInfo.query([column: "originChannel", paymentId: payment.id, readOnly: true]).get()
        if (!originChannel) return null

        Boolean alreadyFee = ChargedFee.query(["exists": true, customer: payment.provider, type: ChargedFeeType.PAYMENT_ORIGIN_CHANNEL, payment: payment, status: ChargedFeeStatus.DEBITED]).get().asBoolean()
        if (alreadyFee) return null

        BigDecimal originChannelFee = paymentOriginChannelFeeConfigService.calculateFee(originChannel, payment.billingType, payment.value)
        if (!originChannelFee) return null

        String description = "Taxa do Gateway"
        String financialDescription = buildDefaultFinancialDescription(description, payment.getInvoiceNumber(), payment.customerAccount.name)
        return save(payment.provider, [value: originChannelFee, type: ChargedFeeType.PAYMENT_ORIGIN_CHANNEL, payment: payment, financialDescription: financialDescription])
    }

    private String buildPixDebitFinancialDescription(PixTransaction pixTransaction) {
        if (pixTransaction.cashValueFinality) {
            if (pixTransaction.cashValueFinality.isWithdrawal()) {
                return "Taxa para Saque Pix"
            } else {
                return "Taxa para Troco Pix"
            }
        }
        if (pixTransaction.originType.isManual()) {
            return "Taxa para Pix com dados manuais"
        } else {
            return "Taxa para Pix com chave"
        }
    }

    private String buildPixCreditFinancialDescription(PixTransaction pixTransaction) {
        String description = "Taxa de transferência Pix recebida"

        switch (pixTransaction.originType) {
            case PixTransactionOriginType.MANUAL:
                description += " com dados manuais"
                break
            case PixTransactionOriginType.ADDRESS_KEY:
                description += " com chave"
                break
            case PixTransactionOriginType.STATIC_QRCODE:
                description += " com QR Code estático"
                break
        }

        return description
    }

    private ChargedFee save(Customer customer, Map chargedFeeMap) {
        ChargedFee chargedFee = new ChargedFee([customer: customer] + chargedFeeMap)
        chargedFee.status = ChargedFeeStatus.DEBITED
        chargedFee.save(flush: true, failOnError: true)

        BigDecimal valueWithDiscountApplied = promotionalCodeService.consumeFeeDiscountBalance(customer, chargedFee.value, chargedFee)
        chargedFee.value = valueWithDiscountApplied
        chargedFee.save(failOnError: true)

        BigDecimal promotionalCodeCreditValue = PromotionalCodeUse.sumValue([consumerObject: chargedFee]).get()

        if (promotionalCodeCreditValue) {
            chargedFeeMap.promotionalCodeDescription = "Desconto na taxa ${buildPromotionalCodeDescription(chargedFee)}"
        }

        financialTransactionService.saveChargedFee(chargedFee, chargedFeeMap.financialDescription, chargedFeeMap.promotionalCodeDescription, promotionalCodeCreditValue)
        return chargedFee
    }

    private ChargedFee refund(Customer customer, ChargedFeeType type, BigDecimal value, domainInstance, String refundTransactionDescription) {
        Map search = [:]
        search.customer = customer
        search.type = type
        search.value = value
        search.status = ChargedFeeStatus.DEBITED
        if (domainInstance) search.object = domainInstance

        ChargedFee chargedFee = ChargedFee.query(search).get()
        if (!chargedFee) return

        chargedFee.status = ChargedFeeStatus.REFUNDED
        chargedFee.save(flush: true, failOnError: true)

        List<PromotionalCodeUse> promotionalCodeUsedList = promotionalCodeService.reverseDiscountValueUsed(chargedFee)
        BigDecimal promotionalCodeUsedValue
        String refundPromotionalCodeDescription
        if (promotionalCodeUsedList) {
            promotionalCodeUsedValue = promotionalCodeUsedList*.value.sum() ?: 0
            if (promotionalCodeUsedValue) {
                refundPromotionalCodeDescription = "Estorno do desconto na taxa ${buildPromotionalCodeDescription(chargedFee)}"
            }
        }

        financialTransactionService.refundChargedFee(chargedFee, refundTransactionDescription, refundPromotionalCodeDescription, promotionalCodeUsedValue)
        return chargedFee
    }

    private String buildDefaultFinancialDescription(String description, String invoiceNumber, String customerAccountName) {
        return "${description} - fatura nr. ${invoiceNumber} ${customerAccountName}"
    }

    private String buildPromotionalCodeDescription(ChargedFee chargedFee) {
        if (chargedFee.productInvoice) {
            String productInvoiceProvider = Utils.getMessageProperty("productInvoiceProvider.${chargedFee.productInvoice.productInvoiceProvider}")
            return "de emissão da nota fiscal de produto emitida via ${productInvoiceProvider} nr. ${chargedFee.productInvoice.number}"
        }
        if (chargedFee.payment) {
            if (chargedFee.type.isPaymentMessagingNotification()) {
                return "de mensageria - fatura nr. ${chargedFee.payment.getInvoiceNumber()} ${chargedFee.payment.customerAccount.name}"
            }

            if (chargedFee.type.isPaymentSmsNotification()) {
                return "de notificação por SMS - fatura nr. ${chargedFee.payment.getInvoiceNumber()}"
            }
        }

        if (chargedFee.instantTextMessage) {
            return "de notificação por WhatsApp - fatura nr. ${chargedFee.instantTextMessage.notificationRequest.payment.getInvoiceNumber()}"
        }

        if (chargedFee.phoneCallNotification) {
            return "de notificação por voz - fatura nr. ${chargedFee.phoneCallNotification.notificationRequest.payment.getInvoiceNumber()}"
        }

        if (chargedFee.creditBureauReport) {
            String promotionalCodeDescription = "de consulta Serasa do "
            String cpfCnpj = chargedFee.creditBureauReport.cpfCnpj

            if (CpfCnpjUtils.isCpf(cpfCnpj)) {
                promotionalCodeDescription += "CPF "
            } else {
                promotionalCodeDescription += "CNPJ "
            }
            promotionalCodeDescription += CpfCnpjUtils.formatCpfCnpj(cpfCnpj)
            return promotionalCodeDescription
        }

        if (chargedFee.dunning) {
            return "para negativação - fatura nr. ${chargedFee.dunning.payment.getInvoiceNumber()}"
        }

        if (chargedFee.type.isPix()) {
            return "para ${Utils.getMessageProperty("PixTransactionOriginType.${chargedFee.pixTransaction.originType}")}"
        }

        if (chargedFee.knownYourCustomerRequestBatch) {
            return "de criação de subcontas - ${chargedFee.knownYourCustomerRequestBatch.getFinancialDescriptionDetail()}"
        }

        if (chargedFee.type.isContractedCustomerPlan()) {
            String planName = CustomerPlan.query([column: "plan.name", customer: chargedFee.customer]).get()
            return "de mensalidade do plano ${Utils.getMessageProperty("customerPlan.${planName}.label")} do Asaas"
        }

        return null
    }
}
