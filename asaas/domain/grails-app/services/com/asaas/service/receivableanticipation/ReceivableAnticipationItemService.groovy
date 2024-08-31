package com.asaas.service.receivableanticipation

import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.BusinessException
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationItemVO
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationItemService {

    def paymentAnticipableInfoService
    def paymentSplitService
    def receivableAnticipationFinancialInfoService
    def receivableAnticipationValidationService

    public void updateFeeWithDiscountApplied(ReceivableAnticipation receivableAnticipation, BigDecimal feeWithDiscountApplied) {
        BigDecimal fee = receivableAnticipation.fee
        BigDecimal discountApplied = fee - feeWithDiscountApplied

        for (ReceivableAnticipationItem receivableAnticipationItem : receivableAnticipation.items) {
            BigDecimal itemFee = receivableAnticipationItem.fee

            if (discountApplied >= itemFee) {
                discountApplied -= itemFee
                itemFee = 0
            } else {
                itemFee -= discountApplied
                discountApplied = 0
            }

            receivableAnticipationItem.originalFee = receivableAnticipationItem.fee
            receivableAnticipationItem.fee = itemFee

            receivableAnticipationItem.save(failOnError: true)

            if (discountApplied == 0) break
        }
    }

    public void saveAllItems(ReceivableAnticipation anticipation, List<Payment> paymentList, ReceivableAnticipationPartner partner) {
        Integer countValidLimit = anticipation.installment ? 0 : 1

        Date estimatedConfirmationDate = anticipation.status.isScheduled() ?
            receivableAnticipationFinancialInfoService.calculateScheduleDate(anticipation.billingType, anticipation.dueDate, anticipation.customer) :
            anticipation.anticipationDate

        paymentList.sort { it.id }

        for (Payment payment : paymentList) {
            if (ReceivableAnticipation.countValid(payment.provider.id, payment.id) > countValidLimit) throw new RuntimeException("Cobrança [${payment.getInvoiceNumber()}] já antecipada.")

            Date estimatedCreditDate = payment.dueDate
            if (payment.billingType.isCreditCard()) {
                estimatedCreditDate = payment.creditDate ?: Payment.calculateEstimatedCreditDate(anticipation.customer.id, payment.installmentNumber, estimatedConfirmationDate)
            }

            BigDecimal itemFee = (anticipation.installment) ?
                receivableAnticipationFinancialInfoService.calculateInstallmentItemFee(payment.netValue, anticipation.anticipationDate, estimatedCreditDate, anticipation.customer) :
                anticipation.fee

            BigDecimal grossValue = ReceivableAnticipationCalculator.calculateGrossValue(payment)

            ReceivableAnticipationItemVO anticipationItemVO = new ReceivableAnticipationItemVO(grossValue, itemFee, estimatedCreditDate, partner)
            ReceivableAnticipationItem item = save(anticipation, payment, anticipationItemVO)
            anticipation.addToItems(item)

            validatePaymentSplitTotalValue(payment, item.calculateNetValue())

            payment.automaticRoutine = true
            payment.anticipated = true
            payment.save(failOnError: true)

            paymentAnticipableInfoService.setAsAnticipated(payment.id, anticipation.status.isScheduled())
            receivableAnticipationValidationService.onPaymentChange(payment)
        }
    }

    public void setAllItemsStatus(ReceivableAnticipation anticipation, ReceivableAnticipationStatus status) {
        for (ReceivableAnticipationItem item : anticipation.items) {
            item.status = status
            item.save(failOnError: true)
        }
    }

    private ReceivableAnticipationItem save(ReceivableAnticipation anticipation, Payment payment, ReceivableAnticipationItemVO anticipationItemVO) {
        ReceivableAnticipationItem item = new ReceivableAnticipationItem()
        item.anticipation = anticipation
        item.status = anticipation.status
        item.customer = anticipation.customer
        item.payment = payment
        item.billingType = payment.billingType
        item.value = anticipationItemVO.grossValue
        item.fee = anticipationItemVO.fee
        item.estimatedCreditDate = anticipationItemVO.estimatedCreditDate

        item.save(failOnError: true)

        return item
    }

    private void validatePaymentSplitTotalValue(Payment payment, BigDecimal anticipationValue) {
        List<PaymentSplit> paymentSplitList = PaymentSplit.listActive(payment)
        if (paymentSplitList.any { it.fixedValue }) {
            paymentSplitService.updateSplitTotalValue(payment)

            BigDecimal valueCompromisedWithPaymentSplit = payment.getValueCompromisedWithPaymentSplit()
            if (valueCompromisedWithPaymentSplit > anticipationValue) throw new BusinessException("Não é possível antecipar a cobrança ${payment.id}. Motivo(s): O valor do Split é superior ao valor antecipado.")
        }
    }
}
