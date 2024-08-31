package com.asaas.service.freepayment

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.freepaymentconfig.FreePaymentUse
import com.asaas.domain.payment.Payment
import com.asaas.financialtransaction.FinancialTransactionType

import grails.transaction.Transactional

@Transactional
class FreePaymentUseService {

    public BigDecimal calculateValueToCredit(Payment payment) {
        Boolean valueHasAlreadyBeenCredited = FinancialTransaction.query([exists: true, transactionType: FinancialTransactionType.FREE_PAYMENT_USE, payment: payment]).get().asBoolean()
        if (valueHasAlreadyBeenCredited) return new BigDecimal(0)

        BigDecimal feeDiscountApplied = FreePaymentUse.query([column: "feeDiscountApplied", payment: payment]).get()
        return feeDiscountApplied ?: new BigDecimal(0)
    }
}
