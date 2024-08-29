package com.asaas.service.paymentdunning.creditbureau

import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatchItem
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItem
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemStatus
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemType
import grails.transaction.Transactional

@Transactional
class CreditBureauDunningBatchItemService {

    public Boolean existsCreationItemTransmittedWithoutReturn(PaymentDunning paymentDunning) {
        Map search = [paymentDunning: paymentDunning,
                      type: CreditBureauDunningBatchItemType.CREATION,
                      status: CreditBureauDunningBatchItemStatus.TRANSMITTED]

        CreditBureauDunningBatchItem lastCreationItemTransmitted = CreditBureauDunningBatchItem.query(search).get()
        if (!lastCreationItemTransmitted) return false

        Map returnBatchItemSearch = [exists: true, paymentDunning: paymentDunning, creditBureauDunningBatchId: lastCreationItemTransmitted.creditBureauDunningBatch.id]
        Boolean existsReturnItem = CreditBureauDunningReturnBatchItem.query(returnBatchItemSearch).get().asBoolean()

        return !existsReturnItem
    }

    public void setAsError(CreditBureauDunningBatchItem item) {
        item.status = CreditBureauDunningBatchItemStatus.ERROR
        item.save(failOnError: true)
    }

    public void setAsTransmitted(CreditBureauDunningBatchItem item) {
        item.status = CreditBureauDunningBatchItemStatus.TRANSMITTED
        item.save(failOnError: true)
    }

    public CreditBureauDunningBatchItem saveCreationItem(PaymentDunning paymentDunning) {
        if (existsItemNotTransmitted(paymentDunning, CreditBureauDunningBatchItemType.CREATION)) return

        return save(paymentDunning, CreditBureauDunningBatchItemType.CREATION)
    }

    public CreditBureauDunningBatchItem saveRemovalItem(PaymentDunning paymentDunning) {
        if (existsItemNotTransmitted(paymentDunning, CreditBureauDunningBatchItemType.REMOVAL)) return

        return save(paymentDunning, CreditBureauDunningBatchItemType.REMOVAL)
    }

    public void cancelNotTransmittedCreationItemIfExists(PaymentDunning paymentDunning) {
        CreditBureauDunningBatchItem item = CreditBureauDunningBatchItem.query([paymentDunning: paymentDunning, type: CreditBureauDunningBatchItemType.CREATION, "statusList": [CreditBureauDunningBatchItemStatus.PENDING, CreditBureauDunningBatchItemStatus.ERROR]]).get()
        if (!item) return

        item.status = CreditBureauDunningBatchItemStatus.CANCELLED
        item.save(failOnError: true)
    }

    public Boolean existsPendingRemovalItem(PaymentDunning paymentDunning) {
        return CreditBureauDunningBatchItem.query([exists: true, paymentDunning: paymentDunning, type: CreditBureauDunningBatchItemType.REMOVAL, status: CreditBureauDunningBatchItemStatus.PENDING]).get()
    }

    private CreditBureauDunningBatchItem save(PaymentDunning paymentDunning, CreditBureauDunningBatchItemType type) {
        CreditBureauDunningBatchItem item = new CreditBureauDunningBatchItem()
        item.status = CreditBureauDunningBatchItemStatus.PENDING
        item.type = type
        item.paymentDunning = paymentDunning
        item.save(failOnError: true)

        return item
    }

    private Boolean existsItemNotTransmitted(PaymentDunning paymentDunning, CreditBureauDunningBatchItemType type) {
        return CreditBureauDunningBatchItem.query([paymentDunning: paymentDunning, type: type, "statusList": [CreditBureauDunningBatchItemStatus.PENDING, CreditBureauDunningBatchItemStatus.ERROR]]).get().asBoolean()
    }
}
