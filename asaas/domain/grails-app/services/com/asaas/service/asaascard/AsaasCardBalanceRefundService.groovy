package com.asaas.service.asaascard

import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardBalanceRefund
import com.asaas.exception.BusinessException

import grails.transaction.Transactional

@Transactional
class AsaasCardBalanceRefundService {

    def bifrostPrepaidCardService
    def financialTransactionService

    public AsaasCardBalanceRefund refundBalanceToAsaasAccount(AsaasCard asaasCard) {
        if (!asaasCard.type.isPrepaid()) throw new BusinessException("O tipo de cartão não permite estorno.")

        Map refund = bifrostPrepaidCardService.saveBalanceRefund(asaasCard)
        if (AsaasCardBalanceRefund.query([externalId: refund.externalId, exists: true]).get()) throw new BusinessException("Estorno de recarga já foi salvo no Asaas.")

        AsaasCardBalanceRefund asaasCardBalanceRefund = new AsaasCardBalanceRefund()
        asaasCardBalanceRefund.externalId = refund.externalId
        asaasCardBalanceRefund.amount = refund.amount
        asaasCardBalanceRefund.asaasCard = asaasCard
        asaasCardBalanceRefund.save(flush: true)

        financialTransactionService.saveBalanceRefund(asaasCardBalanceRefund)

        return asaasCardBalanceRefund
    }
}
