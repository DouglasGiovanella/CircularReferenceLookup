package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.discountconfig.DiscountType
import com.asaas.domain.customer.Customer
import com.asaas.domain.interest.InterestConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDiscountConfig
import com.asaas.domain.payment.importdata.PaymentImportItem
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.importdata.SubscriptionImportItem
import com.asaas.importdata.ImportDataParser
import com.asaas.payment.PaymentBuilder
import com.asaas.subscription.SubscriptionStatus
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class PaymentDiscountConfigService {

    public PaymentDiscountConfig save(object, Map discountConfigMap) {
        if (!discountConfigMap || !object) return null

        BusinessValidation businessValidation = validate(object, discountConfigMap)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(object, businessValidation.getFirstErrorMessage())
            return null
        }

        Map parsedMap = parseParams(discountConfigMap)
        PaymentDiscountConfig paymentDiscountConfig = build(object, parsedMap)
        Boolean isNewOrUpdated = !paymentDiscountConfig.id || paymentDiscountConfig.isDirty()
        if (!isNewOrUpdated) return null

        paymentDiscountConfig.save(failOnError: true, flush: true)
        paymentDiscountConfig.hasBeenUpdated = true

        if (object instanceof Subscription) object.discountConfig = paymentDiscountConfig
        return paymentDiscountConfig
    }

    public BusinessValidation validate(object, discountConfigMap) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!discountConfigMap || !object || object.automaticRoutine) return businessValidation
        Map parsedMap = parseParams(discountConfigMap)

        if (parsedMap.value == null) {
            businessValidation.addError("paymentDiscount.error.valueEmpty")
            return businessValidation
        }

        businessValidation = validateValue(object, parsedMap.value, parsedMap.discountType)
        if (!businessValidation.isValid()) return businessValidation

        if (object instanceof Payment && !object.isPending()) {
            businessValidation.addError("paymentDiscount.error.paymentStatusIsNotPending")
            return businessValidation
        }

        if (object instanceof Payment && parsedMap.limitDate && PaymentBuilder.parseDate(parsedMap.limitDate) > object.dueDate) {
            businessValidation.addError("paymentDiscount.error.limitDateAboveDueDate")
            return businessValidation
        }

        if (object instanceof Subscription && object.status != SubscriptionStatus.ACTIVE) {
            businessValidation.addError("paymentDiscount.error.subscriptionStatusIsNotActive")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation validateObjectValue(object) {
        BusinessValidation businessValidation = new BusinessValidation()
        if (!shouldValidateObject(object)) return businessValidation

        Map discountConfigMap = parseObjectToDiscountConfig(object)
        if (discountConfigMap) return validateValue(object, discountConfigMap.value, discountConfigMap.discountType)

        return businessValidation
    }

    private Boolean shouldValidateObject(object) {
        if (!object) return false
        if (object instanceof Payment && object.automaticRoutine) return false
        if (object instanceof Subscription && object.automaticRoutine) return false

        return true
    }

    private Map parseObjectToDiscountConfig(object) {
        switch (object.class) {
            case Payment:
                Map paymentDiscountConfigMap = PaymentDiscountConfig.query([columnList: ["value", "type"], paymentId: object.id]).get()
                if (!paymentDiscountConfigMap) return [:]
                return [value: paymentDiscountConfigMap.value, discountType: paymentDiscountConfigMap.type]

            case Subscription:
                if (!object.discountConfig) return [:]
                return [value: object.discountConfig.value, discountType: object.discountConfig.type]

            case PaymentImportItem:
            case SubscriptionImportItem:
                if (!object.discountValue || !object.discountType) return [:]
                return [value: object.discountValue, discountType: ImportDataParser.parseDiscountType(object.discountType)]

            default:
                return [:]
        }
    }

    private BusinessValidation validateValue(object, BigDecimal discountValue, DiscountType discountType) {
        Customer customer
        if (object instanceof Payment || object instanceof Subscription) {
            customer = object.customerAccount.provider
        } else if (object instanceof PaymentImportItem || object instanceof SubscriptionImportItem) {
            customer = object.group.customer
        }

        return validateCustomerDiscountValue(BillingType.convert(object.billingType), object.value, discountValue, discountType, customer)
    }

	private Map parseParams(Map discountConfigMap) {
		return [
			value: Utils.toBigDecimal(discountConfigMap.value),
			dueDateLimitDays: Utils.toInteger(discountConfigMap.dueDateLimitDays),
			limitDate: PaymentBuilder.parseDate(discountConfigMap.limitDate),
			discountType: DiscountType.convert(discountConfigMap.discountType)
		]
	}

    private PaymentDiscountConfig build(object, Map parsedMap) {
        PaymentDiscountConfig paymentDiscountConfig = null
        if (object instanceof Payment) paymentDiscountConfig = PaymentDiscountConfig.query([paymentId: object.id]).get()
        else if (object instanceof Subscription) paymentDiscountConfig = (object as Subscription).discountConfig

        if (!paymentDiscountConfig) paymentDiscountConfig = new PaymentDiscountConfig()
        paymentDiscountConfig.value = parsedMap.value
        if (object instanceof Payment) {
            paymentDiscountConfig.payment = object
        } else if (object instanceof Subscription) {
            paymentDiscountConfig.subscription = object
        }

        if (parsedMap.discountType) {
            paymentDiscountConfig.type = parsedMap.discountType
        }

        paymentDiscountConfig.limitDate = null
        paymentDiscountConfig.dueDateLimitDays = null
        if (parsedMap.dueDateLimitDays >= 0) {
            paymentDiscountConfig.dueDateLimitDays = parsedMap.dueDateLimitDays
        } else if (parsedMap.limitDate && object instanceof Payment) {
            paymentDiscountConfig.limitDate = parsedMap.limitDate
        } else {
            paymentDiscountConfig.dueDateLimitDays = 0
        }

        return paymentDiscountConfig
    }

    private BusinessValidation validateCustomerDiscountValue(BillingType billingType, BigDecimal value, BigDecimal discountValue, DiscountType discountType, Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()
        BigDecimal calculatedDiscountValue = PaymentDiscountConfig.calculateDiscountValue(value, discountValue, discountType)

        BigDecimal minValue = 0
        if (billingType.equals(BillingType.MUNDIPAGG_CIELO)) {
            minValue = Payment.getMinimumDetachedCreditCardValue(customer)
        } else {
            minValue = Payment.getMinimumBankSlipAndPixValue(customer)
        }

        if ((value - calculatedDiscountValue) < minValue) {
            businessValidation.addError("paymentDiscount.error.valueBelowMinimum", [FormUtils.formatCurrencyWithMonetarySymbol(value), FormUtils.formatCurrencyWithMonetarySymbol(calculatedDiscountValue), FormUtils.formatCurrencyWithMonetarySymbol(minValue)])
        }

        return businessValidation
    }

	public void applyPaymentDiscountIfNecessary(Payment payment) {
		Map paymentDiscountInfo = calculatePaymentDiscountInfo(payment)

		if (paymentDiscountInfo) {
			payment.originalValue = payment.value
			payment.discountValue = paymentDiscountInfo.discountValue
			payment.value = paymentDiscountInfo.value
		}
	}

    public Map calculatePaymentDiscountInfo(Payment payment) {
        if (payment.isPaid() || payment.isReceivingProcessInitiated()) return null

        PaymentDiscountConfig paymentDiscountConfig = PaymentDiscountConfig.query([paymentId: payment.id]).get()
        BigDecimal discountValue = paymentDiscountConfig?.calculateDiscountValue() ?: 0

        if (discountValue > 0 && !payment.isOverdue() && paymentDiscountConfig?.valid()) return [discountValue: discountValue, value: payment.value - discountValue]

        return null
    }

	public Map buildCustomerConfig(BigDecimal value, BillingType billingType, Customer customer) {
		InterestConfig interestConfig = InterestConfig.find(customer)

        if (interestConfig?.discount) {
            BusinessValidation businessValidation = validateCustomerDiscountValue(billingType, value, interestConfig.discount, interestConfig.discountType, customer)
            if (businessValidation.isValid()) return [value: interestConfig.discount, discountType: interestConfig.discountType, dueDateLimitDays: 0]
        }

        return [value: 0, discountType: DiscountType.FIXED, dueDateLimitDays: 0]
	}
}
