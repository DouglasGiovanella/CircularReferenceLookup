package com.asaas.service.paymentdunning.creditbureau

import com.asaas.boleto.BoletoBankInfo
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatch
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatchItem
import com.asaas.integration.serasa.manager.SerasaCreditBureauSftpManager
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.payment.PaymentDunningStatus
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchBuilder
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemType
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemStatus
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchStatus
import com.asaas.utils.Utils
import com.google.common.io.Files
import grails.transaction.Transactional
import org.apache.commons.io.Charsets

@Transactional
class CreditBureauDunningBatchTransmitionService {

    def bankSlipService
    def boletoService
    def creditBureauDunningBatchItemService
    def creditBureauDunningService
    def fileService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentDunningStatusHistoryService

    public Boolean transmit(Long batchId) {
        Boolean serasaResult = false

        Utils.withNewTransactionAndRollbackOnError({
            Boolean hasPreviousBatchNotTransmitted = CreditBureauDunningBatch.query([exists: true, "id[lt]": batchId, "status[ne]": CreditBureauDunningBatchStatus.TRANSMITTED]).get().asBoolean()
            if (hasPreviousBatchNotTransmitted) throw new RuntimeException("CreditBureauDunningBatchTransmitionService.transmit >> O lote [${batchId}] possui lotes anteriores que ainda não foram transmitidos!")

            Boolean isBatchStatusPending = CreditBureauDunningBatch.query([column: "id", id: batchId, status: CreditBureauDunningBatchStatus.PENDING]).get().asBoolean()
            if (!isBatchStatusPending) {
                throw new RuntimeException("CreditBureauDunningBatchTransmitionService.transmit >> O lote [${batchId}] não está pendente para envio!")
            }

            cancelConfirmedPaymentBatchItemsIfNecessary(batchId)
            buildAndSaveBatchFileContent(batchId)

            CreditBureauDunningBatch batch = CreditBureauDunningBatch.get(batchId)

            AsaasFile asaasFile = batch.file
            String fileContent = Files.toString(asaasFile.getFile(), Charsets.UTF_8)

            SerasaCreditBureauSftpManager serasaCreditBureauSftpManager = new SerasaCreditBureauSftpManager()
            serasaCreditBureauSftpManager.upload(asaasFile.originalName, fileContent)

            batch.status = CreditBureauDunningBatchStatus.TRANSMITTED
            batch.transmissionDate = new Date()
            batch.save(failOnError: true)

            serasaResult = true
        }, [logErrorMessage: "CreditBureauDunningBatchTransmitionService.transmit >> Erro no envio da remessa [${batchId}]"])

        updateBatchItemStatusAfterTransmission(batchId)

        if (serasaResult) {
            updatePaymentDunningStatusAfterTransmission(batchId)
            return serasaResult
        }

        Utils.withNewTransactionAndRollbackOnError({
            CreditBureauDunningBatch batch = CreditBureauDunningBatch.get(batchId)
            batch.status = CreditBureauDunningBatchStatus.ERROR
            batch.save(failOnError: true)
        }, [logErrorMessage: "CreditBureauDunningBatchTransmitionService.transmit >> Erro ao atualizar status da remessa [${batchId}]"])

        return serasaResult
    }

    private void buildAndSaveBatchFileContent(Long batchId) {
        CreditBureauDunningBatch batch = CreditBureauDunningBatch.get(batchId)
        List<CreditBureauDunningBatchItem> creditBureauDunningBatchItemList = CreditBureauDunningBatchItem.query([creditBureauDunningBatch: batch, status: CreditBureauDunningBatchItemStatus.PENDING]).list()

        String fileContent = buildFileContent(batch, creditBureauDunningBatchItemList)

        AsaasFile batchFile = fileService.createFile("serasa_negativacao_${batch.id}", fileContent)

        batch.file = batchFile
        batch.save(failOnError: true)
    }

