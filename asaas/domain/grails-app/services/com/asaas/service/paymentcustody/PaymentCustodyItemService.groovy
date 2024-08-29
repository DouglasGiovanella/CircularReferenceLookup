package com.asaas.service.paymentcustody

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.paymentcustody.PaymentCustody
import com.asaas.domain.paymentcustody.PaymentCustodyItem
import com.asaas.domain.split.PaymentRefundSplit
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.BusinessException
import com.asaas.paymentcustody.PaymentCustodyItemType
import grails.transaction.Transactional

@Transactional
class PaymentCustodyItemService {

    def financialTransactionService

    public void blockPayment(PaymentCustody paymentCustody, Payment payment) {
        BigDecimal paymentBlockValue = calculatePaymentBlockValue(payment)
        save(paymentCustody, payment.provider, payment, null, PaymentCustodyItemType.BLOCK, paymentBlockValue)
    }

    public void blockPaymentSplit(PaymentCustody paymentCustody, PaymentSplit paymentSplit) {
        BigDecimal paymentSplitBlockValue = calculatePaymentSplitBlockValue(paymentSplit)
        save(paymentCustody, paymentSplit.wallet.destinationCustomer, null, paymentSplit, PaymentCustodyItemType.BLOCK, paymentSplitBlockValue)
    }

    public void processPaymentRefund(PaymentCustodyItem paymentCustodyItem, PaymentRefund paymentRefund) {
        BigDecimal paymentBlockedValue = getBlockedValue(paymentRefund.payment, null)
        BigDecimal paymentRefundSplitValue = PaymentRefundSplit.sumValueAbs([paymentRefund: paymentRefund]).get()
        BigDecimal reverseValue = paymentRefund.value - paymentRefundSplitValue

        if (reverseValue > paymentBlockedValue) reverseValue = paymentBlockedValue

        save(paymentCustodyItem.paymentCustody, paymentCustodyItem.customer, paymentCustodyItem.payment, null, PaymentCustodyItemType.REVERSAL, reverseValue)
    }

    public void processPaymentRefundSplit(PaymentCustodyItem paymentCustodyItem, PaymentRefundSplit paymentRefundSplit) {
        BigDecimal paymentSplitBlockedValue = getBlockedValue(null, [paymentRefundSplit.paymentSplit.id])
        BigDecimal reverseValue = paymentRefundSplit.value

        if (reverseValue > paymentSplitBlockedValue) reverseValue = paymentSplitBlockedValue

        save(paymentCustodyItem.paymentCustody, paymentCustodyItem.customer, null, paymentCustodyItem.paymentSplit, PaymentCustodyItemType.REVERSAL, reverseValue)
    }

    public Boolean reverse(PaymentCustody paymentCustody, String walletPublicId) {
        Map queryParams = [paymentCustody: paymentCustody, type: PaymentCustodyItemType.BLOCK]
        if (walletPublicId) queryParams."walletPublicId" = walletPublicId
        List<PaymentCustodyItem> paymentCustodyBlockItemList = PaymentCustodyItem.query(queryParams).list()

        if (walletPublicId && !paymentCustodyBlockItemList) throw new BusinessException("A carteira ${walletPublicId} não faz parte da custódia ${paymentCustody.publicId}.")

        for (PaymentCustodyItem paymentCustodyItem : paymentCustodyBlockItemList) {
            BigDecimal blockedValue = getBlockedValue(paymentCustodyItem.payment, [paymentCustodyItem.paymentSplit?.id])
            if (!blockedValue) continue

            reverseCustodyItem(paymentCustodyItem.payment, paymentCustodyItem.paymentSplit, paymentCustody, blockedValue)
        }

        if (!walletPublicId) return true

        List<Long> paymentCustodyPaymentSplitList = PaymentCustodyItem.query([column: "paymentSplit.id", paymentCustody: paymentCustody, "paymentSplit[isNotNull]": true, type: PaymentCustodyItemType.BLOCK]).list()
        BigDecimal paymentSplitsBlockedValue = getBlockedValue(null, paymentCustodyPaymentSplitList)
        if (paymentSplitsBlockedValue > 0) return false

        BigDecimal blockedValue = getBlockedValue(paymentCustody.payment, null)
        if (blockedValue) reverseCustodyItem(paymentCustody.payment, null, paymentCustody, blockedValue)

        return true
    }

