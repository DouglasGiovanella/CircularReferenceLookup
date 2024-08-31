package com.asaas.service.payment.paymentafterconfirmevent

import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentAfterConfirmEvent
import com.asaas.payment.PaymentStatus
import com.asaas.paymentafterconfirmevent.PaymentAfterConfirmEventStatus
import com.asaas.paymentafterconfirmevent.PaymentAfterConfirmEventType
import grails.transaction.Transactional

@Transactional
class PaymentAfterConfirmEventService {

    public void save(Payment payment, PaymentStatus paymentStatusBeforeConfirmation) {
        PaymentAfterConfirmEvent event = new PaymentAfterConfirmEvent()
        event.payment = payment
        event.customer = payment.provider
        event.status = PaymentAfterConfirmEventStatus.PENDING
        event.type = PaymentAfterConfirmEventType.PAYMENT
        event.paymentStatusOnEventCreation = payment.status
        event.paymentStatusBeforeConfirmation = paymentStatusBeforeConfirmation

        event.save(failOnError: true)
    }

    public void setAsError(PaymentAfterConfirmEvent event) {
        event.status = PaymentAfterConfirmEventStatus.ERROR
        event.save(failOnError: true)
    }

    public void setCustomerEventsAsError(Long customerId) {
        PaymentAfterConfirmEvent.executeUpdate("UPDATE PaymentAfterConfirmEvent pace SET pace.status = :error WHERE pace.customer.id = :customerId and pace.type = :type and pace.status = :pending ",
            [error: PaymentAfterConfirmEventStatus.ERROR, customerId: customerId, pending: PaymentAfterConfirmEventStatus.PENDING, type: PaymentAfterConfirmEventType.CUSTOMER])
    }

    public void deleteCustomerEvents(Long customerId) {
        PaymentAfterConfirmEvent.executeUpdate("DELETE from PaymentAfterConfirmEvent pace WHERE pace.customer.id = :customerId and pace.type = :type and pace.status = :pending ",
            [customerId: customerId, pending: PaymentAfterConfirmEventStatus.PENDING, type: PaymentAfterConfirmEventType.CUSTOMER])
    }
}
