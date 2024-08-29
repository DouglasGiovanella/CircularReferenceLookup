package com.asaas.service.asaasmoney

import com.asaas.asaasmoney.AsaasMoneyPaymentCompromisedBalanceStatus
import com.asaas.domain.asaasmoney.AsaasMoneyPaymentCompromisedBalance
import com.asaas.domain.creditcard.CreditCardTransactionAnalysis
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class AsaasMoneyPaymentCompromisedBalanceService {

    def financialTransactionService

    public Boolean isNecessaryCreate(Payment payment, Map params) {
        if (!params.asaasMoneyCashOutBalanceValue) return false
        if (params.asaasMoneyCashOutBalanceValue < 0.00) {
            AsaasLogger.error("AsaasMoneyPaymentCompromisedBalanceService.saveIfNecessary >> O Valor para reserva informado está negativo no pagamento da cobrança [${payment.id}]!]")
            throw new RuntimeException("Valor para reserva de saldo não deve ser negativo")
        }
        if (!payment.provider.isAsaasMoneyProvider()) return false
        if (!params.payerCustomerPublicId) {
            AsaasLogger.error("AsaasMoneyPaymentCompromisedBalanceService.saveIfNecessary >> payerCustomerPublicId não informado no pagamento da cobrança [${payment.id}]!]")
            throw new RuntimeException("Pagador não informado para efetuar reserva de saldo")
        }
        Boolean customerExists = Customer.query([exists: true, publicId: params.payerCustomerPublicId]).get().asBoolean()
        if (!customerExists) {
            AsaasLogger.error("AsaasMoneyPaymentCompromisedBalanceService.saveIfNecessary >> Erro ao salvar a reserva de saldo utilizado no pagamento da cobrança [${payment.id}] cliente [${params.payerCustomerPublicId} inválido!]")
            throw new RuntimeException("O Pagador informado não foi encontrado para efetuar reserva de saldo")
        }

        return true
    }

    public AsaasMoneyPaymentCompromisedBalance saveIfNecessary(Payment payment, Map params) {
        if (!payment.isAwaitingRiskAnalysis()) return null
        Customer payerCustomer = Customer.query([publicId: params.payerCustomerPublicId]).get()

        AsaasMoneyPaymentCompromisedBalance paymentCompromisedBalance = new AsaasMoneyPaymentCompromisedBalance()
        paymentCompromisedBalance.payerCustomer = payerCustomer
        paymentCompromisedBalance.value = params.asaasMoneyCashOutBalanceValue
        paymentCompromisedBalance.status = AsaasMoneyPaymentCompromisedBalanceStatus.DEBITED

        if (payment.installment) {
            paymentCompromisedBalance.installment = payment.installment
        } else {
            paymentCompromisedBalance.payment = payment
        }

        paymentCompromisedBalance.save(failOnError: true)

        financialTransactionService.saveAsaasMoneyPaymentCompromisedBalance(paymentCompromisedBalance)

        return paymentCompromisedBalance
    }

    public void refund(CreditCardTransactionAnalysis transactionAnalysis) {
        Map queryParameter = [:]
        if (transactionAnalysis.installment) {
            queryParameter.installment =  transactionAnalysis.installment
        } else {
            queryParameter.payment = transactionAnalysis.payment
        }

        AsaasMoneyPaymentCompromisedBalance cashInBalanceBlock = AsaasMoneyPaymentCompromisedBalance.query(queryParameter).get()
        if (!cashInBalanceBlock) return

        cashInBalanceBlock.status = AsaasMoneyPaymentCompromisedBalanceStatus.REFUNDED
        cashInBalanceBlock.save(failOnError: true)

        financialTransactionService.reverseAsaasMoneyPaymentCompromisedBalance(cashInBalanceBlock)
    }
}
