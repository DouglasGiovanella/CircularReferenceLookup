package com.asaas.service.integration.cerc.batch.contractconciliation

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.CercBatch
import com.asaas.exception.BusinessException
import com.asaas.file.FileManager
import com.asaas.file.FileManagerFactory
import com.asaas.file.FileManagerType
import com.asaas.integration.cerc.enums.CercBatchType
import com.asaas.utils.Utils
import com.google.common.io.Files
import grails.transaction.Transactional
import org.apache.commons.io.Charsets

@Transactional
class CercContractConciliationBatchProcessingService {

    def cercBatchService
    def cercContractConciliationService
    def fileService

    public void downloadBatch(Date referenceDate) {
        String downloadPath = buildDownloadPath(referenceDate)
        FileManager fileManager = FileManagerFactory.getFileManager(FileManagerType.CERC, downloadPath)

        Utils.withNewTransactionAndRollbackOnError({
            List<String> fileNamesList = fileManager.listFileName()
            if (!fileNamesList) throw new BusinessException("Não foi encontrado nenhum arquivo no path [${downloadPath}]")
            fileManager.setFullPath(fileNamesList.first())

            File file = fileManager.read()
            String fileContent = Files.toString(file, Charsets.UTF_8)
            AsaasFile asaasFile = fileService.createFile(file.getName(), fileContent)
            cercBatchService.save(CercBatchType.CONTRACT_CONCILIATION, asaasFile)
        }, [logErrorMessage: "CercContractConciliationBatchProcessingService.downloadBatch >> Erro ao salvar o arquivo de conciliação de contratos do dia [${referenceDate}]"])
    }

    public void processPendingBatch() {
        Map cercBatchMap = CercBatch.pending([columnList: ["id", "file.id"], type: CercBatchType.CONTRACT_CONCILIATION]).get()
        if (!cercBatchMap) return

        Boolean processedSuccessfully = false
        Utils.withNewTransactionAndRollbackOnError({
            cercContractConciliationService.conciliateCercBatch(AsaasFile.read(cercBatchMap."file.id"))
            cercBatchService.setAsProcessed(cercBatchMap.id)

            processedSuccessfully = true
        }, [logErrorMessage: "CercContractConciliationBatchProcessingService.processPendingBatch >> Erro ao processar a remessa de conciliação de contratos [${cercBatchMap.id}]"])

        if (!processedSuccessfully) {
            Utils.withNewTransactionAndRollbackOnError({
                cercBatchService.setAsError(cercBatchMap.id)
            }, [logErrorMessage: "CercContractConciliationBatchProcessingService.processPendingBatch >> Erro ao marcar a remessa de conciliação de contratos [${cercBatchMap.id}] como erro"])
        }
    }

    private String buildDownloadPath(Date referenceDate) {
        String batchDate = referenceDate.format(CercBatch.FILE_NAME_DATE_FORMAT)
        String beginningOfFileName = "${CercBatchType.CONTRACT_CONCILIATION.getCode()}_${CercBatch.PARTICIPANT_IDENTIFIER}_${batchDate}"

        return "/${CercBatch.CONTRACT_CONCILIATION_PATH}/${beginningOfFileName}"
    }
}
