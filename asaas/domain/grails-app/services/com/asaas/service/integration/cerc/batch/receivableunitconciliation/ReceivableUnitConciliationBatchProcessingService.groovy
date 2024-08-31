package com.asaas.service.integration.cerc.batch.receivableunitconciliation

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
class ReceivableUnitConciliationBatchProcessingService {

    def cercBatchService
    def fileService
    def receivableUnitConciliationService

    public void processDownloadBatch() {
        final String path = CercBatch.RECEIVABLE_UNIT_CONCILIATION_PATH
        String fileNamePrefix = buildFileNamePrefix()

        FileManager fileManager = FileManagerFactory.getFileManager(FileManagerType.CERC, path)
        List<String> fileNameList = fileManager.listFileName()
        if (!fileNameList) throw new BusinessException("Não foi encontrado nenhum arquivo no caminho [${path}]")
        List<String> filePathList = fileNameList.findAll { it.contains(fileNamePrefix) }

        for (String filePath : filePathList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError ({
                fileManager.setFullPath(filePath)

                File file = fileManager.read()
                String fileContent = Files.toString(file, Charsets.UTF_8)
                AsaasFile asaasFile = fileService.createFile(file.getName(), fileContent)
                cercBatchService.save(CercBatchType.RECEIVABLE_UNIT_CONCILIATION_ANALYTICS_REPORT, asaasFile)

            }, [logErrorMessage: "ReceivableUnitConciliationBatchProcessingService.downloadBatch >> Erro ao salvar o arquivo analítico [${filePath}]", onError: { hasError = true }])

            if (!hasError) {
                Utils.withNewTransactionAndRollbackOnError ({
                    fileManager.delete()
                }, [logErrorMessage: "ReceivableUnitConciliationBatchProcessingService.downloadBatch >> Erro ao deletar o arquivo analítico [${filePath}]"])
            }
        }
    }

    public void processPendingBatch() {
        Map cercBatchMap = CercBatch.pending([columnList: ["id", "file.id"], type: CercBatchType.RECEIVABLE_UNIT_CONCILIATION_ANALYTICS_REPORT]).get()
        if (!cercBatchMap) return

        Boolean hasError = false
        Utils.withNewTransactionAndRollbackOnError({
            receivableUnitConciliationService.conciliateBatch(AsaasFile.read(cercBatchMap."file.id"))
            cercBatchService.setAsProcessed(cercBatchMap.id)
        }, [logErrorMessage: "ReceivableUnitConciliationBatchProcessingService.processPendingBatch >> Erro ao processar o arquivo de conciliação de agenda [${cercBatchMap.id}]",
            onError: { hasError = true }])

        if (hasError) cercBatchService.setAsError(cercBatchMap.id)
    }

    private String buildFileNamePrefix() {
        Date referenceDate = new Date()
        String batchDate = referenceDate.format(CercBatch.FILE_NAME_DATE_FORMAT)

        return "${CercBatchType.RECEIVABLE_UNIT_CONCILIATION_ANALYTICS_REPORT.getCode()}_${CercBatch.PARTICIPANT_IDENTIFIER}_${batchDate}"
    }
}
