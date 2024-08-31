package com.asaas.service.slc

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileType
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduledConfirmationReturnFile
import com.asaas.receivableUnitSettlement.ReceivableUnitSettlementScheduledConfirmationReturnFileItemVO
import com.asaas.receivableUnitSettlement.SettlementScheduleReturnFileVO
import com.asaas.receivableUnitSettlement.ReceivableUnitSettlementScheduledConfirmationReturnFileParser
import com.asaas.receivableUnitSettlement.SettlementScheduledReturnFileRetriever
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableUnitSettlementScheduledConfirmationReturnFileService {

    def fileService
    def receivableUnitSettlementScheduleReturnFileItemService

    public void retrieveFile(ReceivableUnitSettlementScheduleReturnFileType fileType) {
        final Integer flushEvery = 5
        List<SettlementScheduleReturnFileVO> settlementScheduleReturnFileVOList = SettlementScheduledReturnFileRetriever.retrieveConfirmation(fileType)

        Utils.forEachWithFlushSession(settlementScheduleReturnFileVOList, flushEvery, { SettlementScheduleReturnFileVO settlementScheduleReturnFileVO ->
            Utils.withNewTransactionAndRollbackOnError ( {
                save(settlementScheduleReturnFileVO.fileName, settlementScheduleReturnFileVO.fileContents, fileType)
                SettlementScheduledReturnFileRetriever.deleteProcessedFile(settlementScheduleReturnFileVO.fileName)
            }, [logErrorMessage: "ReceivableUnitSettlementScheduledConfirmationReturnFileService.retrieveFile >>> Erro ao salvar o arquivo [fileName: ${settlementScheduleReturnFileVO.fileName}]."])
        })
    }

    private void save(String fileName, String fileContents, ReceivableUnitSettlementScheduleReturnFileType fileType) {
        validateSave(fileName, fileContents)

        Long fileId
        Utils.withNewTransactionAndRollbackOnError({
            AsaasFile asaasFile = fileService.createFile(fileName, fileContents)

            ReceivableUnitSettlementScheduledConfirmationReturnFile receivableUnitSettlementScheduledConfirmationReturnFile = new ReceivableUnitSettlementScheduledConfirmationReturnFile()
            receivableUnitSettlementScheduledConfirmationReturnFile.fileName = fileName
            receivableUnitSettlementScheduledConfirmationReturnFile.file = asaasFile
            receivableUnitSettlementScheduledConfirmationReturnFile.type = fileType
            receivableUnitSettlementScheduledConfirmationReturnFile.save(failOnError: true)

            fileId = receivableUnitSettlementScheduledConfirmationReturnFile.id
        }, [logErrorMessage: "ReceivableUnitSettlementScheduledConfirmationReturnFileService.save >> Erro ao salvar o arquivo de confirmação [fileName: ${fileName}].",
            onError: { Exception exception -> throw exception }])

        validateItems(fileId, fileContents)
    }

    private void validateItems(Long fileId, String fileContents) {
        List<ReceivableUnitSettlementScheduledConfirmationReturnFileItemVO> fileItemsVOList = ReceivableUnitSettlementScheduledConfirmationReturnFileParser.parse(fileContents)
        final Integer maxItemsPerTransaction = 50

        for (List<ReceivableUnitSettlementScheduledConfirmationReturnFileItemVO> collatedItemsVOList : fileItemsVOList.collate(maxItemsPerTransaction)) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitSettlementScheduledConfirmationReturnFile confirmationReturnFile = ReceivableUnitSettlementScheduledConfirmationReturnFile.read(fileId)
                for (ReceivableUnitSettlementScheduledConfirmationReturnFileItemVO confirmationReturnFileItemVO : collatedItemsVOList) {
                    receivableUnitSettlementScheduleReturnFileItemService.processConfirmation(confirmationReturnFileItemVO, confirmationReturnFile)
                }
            }, [logErrorMessage: "ReceivableUnitSettlementScheduledConfirmationReturnFileService.validateItems >> Erro ao validar itens do arquivo de confirmação [fileId: ${fileId}] [${collatedItemsVOList.collect { it.settlementExternalIdentifier }}]"])
        }
    }

    private void validateSave(String fileName, String fileContents) {
        if (!fileContents) throw new RuntimeException("O arquivo está vazio.")

        if (!fileName) throw new RuntimeException("Informe o nome do arquivo.")

        Boolean isReceivableUnitSettlementScheduledConfirmationReturnFileAlreadyImported = ReceivableUnitSettlementScheduledConfirmationReturnFile.query([fileName: fileName, exists: true]).get().asBoolean()
        if (isReceivableUnitSettlementScheduledConfirmationReturnFileAlreadyImported) throw new RuntimeException("Arquivo já importado [${fileName}].")
    }
}