    public BigDecimal getBlockedValue(Payment payment, List<Long> paymentSplitIdList) {
        Map queryParams = [:]
        if (payment) {
            queryParams.payment = payment
        } else {
            queryParams.paymentSplitIdList = paymentSplitIdList
        }

        BigDecimal blockedValue = PaymentCustodyItem.sumValueAbs(queryParams + [type: PaymentCustodyItemType.BLOCK]).get()
        BigDecimal reversedValue = PaymentCustodyItem.sumValueAbs(queryParams + [type: PaymentCustodyItemType.REVERSAL]).get()

        return blockedValue - reversedValue
    }

    public BigDecimal calculatePaymentBlockValue(Payment payment) {
        BigDecimal valueCompromisedWithSplit = payment.getValueCompromisedWithPaymentSplit()
        BigDecimal remainingPaymentNetValue = payment.getRemainingRefundNetValue()

        return (remainingPaymentNetValue - valueCompromisedWithSplit)
    }

    private BigDecimal calculatePaymentSplitBlockValue(PaymentSplit paymentSplit) {
        BigDecimal totalSplitRefundedValue = PaymentRefundSplit.sumValueAbs([paymentSplit: paymentSplit]).get()
        BigDecimal paymentSplitBlockValue = paymentSplit.totalValue

        return (paymentSplitBlockValue - totalSplitRefundedValue)
    }

    private PaymentCustodyItem save(PaymentCustody paymentCustody, Customer customer, Payment payment, PaymentSplit paymentSplit, PaymentCustodyItemType type, BigDecimal value) {
        PaymentCustodyItem paymentCustodyItem = new PaymentCustodyItem()
        paymentCustodyItem.paymentCustody = paymentCustody
        paymentCustodyItem.type = type
        paymentCustodyItem.payment = payment
        paymentCustodyItem.paymentSplit = paymentSplit
        paymentCustodyItem.customer = customer
        paymentCustodyItem.value = value
        paymentCustodyItem.save(failOnError: true)

        String description = buildFinancialTransactionDescription(paymentCustodyItem)
        if (type.isBlock()) {
            financialTransactionService.saveCustody(paymentCustodyItem, description)
        } else {
            financialTransactionService.saveCustodyReversal(paymentCustodyItem, description)
        }

        return paymentCustodyItem
    }

    private buildFinancialTransactionDescription(PaymentCustodyItem paymentCustodyItem) {
        String originDescription
        if (paymentCustodyItem.payment) {
            originDescription = "fatura nr. ${paymentCustodyItem.payment.getInvoiceNumber()} ${paymentCustodyItem.payment.customerAccount.name}"
        } else {
            originDescription = "comissão recebida do parceiro ${paymentCustodyItem.paymentCustody.customer.getProviderName()} - fatura nr. ${paymentCustodyItem.paymentCustody.payment.getInvoiceNumber()}"
        }

        return "${paymentCustodyItem.type.isBlock() ? 'Bloqueio' : 'Desbloqueio'} de saldo por custódia da ${originDescription}"
    }

    private void reverseCustodyItem(Payment payment, PaymentSplit paymentSplit, PaymentCustody paymentCustody, BigDecimal blockedValue) {
        if (payment) {
            save(paymentCustody, payment.provider, payment, null, PaymentCustodyItemType.REVERSAL, blockedValue)
        } else {
            save(paymentCustody, paymentSplit.wallet.destinationCustomer, null, paymentSplit, PaymentCustodyItemType.REVERSAL, blockedValue)
        }
    }
}
