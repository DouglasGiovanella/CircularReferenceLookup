package com.asaas.service.asaascardcashback

import com.asaas.asaascard.asaascardcashback.AsaasCardCashbackRefusalReason
import com.asaas.domain.asaascard.AsaasCardCashback
import com.asaas.integration.bifrost.adapter.asaascardcashback.AsaasCardCashbackAdapter
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class AsaasCardCashbackService {

    def financialTransactionService

    public AsaasCardCashback save(AsaasCardCashbackAdapter asaasCardCashbackAdapter) {
        AsaasCardCashback validatedAsaasCardCashback = validateSave(asaasCardCashbackAdapter)
        if (validatedAsaasCardCashback.hasErrors()) return validatedAsaasCardCashback

        AsaasCardCashback asaasCardCashback = new AsaasCardCashback()
        asaasCardCashback.asaasCard = asaasCardCashbackAdapter.asaasCard
        asaasCardCashback.customer = asaasCardCashbackAdapter.customer
        asaasCardCashback.externalId = asaasCardCashbackAdapter.externalId
        asaasCardCashback.value = asaasCardCashbackAdapter.value
        asaasCardCashback.campaignName = asaasCardCashbackAdapter.campaignName
        asaasCardCashback.save(failOnError: true)

        financialTransactionService.saveAsaasCardCashback(asaasCardCashback)

        return asaasCardCashback
    }

    private AsaasCardCashback validateSave(AsaasCardCashbackAdapter asaasCardCashbackAdapter) {
        AsaasCardCashback asaasCardCashback = new AsaasCardCashback()

        if (!asaasCardCashbackAdapter.asaasCard) return setRefusalReason(asaasCardCashback, AsaasCardCashbackRefusalReason.INVALID_CARD)
        if (!asaasCardCashbackAdapter.asaasCard.status.hasBeenActivated()) return setRefusalReason(asaasCardCashback, AsaasCardCashbackRefusalReason.INACTIVE_CARD)
        if (!asaasCardCashbackAdapter.customer.status.isActive()) return setRefusalReason(asaasCardCashback, AsaasCardCashbackRefusalReason.INACTIVE_CUSTOMER)
        if (!asaasCardCashbackAdapter.externalId) return setRefusalReason(asaasCardCashback, AsaasCardCashbackRefusalReason.INVALID_CASHBACK)
        if (AsaasCardCashback.query([externalId: asaasCardCashbackAdapter.externalId, exists: true]).get()) return setRefusalReason(asaasCardCashback, AsaasCardCashbackRefusalReason.ALREADY_PROCESSED)
        if (!asaasCardCashbackAdapter.value || asaasCardCashbackAdapter.value <= 0) return setRefusalReason(asaasCardCashback, AsaasCardCashbackRefusalReason.INVALID_VALUE)

        return asaasCardCashback
    }

    private AsaasCardCashback setRefusalReason(AsaasCardCashback asaasCardCashback, AsaasCardCashbackRefusalReason reason) {
        asaasCardCashback.refusalReason = reason
        return DomainUtils.addError(asaasCardCashback, reason.message)
    }
}
