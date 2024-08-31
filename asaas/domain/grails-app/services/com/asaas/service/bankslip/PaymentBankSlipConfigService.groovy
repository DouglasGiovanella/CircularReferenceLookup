package com.asaas.service.bankslip

import com.asaas.boleto.BankSlipDocumentType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.bankslip.PaymentBankSlipConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentBankSlipConfigService {

    List<Long> supportedBoletoBankIdList = [
        Payment.SANTANDER_BOLETO_BANK_ID,
        Payment.SANTANDER_ONLINE_BOLETO_BANK_ID,
        Payment.SICREDI_BOLETO_BANK_ID,
        Payment.SAFRA_BOLETO_BANK_ID,
        Payment.ASAAS_ONLINE_BOLETO_BANK_ID
    ]

    public void save(Payment payment, Map params) {
        if (!PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) return
        if (payment.duplicatedPayment) return

        String bankSlipDocumentTypeParam = CustomerParameter.getStringValue(payment.provider, CustomerParameterName.CUSTOM_BANK_SLIP_DOCUMENT_TYPE)

        if (Utils.isEmptyOrNull(params.daysAfterDueDateToRegistrationCancellation) && !bankSlipDocumentTypeParam) return

        if (!supportedBoletoBankIdList.contains(payment.boletoBank.id)) {
            throw new BusinessException("O banco usado no registro dos boletos não suporta o parâmetro daysAfterDueDateToRegistrationCancellation.")
        }

        if (params.daysAfterDueDateToRegistrationCancellation && Integer.valueOf(params.daysAfterDueDateToRegistrationCancellation) < 0) {
            throw new BusinessException("O parâmetro daysAfterDueDateToRegistrationCancellation deve ser maior ou igual a zero.")
        }

        PaymentBankSlipConfig paymentBankSlipConfig = findOrCreate(payment)
        paymentBankSlipConfig.daysToAutomaticRegistrationCancellation = params.daysAfterDueDateToRegistrationCancellation
        if (bankSlipDocumentTypeParam) paymentBankSlipConfig.customDocumentType = BankSlipDocumentType.parse(bankSlipDocumentTypeParam)
        paymentBankSlipConfig.save()
    }

    private PaymentBankSlipConfig findOrCreate(Payment payment) {
        PaymentBankSlipConfig paymentBankSlipConfig

        paymentBankSlipConfig = PaymentBankSlipConfig.query([payment: payment]).get()

        if (paymentBankSlipConfig) {
            return paymentBankSlipConfig
        }

        paymentBankSlipConfig = new PaymentBankSlipConfig()
        paymentBankSlipConfig.payment = payment

        return paymentBankSlipConfig
    }
}
