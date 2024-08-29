package com.asaas.service.paymentcustody

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.paymentcustody.PaymentCustody
import com.asaas.domain.paymentcustody.PaymentCustodyItem
import com.asaas.domain.split.PaymentRefundSplit
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.BusinessException
import com.asaas.paymentcustody.PaymentCustodyFinishReason
import com.asaas.paymentcustody.PaymentCustodyItemType
import com.asaas.paymentcustody.PaymentCustodyStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentCustodyService {

    def customerParameterService
    def paymentCustodyCacheService
    def paymentCustodyItemService
    def receivableAnticipationValidationService

    public void saveIfNecessary(Payment payment) {
        if (!payment.isConfirmed()) return
        if (!isEnabledForCustomer(payment.provider)) return

        PaymentCustody paymentCustody = new PaymentCustody()
        paymentCustody.publicId = UUID.randomUUID()
        paymentCustody.payment = payment
        paymentCustody.custodianCustomer = getCustodianCustomer(payment.provider)
        paymentCustody.customer = payment.provider
        paymentCustody.status = PaymentCustodyStatus.ACTIVE
        paymentCustody.expirationDate = CustomDateUtils.sumDays(new Date().clearTime(), paymentCustodyCacheService.getCustodyDaysForCustomer(payment.provider.id))
        paymentCustody.save(failOnError: true)
    }

    public void processIfNecessary(Payment payment) {
        PaymentCustody paymentCustody = PaymentCustody.query([payment: payment, status: PaymentCustodyStatus.ACTIVE]).get()
        if (!paymentCustody) return

        paymentCustody.value = payment.getRemainingRefundNetValue()
        paymentCustody.save(failOnError: true)

        if (payment.isRefunded() || paymentCustody.value == 0) {
            finish(paymentCustody, PaymentCustodyFinishReason.PAYMENT_REFUNDED, null)
            return
        }

        if (!payment.isReceived()) throw new RuntimeException("PaymentCustodyService.processIfNecessary >> Falha ao processar a custódia ${paymentCustody.id}, a cobrança sob custódia não está recebida!")

        BigDecimal paymentBlockValue = paymentCustodyItemService.calculatePaymentBlockValue(payment)
        if (!payment.provider.hasSufficientBalance(paymentBlockValue)) {
            finish(paymentCustody, PaymentCustodyFinishReason.INSUFFICIENT_BALANCE, null)
            return
        }

        List<PaymentSplit> paymentSplitList = PaymentSplit.listActive(payment)
        for (PaymentSplit paymentSplit : paymentSplitList) {
            paymentCustodyItemService.blockPaymentSplit(paymentCustody, paymentSplit)
        }

        paymentCustodyItemService.blockPayment(paymentCustody, payment)

        if (getBlockedValue(paymentCustody) > paymentCustody.value) throw new RuntimeException("PaymentCustodyService.processIfNecessary >> O valor de custódia ${paymentCustody.id} excede o valor líquido remanescente da cobrança!")
    }

    public List<Long> processExpiration() {
        final Integer maxPaymentCustodyPerExecution = 250
        List<Long> paymentCustodyIdList = PaymentCustody.query([column: "id", status: PaymentCustodyStatus.ACTIVE, "expirationDate[le]": new Date().clearTime()]).list(max: maxPaymentCustodyPerExecution)

        Utils.forEachWithFlushSession(paymentCustodyIdList, 50, { Long paymentCustodyId ->
            Utils.withNewTransactionAndRollbackOnError({
                PaymentCustody paymentCustody = PaymentCustody.get(paymentCustodyId)
                finish(paymentCustody, PaymentCustodyFinishReason.EXPIRED, null)
            }, [onError: { Exception exception -> throw exception }])
        })

        return paymentCustodyIdList
    }

    public BigDecimal getCustomerBlockedValue(Customer customer) {
        BigDecimal blockedValue = PaymentCustodyItem.sumValueAbs([customer: customer, type: PaymentCustodyItemType.BLOCK]).get()
        BigDecimal reversedValue = PaymentCustodyItem.sumValueAbs([customer: customer, type: PaymentCustodyItemType.REVERSAL]).get()

        return blockedValue - reversedValue
    }

    public void onPaymentRefund (PaymentRefund paymentRefund) {
        PaymentCustodyItem paymentCustodyItem = PaymentCustodyItem.query([payment: paymentRefund.payment, type: PaymentCustodyItemType.BLOCK]).get()
        if (!paymentCustodyItem) return
        if (paymentCustodyItem.paymentCustody.status.isDone()) return

        paymentCustodyItemService.processPaymentRefund(paymentCustodyItem, paymentRefund)

        if (getBlockedValue(paymentCustodyItem.paymentCustody) == 0) finish(paymentCustodyItem.paymentCustody, PaymentCustodyFinishReason.PAYMENT_REFUNDED, null)
    }

    public void onPaymentRefundSplit (PaymentRefundSplit paymentRefundSplit) {
        PaymentCustodyItem paymentCustodyItem = PaymentCustodyItem.query([paymentSplit: paymentRefundSplit.paymentSplit, type: PaymentCustodyItemType.BLOCK]).get()
        if (!paymentCustodyItem) return
        if (paymentCustodyItem.paymentCustody.status.isDone()) return

        paymentCustodyItemService.processPaymentRefundSplit(paymentCustodyItem, paymentRefundSplit)

        if (getBlockedValue(paymentCustodyItem.paymentCustody) == 0) finish(paymentCustodyItem.paymentCustody, PaymentCustodyFinishReason.PAYMENT_REFUNDED, null)
    }

    public void finishIfNecessary(Payment payment, PaymentCustodyFinishReason finishReason) {
        PaymentCustody paymentCustody = PaymentCustody.query([payment: payment, status: PaymentCustodyStatus.ACTIVE]).get()
        if (!paymentCustody) return

        finish(paymentCustody, finishReason, null)
    }

    public Boolean isEnabledForCustomer(Customer customer) {
        Boolean hasCustodyDays = paymentCustodyCacheService.getCustodyDaysForCustomer(customer.id).asBoolean()

        return hasCustodyDays
    }

    public void saveDaysToExpirePaymentCustodyForCustomer(Customer customer, BigDecimal daysToExpirePaymentCustody) {
        customerParameterService.save(customer, CustomerParameterName.DAYS_TO_EXPIRE_PAYMENT_CUSTODY, daysToExpirePaymentCustody)
        paymentCustodyCacheService.evictGetCustodyDaysForCustomer(customer.id)
    }

    private void finish(PaymentCustody paymentCustody, PaymentCustodyFinishReason finishReason, String walletPublicId) {
        if (paymentCustody.status.isDone()) {
            throw new BusinessException("A custódia informada já está encerrada.")
        }

        Boolean paymentCustodyReversed = paymentCustodyItemService.reverse(paymentCustody, walletPublicId)

        if (paymentCustodyReversed) {
            paymentCustody.status = PaymentCustodyStatus.DONE
            paymentCustody.finishReason = finishReason
            paymentCustody.finishDate = new Date()
            paymentCustody.save(failOnError: true)

            receivableAnticipationValidationService.onPaymentChange(paymentCustody.payment)
        }
    }

    private Customer getCustodianCustomer(Customer customer) {
        if (customer.accountOwner) return customer.accountOwner

        return customer
    }

    private BigDecimal getBlockedValue(PaymentCustody paymentCustody) {
        BigDecimal blockedValue = PaymentCustodyItem.sumValueAbs([paymentCustody: paymentCustody, type: PaymentCustodyItemType.BLOCK]).get()
        BigDecimal reversedValue = PaymentCustodyItem.sumValueAbs([paymentCustody: paymentCustody, type: PaymentCustodyItemType.REVERSAL]).get()

        return blockedValue - reversedValue
    }
}