    private void cancelConfirmedPaymentBatchItemsIfNecessary(Long batchId) {
        CreditBureauDunningBatch batch = CreditBureauDunningBatch.read(batchId)
        if (!batch.type.isCreation()) return

        List<Long> creditBureauDunningBatchItemIdList = CreditBureauDunningBatchItem.query([column: "id", creditBureauDunningBatch: batch, type: CreditBureauDunningBatchItemType.CREATION, status: CreditBureauDunningBatchItemStatus.PENDING]).list()

        Utils.forEachWithFlushSession(creditBureauDunningBatchItemIdList, 50, { Long itemId ->
            CreditBureauDunningBatchItem item = CreditBureauDunningBatchItem.get(itemId)

            if (item.paymentDunning.payment.status.hasBeenConfirmed()) {
                creditBureauDunningService.cancel(item.paymentDunning, PaymentDunningCancellationReason.PAYMENT_CONFIRMED)
            }
        })
    }

    private String buildFileContent(CreditBureauDunningBatch batch, List<CreditBureauDunningBatchItem> batchItemList) {
        CreditBureauDunningBatchBuilder builder = new CreditBureauDunningBatchBuilder()

        builder.appendFileHeader(batch)

        for (CreditBureauDunningBatchItem item in batchItemList) {
            builder.appendItemInfo(batch.type, item)

            String linhaDigitavel = boletoService.getLinhaDigitavel(item.paymentDunning.payment)
            String nossoNumero = getBankSlipNossoNumero(item.paymentDunning.payment)
            builder.appendBankSlipInfo(item, linhaDigitavel, nossoNumero)

            builder.appendBankSlipInstructionsInfo(item)
        }

        builder.appendTraillerInfo()

        return builder.getFileContent()
    }

    private String getBankSlipNossoNumero(Payment payment) {
        Map nossoNumeroInfoMap = bankSlipService.buildNossoNumeroAndNossoNumeroDigitMap(payment.boletoBank as BoletoBankInfo, payment)
        if (nossoNumeroInfoMap.nossoNumeroDigit) return "${nossoNumeroInfoMap.nossoNumero}${nossoNumeroInfoMap.nossoNumeroDigit}"

        return nossoNumeroInfoMap.nossoNumero
    }

    private void updateBatchItemStatusAfterTransmission(Long batchId) {
        Utils.withNewTransactionAndRollbackOnError({
            CreditBureauDunningBatch batch = CreditBureauDunningBatch.get(batchId)
            List<CreditBureauDunningBatchItem> batchItemList = CreditBureauDunningBatchItem.query([creditBureauDunningBatch: batch, status: CreditBureauDunningBatchItemStatus.PENDING]).list()

            for (CreditBureauDunningBatchItem batchItem in batchItemList) {
                if (batch.status.isTransmitted()) {
                    creditBureauDunningBatchItemService.setAsTransmitted(batchItem)
                } else {
                    creditBureauDunningBatchItemService.setAsError(batchItem)
                }
            }
        }, [logErrorMessage: "CreditBureauDunningBatchTransmitionService.updateBatchItemStatusAfterTransmission >> Erro ao atualizar status dos items da remessa [${batchId}]"])
    }

    private void updatePaymentDunningStatusAfterTransmission(Long batchId) {
        Utils.withNewTransactionAndRollbackOnError({
            CreditBureauDunningBatch batch = CreditBureauDunningBatch.get(batchId)
            if (!batch.status.isTransmitted()) return

            List<PaymentDunning> paymentDunningList = CreditBureauDunningBatchItem.query([column: "paymentDunning", creditBureauDunningBatch: batch]).list()

            PaymentDunningStatus newStatus = batch.type.isCreation() ? PaymentDunningStatus.AWAITING_PARTNER_APPROVAL : PaymentDunningStatus.AWAITING_PARTNER_CANCELLATION

            for (PaymentDunning paymentDunning in paymentDunningList) {
                if (paymentDunning.status.isPaid() && paymentDunning.payment.isDunningReceived()) continue

                paymentDunning.status = newStatus
                paymentDunning.save(failOnError: true)

                notificationDispatcherPaymentNotificationOutboxService.savePaymentDunning(paymentDunning)

                paymentDunningStatusHistoryService.save(paymentDunning)
            }
        }, [logErrorMessage: "CreditBureauDunningBatchTransmitionService.updatePaymentDunningStatusAfterTransmission >> Erro ao atualizar status das negativações na remessa [${batchId}]"])
    }
}
