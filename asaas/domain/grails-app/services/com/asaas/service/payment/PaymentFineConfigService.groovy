package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.interest.InterestConfig
import com.asaas.domain.payment.PaymentFineConfig
import com.asaas.domain.payment.importdata.PaymentImportItem
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.importdata.SubscriptionImportItem
import com.asaas.importdata.ImportDataParser
import com.asaas.interestconfig.FineType
import com.asaas.subscription.SubscriptionStatus
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class PaymentFineConfigService {

    public PaymentFineConfig save(object, Map paymentFineConfigMap) {
        if (!object || !paymentFineConfigMap) return

        BusinessValidation businessValidation = validate(object, paymentFineConfigMap)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(object, businessValidation.getFirstErrorMessage())
            return
        }

        Map parsedMap = parseParams(paymentFineConfigMap)
        PaymentFineConfig paymentFineConfig = build(object, parsedMap)

        Boolean isNewOrUpdated = !paymentFineConfig.id || paymentFineConfig.isDirty()
        if (!isNewOrUpdated) return

        paymentFineConfig.save(flush: true, failOnError: true)
        paymentFineConfig.hasBeenUpdated = true

        if (object instanceof Subscription) object.fineConfig = paymentFineConfig

        return paymentFineConfig
    }

    public BusinessValidation validate(object, Map fineConfigMap) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!object || !fineConfigMap || object.automaticRoutine) return businessValidation
        Map parsedMap = parseParams(fineConfigMap)

        if (parsedMap.value == null && parsedMap.fixedValue == null) {
            businessValidation.addError("paymentFine.error.valueEmpty")
            return businessValidation
        }

        businessValidation = validateValue(object, parsedMap.value, parsedMap.fineType)
        if (!businessValidation.isValid()) return businessValidation

        if (object instanceof Payment && !object.isPending() && !object.duplicatedPayment && !object.ignoreDueDateValidator) {
            businessValidation.addError("paymentFine.error.paymentStatusIsNotPending")
            return businessValidation
        }

        if (object instanceof Subscription && object.status != SubscriptionStatus.ACTIVE) {
            businessValidation.addError("paymentFine.error.subscriptionStatusIsNotActive")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation validateObjectValue(object) {
        BusinessValidation businessValidation = new BusinessValidation()
        if (!shouldValidateObject(object)) return businessValidation

        Map fineConfigMap = parseObjectToFineConfig(object)
        if (fineConfigMap) return validateValue(object, fineConfigMap.value, fineConfigMap.fineType)

        return businessValidation
    }

    private Boolean shouldValidateObject(object) {
        if (!object) return false
        if (object instanceof Payment && object.automaticRoutine) return false
        if (object instanceof Subscription && object.automaticRoutine) return false

        return true
    }

    private Map parseObjectToFineConfig(object) {
        switch (object.class) {
            case Payment:
                Map paymentFineConfigMap = PaymentFineConfig.query([
                    columnList: ["value", "type"],
                    paymentId: object.id
                ]).get()
                if (!paymentFineConfigMap) return [:]

                return [value: paymentFineConfigMap.value, fineType: paymentFineConfigMap.type]

            case Subscription:
                if (!object.fineConfig) return [:]
                return [value: object.fineConfig.value, fineType: object.fineConfig.type]

            case PaymentImportItem:
            case SubscriptionImportItem:
                if (!object.fineValue || !object.fineType) return [:]
                return [value: object.fineValue, fineType: ImportDataParser.parseFineType(object.fineType)]

            default:
                return [:]
        }
    }

	private BusinessValidation validateValue(object, BigDecimal fineValue, FineType fineType) {
        BusinessValidation businessValidation = new BusinessValidation()

		if (fineValue >= object.value && fineType == FineType.FIXED) {
            businessValidation.addError("paymentFine.error.fineValueAbovePaymentValue", [FormUtils.formatCurrencyWithMonetarySymbol(fineValue), FormUtils.formatCurrencyWithMonetarySymbol(object.value)])
            return businessValidation
		}

		if (fineValue >= 100 && fineType == FineType.PERCENTAGE) {
            businessValidation.addError("paymentFine.error.percentageExceededPaymentValue")
            return businessValidation
		}

        return businessValidation
	}

	public Map buildCustomerConfig(Customer customer) {
		InterestConfig interestConfig = InterestConfig.find(customer)

		if (!interestConfig) return [value: 0, fineType: FineType.FIXED]
		if (interestConfig.finePercentage <= 0 && interestConfig.fine <= 0) return [value: 0, fineType: FineType.FIXED]

		if (interestConfig.fineType && interestConfig.fine > 0) return [value: interestConfig.fine, fineType: interestConfig.fineType]

		return [value: interestConfig.finePercentage ?: 0, fixedValue: interestConfig.fine ?: 0]
	}

	private Map parseParams(Map fineConfigMap) {
		return [value: fineConfigMap.value ? Utils.toBigDecimal(fineConfigMap.value) : 0,
		fixedValue: fineConfigMap.fixedValue ? Utils.toBigDecimal(fineConfigMap.fixedValue) : 0,
		fineType: FineType.convert(fineConfigMap.fineType)]
	}

    private PaymentFineConfig build(object, Map parsedMap) {
        PaymentFineConfig paymentFineConfig = null
        if (object instanceof Payment) paymentFineConfig = PaymentFineConfig.query([paymentId: object.id]).get()
        if (object instanceof Subscription) paymentFineConfig = (object as Subscription).fineConfig

        if (!paymentFineConfig) paymentFineConfig = new PaymentFineConfig()
        paymentFineConfig.value = parsedMap.value
        if (parsedMap.fineType) {
            paymentFineConfig.type = parsedMap.fineType
        }

        if (parsedMap.fixedValue && parsedMap.fixedValue > 0) {
            paymentFineConfig.fixedValue = parsedMap.fixedValue
        } else {
            paymentFineConfig.fixedValue = 0
        }

        if (object instanceof Payment) {
            paymentFineConfig.payment = object
        } else if (object instanceof Subscription) {
            paymentFineConfig.subscription = object
        } else {
            throw new RuntimeException("Objeto não suportado.")
        }

        return paymentFineConfig
    }
}
