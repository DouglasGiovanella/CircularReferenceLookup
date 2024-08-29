package com.asaas.service.integration.bacen.bacenjud

class LockAttemptResponseDTO {
    Long lockItemId

    BigDecimal lockedValue

    Date processedDate

    Long financialTransactionId

    Boolean hasTriggeredDisableCheckout
}
