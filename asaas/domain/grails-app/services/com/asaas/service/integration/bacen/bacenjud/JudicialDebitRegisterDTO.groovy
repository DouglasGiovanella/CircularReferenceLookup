package com.asaas.service.integration.bacen.bacenjud

import com.asaas.domain.bacenjud.BacenJudTransferRequestItem

class JudicialDebitRegisterDTO {

    Long customerId

    Long transferRequestItemId

    Long reversedTransactionId

    BigDecimal unlockValue

    BigDecimal debitValue

    BigDecimal keepLockValue

    String detailForCustomer

    JudicialDebitRegisterDTO(BacenJudTransferRequestItem transferRequestItem) {
        transferRequestItemId = transferRequestItem.id
        customerId = transferRequestItem.customerId
        unlockValue = transferRequestItem.unlockValue
        debitValue = transferRequestItem.debitValue
        keepLockValue = transferRequestItem.keepLockValue
        detailForCustomer = transferRequestItem.transferRequest.detailForCustomer
        reversedTransactionId = transferRequestItem.lockItem?.financialTransactionId
    }
}
