package com.asaas.service.customeraccountpaymentbonus

import com.asaas.customeraccountpaymentbonus.CustomerAccountPaymentBonusRepository
import com.asaas.customeraccountpaymentbonus.CustomerAccountPaymentBonusStatus
import com.asaas.domain.customeraccountpaymentbonus.CustomerAccountPaymentBonus
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerAccountPaymentBonusService {

    public CustomerAccountPaymentBonus save(Payment payment) {
        BusinessValidation businessValidation = validateSave(payment)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        CustomerAccountPaymentBonus customerAccountPaymentBonus = CustomerAccountPaymentBonusRepository.query([
            customerId: payment.providerId,
            paymentId: payment.id,
            status: CustomerAccountPaymentBonusStatus.CANCELLED
        ]).disableSort().get()

        if (customerAccountPaymentBonus) {
            customerAccountPaymentBonus.status = CustomerAccountPaymentBonusStatus.ACTIVE
            return customerAccountPaymentBonus.save(failOnError: true)
        }

        customerAccountPaymentBonus = new CustomerAccountPaymentBonus()
        customerAccountPaymentBonus.customerAccount = payment.customerAccount
        customerAccountPaymentBonus.payment = payment
        customerAccountPaymentBonus.status = CustomerAccountPaymentBonusStatus.ACTIVE

        return customerAccountPaymentBonus.save(failOnError: true)
    }

    public CustomerAccountPaymentBonus cancel(CustomerAccountPaymentBonus customerAccountPaymentBonus) {
        BusinessValidation businessValidation = validateCancel(customerAccountPaymentBonus)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        customerAccountPaymentBonus.status = CustomerAccountPaymentBonusStatus.CANCELLED
        return customerAccountPaymentBonus.save(failOnError: true)
    }

    private BusinessValidation validateSave(Payment payment) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean alreadyExists = CustomerAccountPaymentBonusRepository.query([
            customerId: payment.providerId,
            paymentId: payment.id,
            "status[ne]": CustomerAccountPaymentBonusStatus.CANCELLED
        ]).exists()

        if (alreadyExists) {
            businessValidation.addError("customerAccountPaymentBonus.validation.error.alreadyExists")
        }

        return businessValidation
    }

    private BusinessValidation validateCancel(CustomerAccountPaymentBonus customerAccountPaymentBonus) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customerAccountPaymentBonus.status.isCancelled()) {
            businessValidation.addError("customerAccountPaymentBonus.validation.error.alreadyCancelled")
        }

        return businessValidation
    }
}
