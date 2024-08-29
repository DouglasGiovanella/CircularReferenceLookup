package com.asaas.service.receivableanticipation

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationCompromisedItem
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.receivableanticipationcompromisedvalue.ReceivableAnticipationCompromisedValueCache
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationCompromisedItemService {

    public List<ReceivableAnticipationCompromisedItem> save(ReceivableAnticipation anticipation) {
        List<ReceivableAnticipationCompromisedItem> receivableAnticipationCompromisedItemList = []

        for (ReceivableAnticipationItem anticipationItem : anticipation.items) {
            ReceivableAnticipationCompromisedItem compromisedItem = buildCompromisedItem(anticipation, anticipationItem)
            compromisedItem.save(failOnError: true)
            receivableAnticipationCompromisedItemList.add(compromisedItem)
        }

        ReceivableAnticipationCompromisedValueCache.expireCache(anticipation.customer, anticipation.billingType)

        return receivableAnticipationCompromisedItemList
    }

    public void delete(ReceivableAnticipation anticipation, ReceivableAnticipationItem anticipationItem) {
        List<Long> receivableAnticipationItemId = anticipationItem ? [anticipationItem.id] : anticipation.items.collect({ it.id })
        Map search = ["receivableAnticipationItemId[in]": receivableAnticipationItemId, disableSort: true]

        List<ReceivableAnticipationCompromisedItem> compromisedItemToDeleteList = ReceivableAnticipationCompromisedItem.query(search).list()

        for (ReceivableAnticipationCompromisedItem compromisedItem : compromisedItemToDeleteList) {
            compromisedItem.delete(failOnError: true)
        }

        ReceivableAnticipationCompromisedValueCache.expireCache(anticipation.customer, anticipation.billingType)
    }

    private ReceivableAnticipationCompromisedItem buildCompromisedItem(ReceivableAnticipation anticipation, ReceivableAnticipationItem anticipationItem) {
        ReceivableAnticipationCompromisedItem compromisedItem = new ReceivableAnticipationCompromisedItem()
        compromisedItem.receivableAnticipation = anticipation
        compromisedItem.receivableAnticipationItem = anticipationItem
        compromisedItem.customer = anticipation.customer
        compromisedItem.accountOwner = anticipation.customer.accountOwner
        compromisedItem.customerAccount = anticipation.customerAccount
        compromisedItem.billingType = anticipation.billingType
        compromisedItem.partner = anticipation.partnerAcquisition.partner
        compromisedItem.value = anticipationItem.value

        return compromisedItem
    }
}
