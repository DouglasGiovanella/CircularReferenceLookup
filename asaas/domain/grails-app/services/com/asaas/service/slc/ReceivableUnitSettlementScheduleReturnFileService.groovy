package com.asaas.service.slc

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFile
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileItem
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileStatus
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileType
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.log.AsaasLogger
import com.asaas.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileItemVO
import com.asaas.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileParser
import com.asaas.receivableUnitSettlement.SettlementScheduleReturnFileVO
import com.asaas.receivableUnitSettlement.SettlementScheduledReturnFileRetriever
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableUnitSettlementScheduleReturnFileService {

    def fileService
    def receivableUnitSettlementScheduleReturnFileItemService
    def receivableUnitSettlementSchedulingService

    public void retrieveFile(ReceivableUnitSettlementScheduleReturnFileType fileType) {
        List<SettlementScheduleReturnFileVO> settlementScheduleReturnFileVOList = SettlementScheduledReturnFileRetriever.retrieve(fileType)

        Utils.forEachWithFlushSession(settlementScheduleReturnFileVOList, 5, { SettlementScheduleReturnFileVO settlementScheduleReturnFileVO ->
            Utils.withNewTransactionAndRollbackOnError({
                save(settlementScheduleReturnFileVO.fileName, settlementScheduleReturnFileVO.fileContents, fileType)
                SettlementScheduledReturnFileRetriever.deleteProcessedFile(settlementScheduleReturnFileVO.fileName)
            }, [logErrorMessage: "ReceivableUnitSettlementScheduleReturnFileService.retrieveFile >>> Erro ao salvar o arquivo [${settlementScheduleReturnFileVO.fileName}]."])
        })
    }

    public void processPendingFiles() {
        Map returnFileInfoMap = ReceivableUnitSettlementScheduleReturnFile.query([columnList: ["id", "file", "type"], status: ReceivableUnitSettlementScheduleReturnFileStatus.PENDING]).get()
        if (!returnFileInfoMap) return

        AsaasFile file = returnFileInfoMap.file
        String fileContents = file.getFileContent()
        List<ReceivableUnitSettlementScheduleReturnFileItemVO> returnFileContentListVO = ReceivableUnitSettlementScheduleReturnFileParser.parse(fileContents)

        final Integer flushEvery = 100
        Utils.forEachWithFlushSession(returnFileContentListVO, flushEvery, { ReceivableUnitSettlementScheduleReturnFileItemVO returnFileContentVO ->
            Utils.withNewTransactionAndRollbackOnError({
                Boolean hasReturnFileItem = ReceivableUnitSettlementScheduleReturnFileItem.query([exists: true, "settlementExternalIdentifier": returnFileContentVO.settlementExternalIdentifier]).get().asBoolean()
                if (hasReturnFileItem) return

                ReceivableUnitSettlementScheduleReturnFileItem returnFileItem = receivableUnitSettlementScheduleReturnFileItemService.saveReceivableUnitSettlementScheduleReturnFile(returnFileContentVO, returnFileInfoMap.id)

                PaymentArrangement paymentArrangement = PaymentArrangement.valueOf(returnFileItem.paymentArrangement)
                if (paymentArrangement.isAbleToSlcAutomaticProcess()) scheduleSettlement(returnFileItem)
            }, [logErrorMessage: "ReceivableUnitSettlementScheduleReturnFileService.processPendingFiles >> Erro ao processar retornos de agendamento de liquidação de UR [ReceivableUnitSettlementScheduleReturnFileId: ${returnFileInfoMap.id}]"])
        })

        validateSettlementSchedule(PaymentArrangement.listAllowedToSlcAutomaticProcessArrangement())

        setAsProcessed(returnFileInfoMap.id)
    }

    private void scheduleSettlement(ReceivableUnitSettlementScheduleReturnFileItem returnFileItem) {
        Long receivableUnitId = ReceivableUnit.query([column: "id", externalIdentifier: returnFileItem.receivableUnitExternalIdentifier]).get()
        if (!receivableUnitId) return

        List<Long> readyToSheduleSettlementReceivableUnitItemIdList = getReadyToScheduleSettlementReceivableUnitItemIdList(receivableUnitId, returnFileItem.contractualEffectProtocol)
        receivableUnitSettlementSchedulingService.updateToScheduledSettlementFromReceivableUnitItemIdList(readyToSheduleSettlementReceivableUnitItemIdList)
    }

    private List<Long> getReadyToScheduleSettlementReceivableUnitItemIdList(Long receivableUnitId, String contractualEffectProtocol) {
        Map receivableUnitItemIdListDefaultFilters = [column: "id", "originalReceivableUnitId": receivableUnitId, status: ReceivableUnitItemStatus.READY_TO_SCHEDULE_SETTLEMENT]
        if (!contractualEffectProtocol) return ReceivableUnitItem.query(receivableUnitItemIdListDefaultFilters).list(readonly: true)

        Map effectIdDefaultFilters = [column: "id", protocol: contractualEffectProtocol]
        Long cercContractualEffectId = CercContractualEffect.query(effectIdDefaultFilters + [affectedReceivableUnitId: receivableUnitId]).get()
        if (!cercContractualEffectId) cercContractualEffectId = CercContractualEffect.query(effectIdDefaultFilters + [receivableUnitId: receivableUnitId]).get()

        return ReceivableUnitItem.query(receivableUnitItemIdListDefaultFilters + [contractualEffectId: cercContractualEffectId]).list(readonly: true)
    }

    private void validateSettlementSchedule(List<PaymentArrangement> arrangementList) {
        for (PaymentArrangement paymentArrangement : arrangementList) {
            Utils.withNewTransactionAndRollbackOnError({
                Map query = [
                    "exists": true,
                    "paymentArrangement": paymentArrangement,
                    status: ReceivableUnitItemStatus.READY_TO_SCHEDULE_SETTLEMENT
                ]

                if (paymentArrangement.isDebit()) query."estimatedCreditDate[le]" = new Date()

                Boolean existsNotScheduled = ReceivableUnitItem.query(query).get().asBoolean()
                if (existsNotScheduled) {
                    AsaasLogger.error("ReceivableUnitSettlementScheduleReturnFileService.validateSettlementSchedule >> Não foi possível agendar para liquidação todos os itens de UR do arranjo. [PaymentArrangement: ${paymentArrangement}]")
                }
            }, [logErrorMessage: "ReceivableUnitSettlementScheduleReturnFileService.validateSettlementSchedule >> Erro ao validar se todos os itens de UR foram agendados para liquidação. [PaymentArrangement: ${paymentArrangement}]"])
        }
    }

    private ReceivableUnitSettlementScheduleReturnFile save(String fileName, String fileContents, ReceivableUnitSettlementScheduleReturnFileType fileType) {
        validateSave(fileName, fileContents)

        AsaasFile asaasFile = fileService.createFile(fileName, fileContents)

        ReceivableUnitSettlementScheduleReturnFile receivableUnitSettlementScheduleReturnFile = new ReceivableUnitSettlementScheduleReturnFile()
        receivableUnitSettlementScheduleReturnFile.status = ReceivableUnitSettlementScheduleReturnFileStatus.PENDING
        receivableUnitSettlementScheduleReturnFile.fileName = fileName
        receivableUnitSettlementScheduleReturnFile.file = asaasFile
        receivableUnitSettlementScheduleReturnFile.type = fileType
        receivableUnitSettlementScheduleReturnFile.save(failOnError: true)

        return receivableUnitSettlementScheduleReturnFile
    }

    private void validateSave(String fileName, String fileContents) {
        if (!fileContents) throw new RuntimeException("O arquivo está vazio.")

        if (!fileName) throw new RuntimeException("Informe o nome do arquivo.")

        Boolean isReceivableUnitSettlementScheduleReturnFileAlreadyImported = ReceivableUnitSettlementScheduleReturnFile.query([fileName: fileName, exists: true]).get().asBoolean()
        if (isReceivableUnitSettlementScheduleReturnFileAlreadyImported) throw new RuntimeException("Arquivo já importado [${fileName}].")
    }

    private void setAsProcessed(Long id) {
        ReceivableUnitSettlementScheduleReturnFile receivableUnitSettlementScheduleReturnFile = ReceivableUnitSettlementScheduleReturnFile.get(id)
        receivableUnitSettlementScheduleReturnFile.status = ReceivableUnitSettlementScheduleReturnFileStatus.PROCESSED
        receivableUnitSettlementScheduleReturnFile.save(failOnError: true)
    }
}
