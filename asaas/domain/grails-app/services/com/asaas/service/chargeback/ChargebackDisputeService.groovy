package com.asaas.service.chargeback

import com.asaas.chargeback.ChargebackDisputeRejectReason
import com.asaas.chargeback.ChargebackDisputeStatus
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.chargeback.ChargebackDispute
import com.asaas.exception.BusinessException

import grails.transaction.Transactional

@Transactional
class ChargebackDisputeService {

    def chargebackService
    def messageService
    def customerAlertNotificationService

    public ChargebackDispute startDispute(Long chargebackId) {
        Chargeback chargeback = chargebackService.updateToInDispute(chargebackId)

        ChargebackDispute chargebackDispute = ChargebackDispute.query([chargeback: chargeback, status: ChargebackDisputeStatus.REQUESTED]).get()

        if (chargebackDispute) return chargebackDispute

        chargebackDispute = save(chargebackId)

        return chargebackDispute
    }

    public ChargebackDispute save(Long chargebackId) {
        Chargeback chargeback = Chargeback.get(chargebackId)

        ChargebackDispute chargebackDispute = new ChargebackDispute()
        chargebackDispute.chargeback = chargeback
        chargebackDispute.status = ChargebackDisputeStatus.REQUESTED
        chargebackDispute.save(failOnError: true)

        return chargebackDispute
    }

    public ChargebackDispute reject(Long id, ChargebackDisputeRejectReason rejectReason) {
        if (!rejectReason) throw new BusinessException("É necessário informar o motivo para recusar a contestação do chargeback.")

        ChargebackDispute chargebackDispute = ChargebackDispute.get(id)
        chargebackDispute.status = ChargebackDisputeStatus.REJECTED
        chargebackDispute.rejectReason = rejectReason
        chargebackDispute.save(failOnError: true)

        chargebackService.updateToDisputeLost(chargebackDispute.chargeback.id)

        messageService.sendChargebackDisputeLost(chargebackDispute)
        customerAlertNotificationService.notifyChargebackRejected(chargebackDispute.chargeback)

        return chargebackDispute
    }

    public ChargebackDispute accept(Long id) {
        ChargebackDispute chargebackDispute = ChargebackDispute.get(id)
        chargebackDispute.status = ChargebackDisputeStatus.ACCEPTED
        chargebackDispute.save(failOnError: true)

        chargebackService.reverse(chargebackDispute.chargeback.id)

        messageService.sendChargebackReversed(chargebackDispute.chargeback)
        customerAlertNotificationService.notifyChargebackAccepted(chargebackDispute.chargeback)
        return chargebackDispute
    }
}
