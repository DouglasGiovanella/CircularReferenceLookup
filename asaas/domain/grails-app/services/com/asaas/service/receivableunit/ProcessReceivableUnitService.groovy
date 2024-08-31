package com.asaas.service.receivableunit

import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.enums.CercProcessingStatus
import com.asaas.integration.cerc.enums.webhook.CercEffectType
import com.asaas.log.AsaasLogger
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ProcessReceivableUnitService {

    def asyncActionService
    def cercContractualEffectSettlementService
    def receivableUnitService
    def receivableUnitItemService

    public void settleAwaitingSettlementItems() {
        final Integer maxItemsPerCycle = 150

        for (Map asyncActionData : asyncActionService.listPendingSettleReceivableUnitItem(maxItemsPerCycle)) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitItem paymentItem = ReceivableUnitItem.get(asyncActionData.receivableUnitItemId)

                if (paymentItem.status.isInSettlementSchedulingProcess()) {
                    settleScheduledPaymentItem(paymentItem)
                } else {
                    settleItem(paymentItem)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) {
                        Utils.withNewTransactionAndRollbackOnError({
                            AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                            if (asyncAction.status.isCancelled()) {
                                AsaasLogger.error("ProcessReceivableUnitService.settleAwaitingSettlementItems >> Quantidade máxima de tentativas para retry foi atingida [asyncActionId: ${asyncActionData.asyncActionId}]")
                            }
                        }, [logErrorMessage: "ProcessReceivableUnitService.settleAwaitingSettlementItems >> Erro ao fazer retry do asyncAction [asyncActionId: ${asyncActionData.asyncActionId}]"])

                        AsaasLogger.warn("ProcessReceivableUnitService.settleAwaitingSettlementItems >> Ocorreu um lock ao processar asyncAction [asyncActionId: ${asyncActionData.asyncActionId}]", exception)
                        return
                    }

                    AsaasLogger.error("ProcessReceivableUnitService.settleAwaitingSettlementItems >> Erro ao processar asyncAction [asyncActionId: ${asyncActionData.asyncActionId}]", exception)
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                }
            ])
        }
    }

    public void refreshReceivableUnit(ReceivableUnit receivableUnit) {
        receivableUnitService.consolidate(receivableUnit)

        if (receivableUnit.operationType.isFinish()) {
            List<ReceivableUnitItem> receivableUnitItemList = ReceivableUnitItem.notSettled(["receivableUnitId[or]": receivableUnit.id]).list()
            for (ReceivableUnitItem item : receivableUnitItemList) {
                receivableUnitItemService.settleIfPossible(item)
            }
        }

        List<Long> holderChangeReceivableUnitIdList = CercContractualEffect.query([
            distinct: "receivableUnit.id",
            affectedReceivableUnitId: receivableUnit.id,
            "effectType[in]": CercEffectType.listHolderChangeType(),
            "status[in]": [CercProcessingStatus.PROCESSED, CercProcessingStatus.FINISHED],
            disableSort: true
        ]).list()

        for (Long holderChangeReceivableUnitId : holderChangeReceivableUnitIdList) {
            ReceivableUnit holderChangeReceivableUnit = ReceivableUnit.get(holderChangeReceivableUnitId)
            receivableUnitService.consolidate(holderChangeReceivableUnit)
        }
    }

    private void settleItem(ReceivableUnitItem item) {
        if (!item.receivableUnit) {
            AsaasLogger.info("ProcessReceivableUnitService.settleItem >> O ReceivableUnitItem [${item.id}] não foi processado antes do recebimento do pagamento.")
            receivableUnitItemService.process(item)
        }

        cercContractualEffectSettlementService.useItemToSettleContractsIfNecessary(item)

        receivableUnitItemService.settleIfPossible(item)

        refreshReceivableUnit(item.receivableUnit)
    }

    private void settleScheduledPaymentItem(ReceivableUnitItem paymentItem) {
        receivableUnitItemService.settleIfPossible(paymentItem)

        List<ReceivableUnitItem> contractualEffectItemList = ReceivableUnitItem.query([paymentId: paymentItem.payment.id, "status[in]": ReceivableUnitItemStatus.listInSettlementSchedulingProcess(), "contractualEffect[isNotNull]": true]).list()
        for (ReceivableUnitItem contractualEffectItem : contractualEffectItemList) {
            cercContractualEffectSettlementService.settleContractualEffectItem(contractualEffectItem)
        }
    }

}
