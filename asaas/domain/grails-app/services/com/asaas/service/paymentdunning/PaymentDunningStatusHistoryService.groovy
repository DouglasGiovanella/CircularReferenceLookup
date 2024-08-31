package com.asaas.service.paymentdunning

import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.PaymentDunningStatusHistory
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class PaymentDunningStatusHistoryService {

    public PaymentDunningStatusHistory save(PaymentDunning paymentDunning) {
        return save(paymentDunning, null)
    }

    public PaymentDunningStatusHistory save(PaymentDunning paymentDunning, String observation) {
        PaymentDunningStatusHistory statusHistory = new PaymentDunningStatusHistory()
        statusHistory.paymentDunning = paymentDunning
        statusHistory.status = paymentDunning.status
        statusHistory.user = UserUtils.getCurrentUser()
        statusHistory.dateCreated = new Date()
        statusHistory.observation = observation
        statusHistory.save(failOnError: true)

        return statusHistory
    }
}
