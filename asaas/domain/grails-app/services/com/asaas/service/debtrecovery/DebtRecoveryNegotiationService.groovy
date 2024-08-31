package com.asaas.service.debtrecovery

import com.asaas.billinginfo.BillingType
import com.asaas.debtrecovery.DebtRecoveryNegotiationPaymentStatus
import com.asaas.debtrecovery.DebtRecoveryNegotiationPaymentType
import com.asaas.debtrecovery.DebtRecoveryNegotiationStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.domain.debtrecovery.DebtRecovery
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiation
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiationPayment
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.totalVoice.manager.TotalVoiceManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class DebtRecoveryNegotiationService {

    def boletoService
    def customerMessageService
    def debtRecoveryNegotiationPaymentService
    def grailsApplication
    def installmentService
    def linkService
    def paymentService
    def phoneCallNotificationService
    def smsSenderService

    public DebtRecoveryNegotiation save(DebtRecovery debtRecovery, BigDecimal netValue, BigDecimal chargeValue, Map params) {
        validateSave(debtRecovery)

        DebtRecoveryNegotiation debtRecoveryNegotiation = new DebtRecoveryNegotiation()
        debtRecoveryNegotiation.debtRecovery = debtRecovery
        debtRecoveryNegotiation.debtorCustomer = debtRecovery.debtorCustomer
        debtRecoveryNegotiation.status = DebtRecoveryNegotiationStatus.IN_PROGRESS
        debtRecoveryNegotiation.value = netValue + chargeValue
        debtRecoveryNegotiation.chargeValue = chargeValue
        debtRecoveryNegotiation.netValue = netValue
        debtRecoveryNegotiation.save(failOnError: true)

        savePayments(debtRecoveryNegotiation, netValue, params)

        return debtRecoveryNegotiation
    }

    public void renegotiate(DebtRecoveryNegotiation negotiation, Map params) {
        validateRenegotiate(negotiation)

        debtRecoveryNegotiationPaymentService.deleteRemainingInstallmentPayments(negotiation)

        negotiation.chargeValue = Utils.toBigDecimal(params.chargeValue)
        negotiation.value = negotiation.netValue + negotiation.chargeValue
        negotiation.status = DebtRecoveryNegotiationStatus.IN_PROGRESS
        negotiation.save(failOnError: true)
        savePayments(negotiation, negotiation.debtRecovery.getRemainingValue(), params)

        DebtRecoveryNegotiationPayment masterPayment = negotiation.getMasterPayment()
        Boolean shouldCreatePaymentDunning = (negotiation.debtRecovery.paymentDunningEnabled && !masterPayment.paymentDunning)
        if (shouldCreatePaymentDunning) debtRecoveryNegotiationPaymentService.saveDunning(masterPayment, params)
    }

    public void cancel(DebtRecoveryNegotiation negotiation) {
        if (!DebtRecoveryNegotiationStatus.getCancellableList().contains(negotiation.status)) throw new BusinessException("Não é possível cancelar a negociação.")

        negotiation.status = DebtRecoveryNegotiationStatus.CANCELLED
        negotiation.save(flush: true, failOnError: true)

        debtRecoveryNegotiationPaymentService.cancelMasterPaymentIfNecessary(negotiation)
        debtRecoveryNegotiationPaymentService.cancelInstallmentPaymentsIfNecessary(negotiation)
    }

    public void validateRenegotiate(DebtRecoveryNegotiation negotiation) {
        if (hasRecoveredValueInCurrentNegotiation(negotiation)) throw new BusinessException("Não é possível alterar uma negociação com pagamentos efetuados. Uma nova negociação deve ser gerada para o saldo restante.")

        if (negotiation.status.isPaid()) throw new BusinessException("Não é possível alterar uma negociação concluída.")
    }

    public Boolean hasRecoveredValueInCurrentNegotiation(DebtRecoveryNegotiation negotiation) {
        Map search = [:]
        search.debtRecoveryNegotiation = negotiation
        search."status[in]" = DebtRecoveryNegotiationPaymentStatus.getRecoveredList()
        search.exists = true

        return DebtRecoveryNegotiationPayment.query(search).get().asBoolean()
    }

    private void savePayments(DebtRecoveryNegotiation negotiation, BigDecimal debtValue, Map params) {
        Map negotiatedParams = buildNegotiationConditions(negotiation, debtValue, params)

        validateNegotiationConditions(debtValue, negotiatedParams)

        saveMasterNegotiationPayment(negotiation, negotiatedParams)

        if (negotiatedParams.hasEntryPayment) saveEntryNegotiationPayment(negotiation, negotiatedParams)

        saveInstallmentNegotiationPayment(negotiation, negotiatedParams)

        notify(negotiation)
    }

    private Map buildNegotiationConditions(DebtRecoveryNegotiation negotiation, BigDecimal debtValue, Map params) {
        Map negotiatedParams = params + [customer: negotiation.debtRecovery.debtorCustomer,
                                         customerAccount: negotiation.debtRecovery.debtorCustomerAccount,
                                         description: Utils.getMessageProperty("debtRecovery.payment.description", [CpfCnpjUtils.formatCpfCnpj(negotiation.debtorCustomer.cpfCnpj)]),
                                         billingType: BillingType.BOLETO,
                                         masterChargeValue: 0,
                                         masterPaymentValue: debtValue < DebtRecovery.MINIMUM_BANK_SLIP_VALUE ? DebtRecovery.MINIMUM_BANK_SLIP_VALUE : debtValue]

        BigDecimal entryValue = Utils.toBigDecimal(negotiatedParams.entryValue) ?: 0
        BigDecimal totalChargeValue = Utils.toBigDecimal(negotiatedParams.chargeValue) ?: 0
        Integer installmentCount = Utils.toInteger(negotiatedParams.installmentCount) ?: 1

        negotiatedParams.hasInstallment = true
        negotiatedParams.installmentTotalValue = debtValue - entryValue + totalChargeValue
        negotiatedParams.installmentCount = installmentCount
        negotiatedParams.installmentChargeValue = BigDecimalUtils.roundDown(totalChargeValue / installmentCount)
        negotiatedParams.installmentDueDate = CustomDateUtils.toDate(negotiatedParams.installmentDueDate)

        negotiatedParams.entryValue = entryValue
        negotiatedParams.masterPaymentDueDate = negotiatedParams.installmentDueDate

        if (negotiatedParams.entryValue) {
            negotiatedParams.hasEntryPayment = true
            negotiatedParams.entryDueDate = CustomDateUtils.toDate(negotiatedParams.entryDueDate)
            negotiatedParams.masterPaymentDueDate = negotiatedParams.entryDueDate
            negotiatedParams.entryChargeValue = 0
        }

        return negotiatedParams
    }

    private void saveMasterNegotiationPayment(DebtRecoveryNegotiation negotiation, Map params) {
        DebtRecoveryNegotiationPayment masterNegotiationPayment = negotiation.getMasterPayment()
        if (!masterNegotiationPayment) {
            Map masterPaymentParams = params + [dueDate: params.masterPaymentDueDate, value: params.masterPaymentValue]

            Payment payment = paymentService.save(masterPaymentParams, true, false)

            debtRecoveryNegotiationPaymentService.save(negotiation, payment, DebtRecoveryNegotiationPaymentType.MASTER, params.masterChargeValue, params)
        }
    }

    private void saveEntryNegotiationPayment(DebtRecoveryNegotiation negotiation, Map negotiatedParams) {
        Map entryPaymentParams = negotiatedParams + [dueDate: negotiatedParams.entryDueDate, value: negotiatedParams.entryValue]

        Payment payment = paymentService.save(entryPaymentParams, true, false)

        debtRecoveryNegotiationPaymentService.save(negotiation, payment, DebtRecoveryNegotiationPaymentType.ENTRY_VALUE, negotiatedParams.entryChargeValue, null)
    }

    private void saveInstallmentNegotiationPayment(DebtRecoveryNegotiation negotiation, Map negotiatedParams) {
        Map installmentParams = negotiatedParams + [totalValue: negotiatedParams.installmentTotalValue, dueDate: negotiatedParams.installmentDueDate, value: null]
        Installment installment = installmentService.save(installmentParams, true, true)

        BigDecimal remainingTotalChargeValue = Utils.toBigDecimal(negotiatedParams.chargeValue) ?: 0
        BigDecimal installmentChargeValue = Utils.toBigDecimal(negotiatedParams.installmentChargeValue)

        DebtRecoveryNegotiationPayment lastNegotiationPaymentCreated
        for (Payment payment : installment.payments.sort({ it.id })) {
            remainingTotalChargeValue -= installmentChargeValue
            lastNegotiationPaymentCreated = debtRecoveryNegotiationPaymentService.save(negotiation, payment, DebtRecoveryNegotiationPaymentType.INSTALLMENT, installmentChargeValue, null)
        }

        if (remainingTotalChargeValue > 0) {
            lastNegotiationPaymentCreated.chargeValue += remainingTotalChargeValue
            lastNegotiationPaymentCreated.netValue = (lastNegotiationPaymentCreated.payment.value - lastNegotiationPaymentCreated.chargeValue)
            lastNegotiationPaymentCreated.save(failOnError: true)
        }
    }

    private void validateNegotiationConditions(BigDecimal debtValue, Map negotiatedParams) {
        if (!negotiatedParams.entryDueDate && negotiatedParams.entryValue) throw new BusinessException("Informe o vencimento da entrada negociada.")
        if (negotiatedParams.entryDueDate && !negotiatedParams.entryValue) throw new BusinessException("Informe o valor da entrada negociada.")

        if (negotiatedParams.installmentTotalValue <= 0) throw new BusinessException("Informe o valor total negociado.")
        if (!negotiatedParams.installmentCount) throw new BusinessException("Informe um número de parcelas.")
        if (!negotiatedParams.installmentDueDate) throw new BusinessException("Informe o vencimento da 1ª parcela negociada.")

        if (negotiatedParams.hasEntryPayment && !negotiatedParams.hasInstallment) throw new BusinessException("Informe as condições de parcelamento.")
        if (negotiatedParams.hasEntryPayment && negotiatedParams.entryDueDate >= negotiatedParams.installmentDueDate) throw new BusinessException("O vencimento da 1ª parcela negociada precisa ser posterior à data de vencimento da entrada.")

        if (debtValue > (negotiatedParams.entryValue + negotiatedParams.installmentTotalValue)) throw new BusinessException("Valor negociado está abaixo do valor do débito.")
    }

    private void validateSave(DebtRecovery debtRecovery) {
        Map search = [exists: true, debtRecovery: debtRecovery, "status[in]": [DebtRecoveryNegotiationStatus.IN_PROGRESS, DebtRecoveryNegotiationStatus.PAID]]
        Boolean hasAnotherNegotiation = DebtRecoveryNegotiation.query(search).get().asBoolean()
        if (hasAnotherNegotiation) throw new BusinessException("Há uma outra negociação para este débito.")
    }

    private void notify(DebtRecoveryNegotiation negotiation) {
        DebtRecoveryNegotiationPayment entryValueNegotiationPayment = negotiation.getEntryPayment()
        DebtRecoveryNegotiationPayment firstNegotiationPayment = negotiation.getFirstNegotiationInstallment()

        Map emailNotificationData = buildEmailNotificationData(firstNegotiationPayment, entryValueNegotiationPayment?.payment)

        if (CustomerDebtAppropriation.active([customer: negotiation.debtRecovery.debtorCustomer, exists: true]).get().asBoolean()) {
            customerMessageService.sendAppropriatedCustomerDebtRecoveryAlert(negotiation.debtRecovery.debtorCustomer, emailNotificationData)
            return
        }

        customerMessageService.sendDebtRecoveryAlert(negotiation.debtRecovery.debtorCustomer, emailNotificationData)

        DebtRecoveryNegotiationPayment negotiationPaymentNotifiableBySmsAndPhoneCall = entryValueNegotiationPayment ?: firstNegotiationPayment

        String smsMessage = "Olá! Sua conta Asaas está com saldo negativo. Acesse aqui o boleto para pagamento: ${linkService.viewInvoiceShort(negotiationPaymentNotifiableBySmsAndPhoneCall.payment)}"
        smsSenderService.send(smsMessage, negotiation.debtRecovery.debtorCustomer.mobilePhone, false, [:])

        sendPhoneCallNotification(negotiationPaymentNotifiableBySmsAndPhoneCall)
    }

    private Map buildEmailNotificationData(DebtRecoveryNegotiationPayment firstNegotiationPayment, Payment entryValuePayment ) {
        Map emailNotificationData = [:]
        emailNotificationData.payment = firstNegotiationPayment.payment
        emailNotificationData.negotiationPaymentCount = DebtRecoveryNegotiationPayment.query([debtRecoveryNegotiation: firstNegotiationPayment.debtRecoveryNegotiation, type: DebtRecoveryNegotiationPaymentType.INSTALLMENT]).count()
        emailNotificationData.invoiceUrl = linkService.viewInvoice(firstNegotiationPayment.payment)
        emailNotificationData.entryValuePayment = entryValuePayment
        if (entryValuePayment) emailNotificationData.entryValuePublicUrlShort = linkService.viewInvoiceShort(entryValuePayment)

        return emailNotificationData
    }

    private void sendPhoneCallNotification(DebtRecoveryNegotiationPayment negotiationPayment) {
        if (!AsaasEnvironment.isProduction()) return

        TotalVoiceManager totalVoiceManager = new TotalVoiceManager()
        totalVoiceManager.sendDebtRecoveryNegativeBalanceNotification(negotiationPayment.payment.value,
            negotiationPayment.payment.dueDate,
            negotiationPayment.debtRecovery.debtorCustomer.getProviderName(),
            negotiationPayment.debtRecovery.debtorCustomer.mobilePhone,
            boletoService.getLinhaDigitavel(negotiationPayment.payment))

        if (!totalVoiceManager.isSuccessful()) {
            AsaasLogger.error("DebtRecoveryNegotiationService.sendPhoneCallNotification >> A comunicação com a TotalVoice falhou.")
            return
        }

        Customer asaasCustomer = Customer.get(grailsApplication.config.asaas.debtRecoveryCustomer.id)

        phoneCallNotificationService.save(totalVoiceManager.responseBodyMap.dados.id.toString(), null, asaasCustomer)
    }
}
