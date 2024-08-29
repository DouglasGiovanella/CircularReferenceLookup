package com.asaas.service.externalsettlement

import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.externalsettlement.ExternalSettlement
import com.asaas.externalsettlement.ExternalSettlementOrigin
import com.asaas.externalsettlement.ExternalSettlementStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class ProcessExternalSettlementService {

    def externalSettlementService
    def financialTransactionExternalSettlementService
    def creditTransferRequestService
    def externalSettlementContractualEffectSettlementBatchService

    public void processPendingSettlements() {
        final Integer maximumAmountOfDataPerExecution = 200
        List<Long> externalSettlementsPendingProcessIdList = ExternalSettlement.query([column: "id", status: ExternalSettlementStatus.PENDING]).list(max: maximumAmountOfDataPerExecution)

        for (Long externalSettlementId : externalSettlementsPendingProcessIdList) {
            Boolean processedSuccessfully = true

            Utils.withNewTransactionAndRollbackOnError({
                ExternalSettlement externalSettlement = ExternalSettlement.get(externalSettlementId)
                externalSettlementService.setAsInProgress(externalSettlement)

                financialTransactionExternalSettlementService.saveCredit(externalSettlement)
            }, [logErrorMessage: "ProcessExternalSettlementService.processPendingSettlements >> Erro no processamento da liquidação externa [${externalSettlementId}]",
                onError: { processedSuccessfully = false } ])

            if (!processedSuccessfully) {
                Utils.withNewTransactionAndRollbackOnError {
                    ExternalSettlement externalSettlement = ExternalSettlement.get(externalSettlementId)
                    externalSettlementService.setAsError(externalSettlement)
                }
            }
        }
    }

    public void transferSettlementsInProgress() {
        final Integer maximumAmountOfDataPerExecution = 200
        List<Long> externalSettlementsPendingProcessIdList = ExternalSettlement.query([column: "id", status: ExternalSettlementStatus.IN_PROGRESS, "transfer[isNull]": true]).list(max: maximumAmountOfDataPerExecution)

        for (Long externalSettlementId : externalSettlementsPendingProcessIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ExternalSettlement externalSettlement = ExternalSettlement.get(externalSettlementId)

                CreditTransferRequest creditTransferRequest = creditTransferRequestService.save(externalSettlement.asaasCustomer, externalSettlement.bankAccountInfo.id, externalSettlement.totalValue, [:])
                externalSettlement.transfer = creditTransferRequest.transfer
                externalSettlement.save(failOnError: true)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("ProcessExternalSettlementService.transferSettlementsInProgress >> Lock ao processar a transferência da liquidação externa [${externalSettlementId}]", exception)
                    return
                }

                Utils.withNewTransactionAndRollbackOnError {
                    ExternalSettlement externalSettlement = ExternalSettlement.get(externalSettlementId)
                    externalSettlementService.setAsError(externalSettlement)
                    AsaasLogger.error("ProcessExternalSettlementService.transferSettlementsInProgress >> Erro na transferência da liquidação externa [${externalSettlementId}]", exception)
                }
            }])
        }
    }

    public void finishProcessing() {
        final Integer maximumAmountOfDataPerExecution = 200
        List<Long> externalSettlementIdList = ExternalSettlement.query([column: "id", status: ExternalSettlementStatus.PRE_PROCESSED]).list(max: maximumAmountOfDataPerExecution)

        for (Long externalSettlementId : externalSettlementIdList) {
            Boolean processedSuccessfully = true

            Utils.withNewTransactionAndRollbackOnError({
                ExternalSettlement externalSettlement = ExternalSettlement.get(externalSettlementId)

                switch (externalSettlement.origin) {
                    case ExternalSettlementOrigin.CONTRACTUAL_EFFECT_SETTLEMENT_BATCH:
                        externalSettlementContractualEffectSettlementBatchService.confirmOrDenyBatch(externalSettlement)
                        break
                    default:
                        throw new NotImplementedException("Origem não implementada")
                }
            }, [logErrorMessage: "ProcessExternalSettlementService.finishProcessing >> Erro no processamento da liquidação externa [${externalSettlementId}]",
                onError: { processedSuccessfully = false }])

            if (!processedSuccessfully) {
                Utils.withNewTransactionAndRollbackOnError {
                    ExternalSettlement externalSettlement = ExternalSettlement.get(externalSettlementId)
                    externalSettlementService.setAsError(externalSettlement)
                }
            }
        }
    }
}
