package com.asaas.service.payment

import com.asaas.cashinriskanalysis.vo.CashInRiskAnalysisCheckResultVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentSettlementSchedule
import com.asaas.domain.riskAnalysis.RiskAnalysisRequest
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class PaymentFloatService {

    def cashInRiskAnalysisService
    def createCashInRiskAnalysisRequestService
    def paymentAnticipableInfoService
    def riskAnalysisFloatService
    def riskAnalysisRequestService

    public Boolean applyPaymentFloatOnPaymentConfirmation(Payment payment) {
        final Integer floatAppliedIfAnalysisRequired = 2
        final Integer defaultFloatForBillingTypeDifferentFromBoleto = 0
        final Integer defaultFloatForPixInPrecautionaryBlock = 3
        final Integer defaultFloatForCreditBureauDunning = 0
        final Integer defaultFloatForTransfer = 0

        if (payment.getDunning()?.type?.isCreditBureau()) {
            setCreditDate(payment, defaultFloatForCreditBureauDunning)
            return verifyHasFloatFromPaymentSettlementScheduleAndSetPaymentCreditDateIfNecessary(payment)
        }

        if (payment.billingType.isPix()) {
            CashInRiskAnalysisCheckResultVO cashInRiskAnalysisCheckResult = cashInRiskAnalysisService.checkIfCashInNeedsPrecautionaryBlock(payment)
            if (cashInRiskAnalysisCheckResult.isSuspected) {
                createCashInRiskAnalysisRequestService.saveForPrecautionaryBlock(payment, cashInRiskAnalysisCheckResult.triggeredRule)
                setCreditDate(payment, defaultFloatForPixInPrecautionaryBlock)
                return true
            }
        }

        if (!payment.billingType.isEquivalentToBoleto()) {
            setCreditDate(payment, defaultFloatForBillingTypeDifferentFromBoleto)
            return verifyHasFloatFromPaymentSettlementScheduleAndSetPaymentCreditDateIfNecessary(payment)
        }

        if (payment.billingType.isTransfer()) {
            setCreditDate(payment, defaultFloatForTransfer)
        } else {
            setCreditDate(payment, payment.provider.customerConfig.paymentFloat)
            if (payment.creditDate > new Date().clearTime()) return true
        }

        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.FLOAT_APPLIED_ON_PAYMENT_VALUE_RECEIVED_ABOVE_LIMIT
        Boolean shouldSaveRiskAnalysisRequest = riskAnalysisFloatService.shouldSaveRiskAnalysisRequest(payment, riskAnalysisReason)
        if (!shouldSaveRiskAnalysisRequest) return verifyHasFloatFromPaymentSettlementScheduleAndSetPaymentCreditDateIfNecessary(payment)

        Boolean hasFloatAnalysisVerified = RiskAnalysisRequest.hasPaymentFloatRiskAnalysisVerified([customer: payment.provider]).get().asBoolean()
        if (hasFloatAnalysisVerified) return verifyHasFloatFromPaymentSettlementScheduleAndSetPaymentCreditDateIfNecessary(payment)

        if (!payment.billingType.isTransfer()) setCreditDate(payment, floatAppliedIfAnalysisRequired)

        riskAnalysisRequestService.saveAnalysisWithNewTransaction(payment.provider.id, riskAnalysisReason, payment.id)

        return true
    }

    public void updateCreditDateForNotCreditedPayments(Customer customer) {
        List<Payment> confirmedPaymentIds = Payment.automaticCreditableAfterFloat([customer: customer, 'creditDate[ge]': new Date().clearTime()]).list()

        for (Payment payment : confirmedPaymentIds) {
            setCreditDate(payment, payment.provider.customerConfig.paymentFloat)

            if (payment.creditDate <= new Date().clearTime()) payment.creditDate = CustomDateUtils.getNextBusinessDay()

            payment.save(failOnError: true)
            paymentAnticipableInfoService.updateIfNecessary(payment)
        }
    }

    private void setCreditDate(Payment payment, Integer paymentFloat) {
        if (paymentFloat <= 0) {
            payment.creditDate = payment.confirmedDate
            return
        }

        if (payment.billingType.isPix()) {
            payment.creditDate = CustomDateUtils.sumDays(payment.clientPaymentDate, paymentFloat)
        } else {
            payment.creditDate = CustomDateUtils.addBusinessDays(payment.confirmedDate, paymentFloat)
        }
    }

    private Boolean verifyHasFloatFromPaymentSettlementScheduleAndSetPaymentCreditDateIfNecessary(Payment payment) {
        Date today = new Date().clearTime()
        if (payment.creditDate > today) return true
        if (!payment.billingType.isBoleto()) return false

        Date paymentSettlementScheduleDate = PaymentSettlementSchedule.query([column: "settlementDate", payment: payment]).get()
        if (!paymentSettlementScheduleDate) return false
        if (paymentSettlementScheduleDate <= payment.creditDate) return false

        payment.creditDate = paymentSettlementScheduleDate.clone().clearTime()

        return paymentSettlementScheduleDate > today
    }
}
