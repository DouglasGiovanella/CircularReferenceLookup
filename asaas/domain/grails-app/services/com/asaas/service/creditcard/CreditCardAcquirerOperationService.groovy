package com.asaas.service.creditcard

import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.creditcardacquireroperation.CreditCardAcquirerOperationBatch
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationBatchStatus
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditCardAcquirerOperationService {

    public void updateAwaitingReprocessingToPending() {
        List<Long> creditCardAcquirerOperationIdList = CreditCardAcquirerOperation.query([
            column: "id",
            status: CreditCardAcquirerOperationStatus.AWAITING_REPROCESSING,
            "lastUpdated[ge]": CustomDateUtils.sumDays(new Date(), -1),
            sort: "lastUpdated",
            order: "asc"
        ]).list(max: 100)

        for (Long  creditCardAcquirerOperationId in creditCardAcquirerOperationIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CreditCardAcquirerOperation creditCardAcquirerOperation = CreditCardAcquirerOperation.get(creditCardAcquirerOperationId)
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.PENDING
                creditCardAcquirerOperation.save(failOnError: true)

                CreditCardAcquirerOperationBatch creditCardAcquirerOperationBatch = creditCardAcquirerOperation.creditCardAcquirerOperationBatch
                if (creditCardAcquirerOperationBatch.status.isProcessed()) {
                    creditCardAcquirerOperationBatch.status = CreditCardAcquirerOperationBatchStatus.APPROVED
                    creditCardAcquirerOperationBatch.save(failOnError: true)
                }
            }, [logErrorMessage: "CreditCardAcquirerOperationService.updateAwaitingReprocessingToPending >> Falha ao alterar o status para PENDING [${creditCardAcquirerOperationId}]"])
        }
    }

    public void setAsAwaitingReprocessing(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.AWAITING_REPROCESSING
        creditCardAcquirerOperation.save(failOnError: true)
    }

    public void setAsError(CreditCardAcquirerOperation creditCardAcquirerOperation, String detailsMessage) {
        creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.ERROR
        creditCardAcquirerOperation.details = detailsMessage
        creditCardAcquirerOperation.save(failOnError: true)
    }
}
