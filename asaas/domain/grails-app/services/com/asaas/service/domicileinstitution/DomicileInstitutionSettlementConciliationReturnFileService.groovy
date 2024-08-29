package com.asaas.service.domicileinstitution

import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationFile
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationItem
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationReturnFile
import com.asaas.domain.file.AsaasFile
import com.asaas.domicileinstitution.DomicileInstitutionSettlementConciliationFileStatus
import com.asaas.domicileinstitution.DomicileInstitutionSettlementConciliationItemStatus
import com.asaas.domicileinstitution.parser.DomicileInstitutionSettlementConciliationReturnFileParser
import com.asaas.domicileinstitution.retriever.DomicileInstitutionSettlementFileRetriever
import com.asaas.domicileinstitution.vo.DomicileInstitutionSettlementConciliationReturnFileItemVO
import com.asaas.domicileinstitution.vo.DomicileInstitutionSettlementConciliationReturnFileVO
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class DomicileInstitutionSettlementConciliationReturnFileService {

    def fileService

    public void processReturnFiles() {
        List<DomicileInstitutionSettlementConciliationReturnFileVO> domicileInstitutionSettlementConciliationReturnFileVOList = DomicileInstitutionSettlementFileRetriever.retrieveReturns()

        Utils.forEachWithFlushSession(domicileInstitutionSettlementConciliationReturnFileVOList, 5, { DomicileInstitutionSettlementConciliationReturnFileVO domicileInstitutionSettlementConciliationReturnFileVO ->
            Utils.withNewTransactionAndRollbackOnError ( {
                String referenceFileName = getReferenceFileName(domicileInstitutionSettlementConciliationReturnFileVO.fileName)
                DomicileInstitutionSettlementConciliationFile domicileInstitutionSettlementConciliationFile = DomicileInstitutionSettlementConciliationFile.query([fileName: referenceFileName]).get()

                save(domicileInstitutionSettlementConciliationReturnFileVO.fileName, domicileInstitutionSettlementConciliationReturnFileVO.fileContents, domicileInstitutionSettlementConciliationFile)

                if (domicileInstitutionSettlementConciliationFile) {
                    processConciliationResponse(domicileInstitutionSettlementConciliationReturnFileVO, domicileInstitutionSettlementConciliationFile)
                } else {
                    AsaasLogger.error("DomicileInstitutionSettlementConciliationReturnFileService.processReturnFiles >>> Arquivo de referencia não encontrado. [returnFileName: ${domicileInstitutionSettlementConciliationReturnFileVO.fileName}]")
                }

                DomicileInstitutionSettlementFileRetriever.deleteProcessedFile(domicileInstitutionSettlementConciliationReturnFileVO.fileName)
            }, [logErrorMessage: "DomicileInstitutionSettlementFileService.processReturnFiles >>> Erro ao salvar o arquivo de retorno de conciliação [Nome do Arquivo: ${domicileInstitutionSettlementConciliationReturnFileVO.fileName}."])
        })
    }

    private void processConciliationResponse(DomicileInstitutionSettlementConciliationReturnFileVO domicileInstitutionSettlementConciliationReturnFileVO, DomicileInstitutionSettlementConciliationFile domicileInstitutionSettlementConciliationFile) {
        if (domicileInstitutionSettlementConciliationReturnFileVO.type.isError()) {
            domicileInstitutionSettlementConciliationFile.error = DomicileInstitutionSettlementConciliationReturnFileParser.parseError(domicileInstitutionSettlementConciliationReturnFileVO.fileContents)
            domicileInstitutionSettlementConciliationFile.status = DomicileInstitutionSettlementConciliationFileStatus.ERROR
            DomicileInstitutionSettlementConciliationItem.executeUpdate("update DomicileInstitutionSettlementConciliationItem set status = :status, error = :error, lastUpdated = :lastUpdated where conciliationFile = :conciliationFile", [status: DomicileInstitutionSettlementConciliationItemStatus.ERROR, error: domicileInstitutionSettlementConciliationFile.error, lastUpdated: new Date(), conciliationFile: domicileInstitutionSettlementConciliationFile])
            AsaasLogger.error("DomicileInstitutionSettlementConciliationReturnFileService.processConciliationResponse >>> Erro na conciliação do SLC para isntituição domicílio. [conciliationFileId: ${domicileInstitutionSettlementConciliationFile.id} ]")
        } else if (domicileInstitutionSettlementConciliationReturnFileVO.type.isInProcess()) {
            if (domicileInstitutionSettlementConciliationFile.status.isProcessed()) return

            domicileInstitutionSettlementConciliationFile.status = DomicileInstitutionSettlementConciliationFileStatus.RECEIVED
            domicileInstitutionSettlementConciliationFile.error = null
        } else if (domicileInstitutionSettlementConciliationReturnFileVO.type.isProcessed()) {
            domicileInstitutionSettlementConciliationFile.error = null
            List<DomicileInstitutionSettlementConciliationReturnFileItemVO> domicileInstitutionSettlementConciliationReturnFileItemVOList = DomicileInstitutionSettlementConciliationReturnFileParser.parseProcessed(domicileInstitutionSettlementConciliationReturnFileVO.fileContents)

            Boolean hasErrors = false
            for (DomicileInstitutionSettlementConciliationReturnFileItemVO item : domicileInstitutionSettlementConciliationReturnFileItemVOList) {
                DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem = DomicileInstitutionSettlementConciliationItem.query([controlNumber: item.controlNumber, settlementExternalIdentifier: item.settlementExternalIdentifier, conciliationFileId: domicileInstitutionSettlementConciliationFile.id]).get()
                if (domicileInstitutionSettlementConciliationItem) {
                    if (item.error) {
                        AsaasLogger.error("DomicileInstitutionSettlementConciliationReturnFileService.processConciliationResponse >>> Erro na conciliação de UR do SLC para isntituição domicílio. [conciliationFileId: ${domicileInstitutionSettlementConciliationFile.id} ]")
                        domicileInstitutionSettlementConciliationItem.error = item.error
                        domicileInstitutionSettlementConciliationItem.status = DomicileInstitutionSettlementConciliationItemStatus.ERROR
                        hasErrors = true
                    } else {
                        domicileInstitutionSettlementConciliationItem.error = null
                        domicileInstitutionSettlementConciliationItem.status = DomicileInstitutionSettlementConciliationItemStatus.SUCCESSFUL
                    }
                    domicileInstitutionSettlementConciliationItem.save(failOnError: true)
                } else {
                    AsaasLogger.error("DomicileInstitutionSettlementConciliationReturnFileService.processConciliationResponse >>> Item de conciliação não encontrado. [controlNumber: ${item.controlNumber}, settlementExternalIdentifier: ${item.settlementExternalIdentifier}, conciliationFileId: ${domicileInstitutionSettlementConciliationFile.id} ]")
                    hasErrors = true
                }
            }

            if (hasErrors) {
                domicileInstitutionSettlementConciliationFile.status = DomicileInstitutionSettlementConciliationFileStatus.ERROR
            } else {
                domicileInstitutionSettlementConciliationFile.status = DomicileInstitutionSettlementConciliationFileStatus.PROCESSED
            }
        }

        domicileInstitutionSettlementConciliationFile.save(failOnError: true)
    }

    private String getReferenceFileName(String returnFileName) {
        return returnFileName.replaceAll("_RET","").replaceAll("_PRO","").replaceAll("_ERR","").replaceAll(".XML", "")
    }

    private DomicileInstitutionSettlementConciliationReturnFile save(String fileName, String fileContents, DomicileInstitutionSettlementConciliationFile conciliationFileReference) {
        validateSave(fileName, fileContents)

        AsaasFile asaasFile = fileService.createFile("${fileName}.xml", fileContents)

        DomicileInstitutionSettlementConciliationReturnFile domicileInstitutionSettlementConciliationFile = new DomicileInstitutionSettlementConciliationReturnFile()

        domicileInstitutionSettlementConciliationFile.fileName = fileName
        domicileInstitutionSettlementConciliationFile.file = asaasFile
        domicileInstitutionSettlementConciliationFile.conciliationFileReference = conciliationFileReference
        domicileInstitutionSettlementConciliationFile.save(failOnError: true)

        return domicileInstitutionSettlementConciliationFile
    }

    private void validateSave(String fileName, String fileContents) {
        if (!fileContents) throw new RuntimeException("O arquivo está vazio.")
        if (!fileName) throw new RuntimeException("Informe o nome do arquivo.")
    }
}
