package com.asaas.service.customerdealinfo.feeconfig.product

import com.asaas.domain.customerfee.CustomerFee
import com.asaas.feenegotiation.FeeNegotiationReplicationType

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoApplyFeeConfigService {

    def feeAdminChildAccountReplicationService
    def feeAdminService

    public void applyWhatsappNotificationFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee whatsappNotificationFee = feeAdminService.updateFee(customerId, [whatsappNotificationFee: value], false)
            if (whatsappNotificationFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de notificação Whatsapp", whatsappNotificationFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [whatsappNotificationFee: value.toString()])
    }

    public void applyPaymentMessagingNotificationFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee messagingNotificationFee = feeAdminService.updatePaymentMessagingNotificationFee(customerId, value)
            if (messagingNotificationFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de mensageria", messagingNotificationFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [paymentMessagingNotificationFeeValue: value.toString()])
    }

    public void applyPixDebitFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee pixDebitFee = feeAdminService.updateFee(customerId, [pixDebitFee: value], false)
            if (pixDebitFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de Débito Pix (Pix Cash Out)", pixDebitFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [pixDebitFee: value.toString()])
    }

    public void applyChildAccountKnownYourCustomerFee(Long customerId, BigDecimal value) {
        CustomerFee childAccountKnownYourCustomerFee = feeAdminService.updateFee(customerId, [childAccountKnownYourCustomerFee: value], false)
        if (childAccountKnownYourCustomerFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de criação de subcontas", childAccountKnownYourCustomerFee.errors)
    }

    public void applyServiceInvoiceFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee serviceInvoiceFee = feeAdminService.updateFee(customerId, [invoiceValue: value], false)
            if (serviceInvoiceFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de nota fiscal de serviço", serviceInvoiceFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [invoiceValue: value.toString()])
    }

    public void applyCreditBureauReportFee(Long customerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        Map itemParams = itemParamsList.groupBy { it.productFeeType }
        BigDecimal legalPersonFee = itemParams.legalPersonFee.value.first()
        BigDecimal naturalPersonFee = itemParams.naturalPersonFee.value.first()

        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee creditBureauReportFee = feeAdminService.updateFee(customerId, [creditBureauReportLegalPersonFee: legalPersonFee, creditBureauReportNaturalPersonFee: naturalPersonFee], false)
            if (creditBureauReportFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de Consulta Serasa", creditBureauReportFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [creditBureauReportNaturalPersonFee: naturalPersonFee.toString(), creditBureauReportLegalPersonFee: legalPersonFee.toString()])
    }

    public void applyTransferFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee transferFee = feeAdminService.updateFee(customerId, [transferValue: value], false)
            if (transferFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de transferência", transferFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [transferValue: value.toString()])
    }

    public void applyDunningCreditBureauFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee dunningCreditBureauFee = feeAdminService.updateFee(customerId, [dunningCreditBureauFeeValue: value], false)
            if (dunningCreditBureauFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de negativação Serasa", dunningCreditBureauFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [dunningCreditBureauFeeValue: value.toString()])
    }

    public void applyPhoneCallNotificationFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            CustomerFee phoneCallNotificationFee = feeAdminService.updateFee(customerId, [phoneCallNotificationFee: value], false)
            if (phoneCallNotificationFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de notificação por robô de voz", phoneCallNotificationFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customerId, [phoneCallNotificationFee: value.toString()])
    }
}
