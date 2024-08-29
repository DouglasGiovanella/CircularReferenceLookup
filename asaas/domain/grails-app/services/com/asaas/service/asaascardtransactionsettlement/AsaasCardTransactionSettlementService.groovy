package com.asaas.service.asaascardtransactionsettlement

import com.asaas.asaascard.AsaasCardTransactionSettlementStatus
import com.asaas.domain.asaascard.AsaasCardTransactionSettlement
import com.asaas.domain.asaascardtransaction.AsaasCardTransaction
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasCardTransactionSettlementService {

    public AsaasCardTransactionSettlement save(AsaasCardTransaction asaasCardTransaction) {
        AsaasCardTransactionSettlement asaasCardTransactionSettlement = new AsaasCardTransactionSettlement()
        asaasCardTransactionSettlement.asaasCardTransaction = asaasCardTransaction
        asaasCardTransactionSettlement.status = AsaasCardTransactionSettlementStatus.PENDING
        asaasCardTransactionSettlement.value = asaasCardTransaction.value
        asaasCardTransactionSettlement.save(failOnError: true)

        return asaasCardTransactionSettlement
    }

    public void schedule(Long asaasCardTransactionId, Date estimatedSettlementDate) {
        AsaasCardTransactionSettlement asaasCardTransactionSettlement = AsaasCardTransactionSettlement.query(asaasCardTransactionId: asaasCardTransactionId).get()
        asaasCardTransactionSettlement.estimatedSettlementDate = estimatedSettlementDate
        asaasCardTransactionSettlement.save(failOnError: true)
    }

    public AsaasCardTransactionSettlement updateStatusAndValue(AsaasCardTransactionSettlement asaasCardTransactionSettlement, BigDecimal value) {
        if (asaasCardTransactionSettlement.status.isDone()) return asaasCardTransactionSettlement

        asaasCardTransactionSettlement.value = asaasCardTransactionSettlement.value + value
        AsaasCardTransactionSettlementStatus status = asaasCardTransactionSettlement.value > 0 ? AsaasCardTransactionSettlementStatus.PENDING : AsaasCardTransactionSettlementStatus.CANCELLED
        return updateStatus(asaasCardTransactionSettlement, status)
    }

    public void updateStatusToDone() {
        List<Long> asaasCardTransactionSettlementIdList = AsaasCardTransactionSettlement.query(column: "id", "estimatedSettlementDate[le]": new Date().clearTime(), status: AsaasCardTransactionSettlementStatus.PENDING).list()

        Utils.forEachWithFlushSession(asaasCardTransactionSettlementIdList, 100, { Long asaasCardTransactionSettlementId ->
            Utils.withNewTransactionAndRollbackOnError({
                AsaasCardTransactionSettlement asaasCardTransactionSettlement = AsaasCardTransactionSettlement.get(asaasCardTransactionSettlementId)
                updateStatus(asaasCardTransactionSettlement, AsaasCardTransactionSettlementStatus.DONE)
            }, [logErrorMessage: "AsaasCardTransactionSettlementService.updateStatusAsaasCardTransactionSettlementDoneJob -> Erro ao atualizar status do item [id:${asaasCardTransactionSettlementId}]"])
        })
    }

    private AsaasCardTransactionSettlement updateStatus(AsaasCardTransactionSettlement asaasCardTransactionSettlement, AsaasCardTransactionSettlementStatus status) {
        asaasCardTransactionSettlement.status = status
        asaasCardTransactionSettlement.save(failOnError: true)

        return asaasCardTransactionSettlement
    }
}
