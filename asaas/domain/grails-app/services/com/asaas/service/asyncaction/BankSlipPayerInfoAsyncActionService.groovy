package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.domain.asyncAction.BankSlipPayerInfoAsyncAction
import com.asaas.domain.bankslip.BankSlipPayerInfo
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BankSlipPayerInfoAsyncActionService {

    def baseAsyncActionService
    def bankSlipPayerInfoService

    public void saveIfNecessary(Payment payment) {
        if (!payment.billingType.isBoleto()) return
        if (payment.boletoBankId != Payment.ASAAS_ONLINE_BOLETO_BANK_ID) return

        Map actionData = [paymentId: payment.id, boletoBankId: payment.boletoBankId]
        baseAsyncActionService.save(new BankSlipPayerInfoAsyncAction(), actionData)
    }

    public Boolean process() {
        final Integer maxItemsPerCycle = 400
        final Integer itemsPerThread = 50
        final Integer flushEvery = 25
        final Integer batchSize = 25

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(BankSlipPayerInfoAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return false

        List<Long> asyncActionErrorList = Collections.synchronizedList([])
        List<Long> asyncActionIdProcessedList = Collections.synchronizedList([])
        ThreadUtils.processWithThreadsOnDemand(asyncActionDataList, itemsPerThread, { List<Map> asyncActionMapSubList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionMapSubList, batchSize, flushEvery, { Map asyncActionData ->
                try {
                    Boolean existsBankSlipPayerInfo = BankSlipPayerInfo.query([exists: true, paymentId: Utils.toLong(asyncActionData.paymentId)]).get().asBoolean()
                    if (!existsBankSlipPayerInfo) bankSlipPayerInfoService.saveForBoletoBank(asyncActionData.paymentId, asyncActionData.boletoBankId)

                    asyncActionIdProcessedList.add(asyncActionData.asyncActionId)
                } catch (Exception exception) {
                    if (!Utils.isLock(exception)) {
                        asyncActionErrorList.add(asyncActionData.asyncActionId)
                        AsaasLogger.error("BankSlipPayerInfoAsyncActionService.process >> asyncAction: [${asyncActionData.asyncActionId}]", exception)
                    }
                    throw exception
                }
            }, [logErrorMessage: "BankSlipPayerInfoAsyncActionService.process >> Erro ao processar asyncActions em lote"])
        })

        processErrorList(asyncActionErrorList)
        deleteProcessedList(asyncActionIdProcessedList)

        return true
    }

    private void processErrorList(List<Long> asyncActionErrorList) {
        final Integer flushEvery = 10
        final Integer batchSize = 10
        final Integer idListOperationCollateSize = 250

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionErrorList.collate(idListOperationCollateSize), batchSize, flushEvery, { List<Long> asyncActionErrorPartialList ->
            baseAsyncActionService.updateStatus(BankSlipPayerInfoAsyncAction, asyncActionErrorPartialList, AsyncActionStatus.ERROR)
        }, [logErrorMessage: "BankSlipPayerInfoAsyncActionService.processErrorList >> Erro ao processar setar asyncActions com ERRO em lote",
            appendBatchToLogErrorMessage: true])
    }

    private void deleteProcessedList(List<Long> asyncActionIdProcessedList) {
        final Integer flushEvery = 10
        final Integer batchSize = 10
        final Integer idListOperationCollateSize = 250

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionIdProcessedList.collate(idListOperationCollateSize), batchSize, flushEvery, { List<Long> asyncActionIdProcessedPartialList ->
            baseAsyncActionService.deleteList(BankSlipPayerInfoAsyncAction, asyncActionIdProcessedPartialList)
        }, [logErrorMessage: "BankSlipPayerInfoAsyncActionService.deleteProcessedList >> Erro ao remover asyncActions processadas em lote",
            appendBatchToLogErrorMessage: true])
    }
}
