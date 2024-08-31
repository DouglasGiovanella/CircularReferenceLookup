package com.asaas.service.integration.bacen.bacenjud

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.bacen.bacenjud.JudicialLockItem
import com.asaas.integration.bacen.bacenjud.JudicialLockItemType
import grails.transaction.Transactional

@Transactional
class JudicialLockItemService {

    public void registerJudicialLock(Customer customer, BigDecimal lockValue, Long bacenLockItemId, Long financialTransactionId) {
        JudicialLockItem bacenJudicialLockItem = initCreation(customer, lockValue, financialTransactionId)
        bacenJudicialLockItem.bacenLockItemId = bacenLockItemId
        bacenJudicialLockItem.type = JudicialLockItemType.LOCK
        bacenJudicialLockItem.save(failOnError: true)
    }

    public void registerJudicialUnlock(Customer customer, BigDecimal unlockValue, Long bacenUnlockId, Long financialTransactionId) {
        JudicialLockItem bacenJudicialLockItem = initCreation(customer, unlockValue, financialTransactionId)
        bacenJudicialLockItem.bacenUnlockId = bacenUnlockId
        bacenJudicialLockItem.type = JudicialLockItemType.UNLOCK
        bacenJudicialLockItem.save(failOnError: true)
    }

    public void registerJudicialLockCancel(Customer customer, BigDecimal unlockValue, Long bacenCancelLockId, Long financialTransactionId) {
        JudicialLockItem bacenJudicialLockItem = initCreation(customer, unlockValue, financialTransactionId)
        bacenJudicialLockItem.bacenCancelLockId = bacenCancelLockId
        bacenJudicialLockItem.type = JudicialLockItemType.CANCEL_LOCK
        bacenJudicialLockItem.save(failOnError: true)
    }

    public void registerJudicialUnlockFromTransfer(Customer customer, BigDecimal unlockValue, Long bacenTransferRequestId, Long financialTransactionId) {
        JudicialLockItem bacenJudicialLockItem = initCreation(customer, unlockValue, financialTransactionId)
        bacenJudicialLockItem.bacenTransferRequestId = bacenTransferRequestId
        bacenJudicialLockItem.type = JudicialLockItemType.UNLOCK_FROM_TRANSFER
        bacenJudicialLockItem.save(failOnError: true)
    }

    public void registerJudicialLockFromTransfer(Customer customer, BigDecimal lockValue, Long bacenTransferRequestId, Long financialTransactionId) {
        JudicialLockItem bacenJudicialLockItem = initCreation(customer, lockValue, financialTransactionId)
        bacenJudicialLockItem.bacenTransferRequestId = bacenTransferRequestId
        bacenJudicialLockItem.type = JudicialLockItemType.LOCK_FROM_TRANSFER
        bacenJudicialLockItem.save(failOnError: true)
    }

    private JudicialLockItem initCreation(Customer customer, BigDecimal value, Long financialTransactionId) {
        JudicialLockItem judicialLockItem = new JudicialLockItem()
        judicialLockItem.customer = customer
        judicialLockItem.value = value
        judicialLockItem.financialTransactionId = financialTransactionId
        return judicialLockItem
    }
}
