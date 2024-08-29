package com.asaas.service.asaascardbillpayment

import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPaymentRefund

import grails.transaction.Transactional

@Transactional
class AsaasCardBillPaymentRefundService {

    def financialTransactionService

    public AsaasCardBillPaymentRefund save(AsaasCardBillPayment asaasCardBillPayment) {
        validate(asaasCardBillPayment)

        AsaasCardBillPaymentRefund asaasCardBillPaymentRefund = new AsaasCardBillPaymentRefund()
        asaasCardBillPaymentRefund.asaasCardBillPayment = asaasCardBillPayment
        asaasCardBillPaymentRefund.asaasCard = asaasCardBillPayment.asaasCard
        asaasCardBillPaymentRefund.customer = asaasCardBillPayment.customer
        asaasCardBillPaymentRefund.value = asaasCardBillPayment.value
        asaasCardBillPaymentRefund.save(failOnError: true)

        financialTransactionService.saveAsaasCardBillPaymentRefund(asaasCardBillPaymentRefund)

        return asaasCardBillPaymentRefund
    }

    private void validate(AsaasCardBillPayment asaasCardBillPayment) {
        if (!asaasCardBillPayment) throw new RuntimeException("Informe o pagamento a ser estornado.")
        if (!asaasCardBillPayment.method.isBalanceDebit()) throw new RuntimeException("Método de pagamento de fatura inválido para estorno")
    }
}
