package com.asaas.service.integration.bacen.ccs

import com.asaas.domain.integration.bacen.ccs.BacenCcs
import com.asaas.domain.integration.bacen.ccs.batchfile.BacenCcsBatchFile
import com.asaas.domain.integration.bacen.ccs.batchfile.BacenCcsBatchFileItem
import com.asaas.domain.integration.bacen.ccs.batchfile.BacenCcsBatchFileStatus
import com.asaas.domain.integration.bacen.ccs.enums.BacenCcsSyncStatus
import com.asaas.integration.bacen.ccs.builders.BacenCcsXmlBuilder
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

import java.nio.charset.StandardCharsets

@Transactional
class BacenCcsBatchFileService {

    def fileService
    def messageService

    public BacenCcsBatchFile buildAndSave() {
        List<BacenCcs> bacenCcsList = BacenCcs.query(["syncStatus[in]": BacenCcsSyncStatus.awaitingSync(), "relationshipDate[lt]": new Date().clearTime(), sort: "id", order: "asc"]).list()

        BacenCcsBatchFile bacenCcsBatchFile = new BacenCcsBatchFile()
        bacenCcsBatchFile.status = BacenCcsBatchFileStatus.SENT
        bacenCcsBatchFile.movementDate = BacenCcsBatchFile.calculateMovementDate()
        bacenCcsBatchFile.batchFileNumber = buildBatchFileSequence(bacenCcsBatchFile.movementDate)

        String fileContent = new BacenCcsXmlBuilder().build(bacenCcsList, bacenCcsBatchFile.movementDate, bacenCcsBatchFile.batchFileNumber)
        bacenCcsBatchFile.fileId = createAsaasFile(fileContent, bacenCcsBatchFile.batchFileNumber)

        bacenCcsBatchFile.save(failOnError: true)

        for (BacenCcs bacenCcs : bacenCcsList) {
            BacenCcsBatchFileItem bacenCcsBatchFileItem = new BacenCcsBatchFileItem()
            bacenCcsBatchFileItem.bacenCcsBatchFile = bacenCcsBatchFile
            bacenCcsBatchFileItem.bacenCcs = bacenCcs
            bacenCcsBatchFileItem.save(failOnError: true)

            bacenCcs.syncStatus = BacenCcsSyncStatus.AWAITING_PARTNER
            bacenCcs.save(failOnError: true)
        }

        return bacenCcsBatchFile
    }

    private String buildBatchFileSequence(Date movementDate) {
        Integer batchFileSequence = BacenCcsBatchFile.getBatchFileDailyCount(movementDate)
        batchFileSequence++

        return CustomDateUtils.fromDate(movementDate, "yyyyMMdd") + batchFileSequence.toString().padLeft(4, "0")
    }

    private Long createAsaasFile(String fileContent, String batchFileNumber) {
        String fileName = "ACCS001-${batchFileNumber}.xml"
        Long asaasFileId = fileService.createFile(fileName, fileContent, StandardCharsets.UTF_16BE.toString())?.id
        AsaasLogger.info("BacenCcsBatchFileService -> Criado AsaasFile ID [${asaasFileId}] - ${fileName}")
        return asaasFileId
    }
}
