package com.asaas.service.bankslip

import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.payment.Payment
import com.asaas.payment.PaymentBuilder
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class PaymentBankSlipInfoService {

    def boletoBatchFileItemService
    def paymentHistoryService

    public PaymentBankSlipInfo save(Payment payment, Map params) {
        PaymentBankSlipInfo bankSlipInfo = new PaymentBankSlipInfo()
        bankSlipInfo.payment = payment
        bankSlipInfo.nossoNumero = PaymentBuilder.buildNossoNumero(payment)
        bankSlipInfo.dueDate = CustomDateUtils.toDate(params.dueDate)
        bankSlipInfo.applyFineAndInterest = Boolean.valueOf(params.applyFineAndInterest)
        bankSlipInfo.applyDiscount = Boolean.valueOf(params.applyDiscount)
        bankSlipInfo.applyInstructions = Boolean.valueOf(params.applyInstructions)
        bankSlipInfo.save(failOnError: true, flush: true)

        return bankSlipInfo
    }

    public void deleteAndSaveHistory(PaymentBankSlipInfo paymentBankSlipInfo) {
        delete(paymentBankSlipInfo)
        cancelBankSlipRegistrationIfNecessary(paymentBankSlipInfo)
        paymentHistoryService.save(paymentBankSlipInfo.payment, paymentBankSlipInfo.nossoNumero, paymentBankSlipInfo.payment.boletoBank?.id ?: null)
    }

    private void delete(PaymentBankSlipInfo paymentBankSlipInfo) {
        paymentBankSlipInfo.deleted = true
        paymentBankSlipInfo.save(failOnError: true)
    }

    private void cancelBankSlipRegistrationIfNecessary(PaymentBankSlipInfo paymentBankSlipInfo) {
        BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.query([payment: paymentBankSlipInfo.payment, nosso_numero: paymentBankSlipInfo.nossoNumero, action: BoletoAction.CREATE]).get()
        if (!boletoBatchFileItem) return

        if (boletoBatchFileItem.boletoBankId != Payment.BRADESCO_ONLINE_BOLETO_BANK_ID) {
            boletoBatchFileItemService.saveDeleteItem(paymentBankSlipInfo.payment, paymentBankSlipInfo.nossoNumero)
        }
    }
}
