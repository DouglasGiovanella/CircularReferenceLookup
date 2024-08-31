package com.asaas.service.split

import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.split.PaymentRefundSplit
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.BusinessException
import com.asaas.paymentsplit.repository.PaymentRefundSplitRepository
import com.asaas.paymentsplit.repository.PaymentSplitRepository
import com.asaas.service.internaltransfer.InternalTransferService
import com.asaas.service.paymentcustody.PaymentCustodyService
import com.asaas.split.PaymentRefundSplitVO
import com.asaas.split.PaymentSplitStatus
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class PaymentRefundSplitService {

    InternalTransferService internalTransferService
    PaymentCustodyService paymentCustodyService

    public void savePartialRefundSplit(PaymentRefund paymentRefund, List<PaymentRefundSplitVO> paymentRefundSplitVoList) {
        for (PaymentRefundSplitVO paymentRefundSplitVo : paymentRefundSplitVoList) {
            BigDecimal paymentRefundSplitValue = Utils.toBigDecimal(paymentRefundSplitVo.value)
            if (!paymentRefundSplitValue) continue

            PaymentRefundSplit paymentRefundSplit = new PaymentRefundSplit()
            paymentRefundSplit.done = false
            paymentRefundSplit.paymentRefund = paymentRefund
            paymentRefundSplit.value = paymentRefundSplitValue

            PaymentSplit paymentSplit = PaymentSplitRepository.query([payment: paymentRefund.payment, publicId: paymentRefundSplitVo.paymentSplitPublicId]).get()
            paymentRefundSplit.paymentSplit = paymentSplit

            if (!paymentRefundSplit.paymentSplit) throw new BusinessException("O split informado não foi encontrado [${paymentRefundSplitVo.paymentSplitPublicId}].")

            paymentRefundSplit.save(failOnError: true, flush: true)

            BigDecimal totalSplitRefundedValue = PaymentRefundSplitRepository.query([paymentSplit: paymentRefundSplit.paymentSplit]).sumAbsolute("value")
            if (totalSplitRefundedValue > paymentRefundSplit.paymentSplit.totalValue) {
                throw new BusinessException("O valor total estornado do split [${FormUtils.formatCurrencyWithMonetarySymbol(totalSplitRefundedValue)}] excede o valor total do split [${FormUtils.formatCurrencyWithMonetarySymbol(paymentRefundSplit.paymentSplit.totalValue)}].")
            }
        }

        BigDecimal totalPaymentRefundedValue = PaymentRefundSplitRepository.query([paymentRefund: paymentRefund]).sumAbsolute("value")
        if (totalPaymentRefundedValue > paymentRefund.value) {
            throw new BusinessException("O valor estornado do split [${FormUtils.formatCurrencyWithMonetarySymbol(totalPaymentRefundedValue)}] excede o valor do estorno solicitado [${FormUtils.formatCurrencyWithMonetarySymbol(paymentRefund.value)}].")
        }
    }

    public void executePartialRefundSplitIfNecessary(PaymentRefund paymentRefund) {
        List<PaymentRefundSplit> paymentRefundSplitList = PaymentRefundSplitRepository.query([paymentRefund: paymentRefund, done: false]).list() as List<PaymentRefundSplit>

        for (PaymentRefundSplit paymentRefundSplit : paymentRefundSplitList) {
            executeRefund(paymentRefundSplit)
        }
    }

    public void refundSplitIfNecessary(PaymentSplit paymentSplit) {
        List<PaymentRefundSplit> paymentRefundSplitList = PaymentRefundSplitRepository.query([paymentSplit: paymentSplit, done: false]).list() as List<PaymentRefundSplit>

        for (PaymentRefundSplit paymentRefundSplit : paymentRefundSplitList) {
            executeRefund(paymentRefundSplit)
        }
    }

    private void executeRefund(PaymentRefundSplit paymentRefundSplit) {
        paymentCustodyService.onPaymentRefundSplit(paymentRefundSplit)

        PaymentSplit paymentSplit = paymentRefundSplit.paymentSplit
        internalTransferService.executePaymentRefundSplit(paymentRefundSplit)

        paymentRefundSplit.done = true
        paymentRefundSplit.save(failOnError: true, flush: true)

        BigDecimal totalSplitRefundedValue = PaymentRefundSplitRepository.query([paymentSplit: paymentSplit, done: true]).sumAbsolute("value")
        if (totalSplitRefundedValue >= paymentSplit.totalValue) {
            paymentSplit.status = PaymentSplitStatus.REFUNDED
            paymentSplit.save(failOnError: true)
        }
    }
}
