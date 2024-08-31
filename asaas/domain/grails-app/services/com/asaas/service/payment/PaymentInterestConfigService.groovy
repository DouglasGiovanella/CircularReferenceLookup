package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.interest.InterestConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentInterestConfig
import com.asaas.domain.subscription.Subscription
import com.asaas.interestconfig.InterestPeriod
import com.asaas.payment.PaymentUtils
import com.asaas.subscription.SubscriptionStatus
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PaymentInterestConfigService {

	def interestConfigService

    public PaymentInterestConfig save(Payment payment, Map interestConfigMap) {
        if (!payment || !interestConfigMap) return null

        BusinessValidation businessValidation = validate(payment, interestConfigMap)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(payment, businessValidation.getFirstErrorMessage())
            return null
        }

        Map parsedMap = parseParams(interestConfigMap)

        PaymentInterestConfig interestConfig = PaymentInterestConfig.query([paymentId: payment.id]).get()
        if (interestConfig) {
            Boolean valueHasChanged = interestConfig.value != parsedMap.value
            if (!valueHasChanged) return interestConfig
        } else {
            interestConfig = new PaymentInterestConfig()
            interestConfig.payment = payment
        }

        interestConfig.value = parsedMap.value
        interestConfig.save(flush: true, failOnError: true)
        interestConfig.hasBeenUpdated = true

        return interestConfig
    }

    public PaymentInterestConfig save(Subscription subscription, Map interestConfigMap) {
       if (!subscription || !interestConfigMap) return null

        BusinessValidation businessValidation = validate(subscription, interestConfigMap)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(subscription, businessValidation.getFirstErrorMessage())
            return null
        }

        Map parsedMap = parseParams(interestConfigMap)

        PaymentInterestConfig interestConfig = PaymentInterestConfig.query([subscriptionId: subscription.id]).get()
        if (interestConfig) {
            Boolean valueHasChanged = interestConfig.value != parsedMap.value
            if (!valueHasChanged) return interestConfig
        } else {
            interestConfig = new PaymentInterestConfig()
            interestConfig.subscription = subscription
        }

        interestConfig.value = parsedMap.value
        interestConfig.save(flush: true, failOnError: true)
        interestConfig.hasBeenUpdated = true

        subscription.interestConfig = interestConfig

        return interestConfig
    }

	public BusinessValidation validate(object, Map interestConfigMap) {
        BusinessValidation businessValidation = new BusinessValidation()

		if (!object || !interestConfigMap || object.automaticRoutine) return businessValidation
		Map parsedMap = parseParams(interestConfigMap)

		if (parsedMap.value == null) {
            businessValidation.addError("paymentInterest.error.valueEmpty")
            return businessValidation
		}

        businessValidation = validateCustomerConfig(parsedMap)
        if (!businessValidation.isValid()) return businessValidation

		if (object instanceof Payment && !object.isPending() && !object.duplicatedPayment && !object.ignoreDueDateValidator) {
            businessValidation.addError("paymentInterest.error.paymentStatusIsNotPending")
            return businessValidation
		}

		if (object instanceof Subscription && object.status != SubscriptionStatus.ACTIVE) {
            businessValidation.addError("paymentInterest.error.subscriptionStatusIsNotActive")
            return businessValidation
		}

        return businessValidation
	}

    public BusinessValidation validateCustomerConfig(Map interestMap) {
        BusinessValidation businessValidation = new BusinessValidation()

        InterestPeriod interestPeriod = interestMap.interestPeriod ?: InterestPeriod.MONTHLY
        String customerValidationMessage = InterestConfig.validateCustomerConfig(interestPeriod, interestMap.value)
        if (customerValidationMessage) businessValidation.addError(customerValidationMessage)

        return businessValidation
    }

	public Map buildCustomerConfig(Customer customer) {
		InterestConfig interestConfig = InterestConfig.find(customer)
		if (!interestConfig)  return [value: 0]

        BigDecimal value = interestConfig.calculateInterestMonthlyPercentage()
        return [value: value ?: 0]
	}

	private Map parseParams(Map interestConfigMap) {
        return [value: Utils.toBigDecimal(interestConfigMap.value)]
	}

	public void applyPaymentInterestIfNecessary(Payment payment) {
		Map paymentInterestInfo = calculatePaymentInterestInfo(payment)

		if (paymentInterestInfo) {
			payment.originalValue = payment.value
			payment.interestValue = paymentInterestInfo.interestValue
			payment.value = paymentInterestInfo.value
		}
	}

	public Map calculatePaymentInterestInfo(Payment payment) {
        BigDecimal interestValue = interestConfigService.calculateFineAndInterestValue(payment, new Date().clearTime())

		if (interestValue > 0 && PaymentUtils.paymentDateHasBeenExceeded(payment)) return [interestValue: interestValue, value: payment.value + interestValue]

		return null
	}
}
