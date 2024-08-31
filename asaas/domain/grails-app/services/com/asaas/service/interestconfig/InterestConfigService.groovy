package com.asaas.service.interestconfig

import com.asaas.domain.interest.InterestConfig
import com.asaas.domain.payment.Payment
import com.asaas.discountconfig.DiscountType
import com.asaas.interestconfig.FineType
import com.asaas.interestconfig.InterestPeriod
import com.asaas.payment.PaymentInterestCalculator
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class InterestConfigService {

	def save(params) {
		InterestConfig interestConfig = InterestConfig.findOrCreateWhere(provider: params.provider)

		interestConfig.enabled = params.enabled

		interestConfig.fineType = FineType.convert(params.fineType)
		interestConfig.interestPeriod = InterestPeriod.MONTHLY
		interestConfig.interest = params.interest ? Utils.toBigDecimal(params.interest) : 0
		interestConfig.discount = params.discount ? Utils.toBigDecimal(params.discount) : 0
		interestConfig.discountType = params.discountType instanceof DiscountType ? params.discountType : DiscountType.convert(params.discountType)

		interestConfig.finePercentage = 0
		interestConfig.fine = params.fineValue ? Utils.toBigDecimal(params.fineValue) : 0

		interestConfig.save(flush: true)

		return interestConfig
	}

	public BigDecimal calculateFineAndInterestValue(Payment payment, Date paymentDate) {
		Integer overdueDays = CustomDateUtils.calculateDifferenceInDays(payment.dueDate, paymentDate)
		if (overdueDays <= 0) return 0

		PaymentInterestCalculator interestCalculator = new PaymentInterestCalculator(payment, paymentDate).execute()

		return interestCalculator.totalInterestPlusFineValue
	}

	public BigDecimal calculateInterestValue(InterestConfig interestConfig, Double value, Integer overdueDays) {
		if (!interestConfig) return 0

		InterestPeriod interestPeriod = interestConfig.interestPeriod ?: InterestPeriod.DAILY
		BigDecimal interest = interestConfig.interest ?: 0

		if (interestPeriod == InterestPeriod.MONTHLY) {
			interest = interest / 30
		}

		return (value * (interest / 100)) * overdueDays
	}
}
