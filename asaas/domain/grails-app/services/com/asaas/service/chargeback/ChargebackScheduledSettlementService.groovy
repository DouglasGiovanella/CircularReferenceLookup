package com.asaas.service.chargeback

import com.asaas.chargeback.ChargebackScheduledSettlementStatus
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.chargeback.ChargebackScheduledSettlement
import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class ChargebackScheduledSettlementService {

    public ChargebackScheduledSettlement save(Long chargebackId, Payment payment) {
        Chargeback chargeback = Chargeback.get(chargebackId)

        ChargebackScheduledSettlement chargebackScheduledSettlement = new ChargebackScheduledSettlement()
        chargebackScheduledSettlement.chargeback = chargeback
        chargebackScheduledSettlement.payment = payment
        chargebackScheduledSettlement.creditDate = payment.creditDate
        chargebackScheduledSettlement.status = ChargebackScheduledSettlementStatus.PENDING
        chargebackScheduledSettlement.save(failOnError: true)

        return chargebackScheduledSettlement
    }

    public ChargebackScheduledSettlement setAsDone(ChargebackScheduledSettlement chargebackScheduledSettlement) {
        chargebackScheduledSettlement.status = ChargebackScheduledSettlementStatus.DONE
        chargebackScheduledSettlement.save(failOnError: true)

        return chargebackScheduledSettlement
    }
}
