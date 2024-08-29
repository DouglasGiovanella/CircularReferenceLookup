package com.asaas.service.domicileinstitution

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationFile
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationItem
import com.asaas.domain.file.AsaasFile
import com.asaas.domicileinstitution.DomicileInstitutionSettlementConciliationFileStatus
import com.asaas.domicileinstitution.DomicileInstitutionSettlementType
import com.asaas.domicileinstitution.builder.DomicileInstitutionConciliationFileBuilder
import com.asaas.log.AsaasLogger
import com.asaas.sftp.SftpManager

import grails.transaction.Transactional
import org.apache.commons.net.ftp.FTPConnectionClosedException

@Transactional
class DomicileInstitutionSettlementConciliationFileService {

    def fileService
    def grailsApplication

    public DomicileInstitutionSettlementConciliationFile buildConciliationFile(DomicileInstitutionSettlementType type, List<DomicileInstitutionSettlementConciliationItem> domicileInstitutionSettlementConciliationItemList, Boolean secondGrid) {
        DomicileInstitutionConciliationFileBuilder domicileInstitutionConciliationFileBuilder = new DomicileInstitutionConciliationFileBuilder()
        domicileInstitutionConciliationFileBuilder.buildFile(type, domicileInstitutionSettlementConciliationItemList, secondGrid)

        return save(domicileInstitutionConciliationFileBuilder.fileName, domicileInstitutionConciliationFileBuilder.controlNumber, domicileInstitutionConciliationFileBuilder.fileContents, type, domicileInstitutionSettlementConciliationItemList?.first()?.conciliationFile)
    }

    public void uploadConciliationFile(String fileName, String fileContents) {
        try {
            SftpManager sftpManager = new SftpManager(
                AsaasApplicationHolder.config.asaas.domicileinstition.sftp.username,
                null,
                null,
                AsaasApplicationHolder.config.asaas.domicileinstition.sftp.server,
                AsaasApplicationHolder.config.asaas.domicileinstition.sftp.port,
                AsaasApplicationHolder.config.asaas.domicileinstition.sftp.password)

            sftpManager.upload(fileName, fileContents, grailsApplication.config.asaas.domicileinstition.sftp.outbox)
        } catch (FTPConnectionClosedException ftpConnectionClosedException) {
            AsaasLogger.error("DomicileInstitutionSettlementConciliationFileService.uploadConciliationFile >>> Falha na realizar upload do arquivo de liquidação. Erro de conexão com o servidor FTP.", ftpConnectionClosedException)

            throw new RuntimeException("Falha ao realizar upload do arquivo de liquidação. Erro de conexão com o servidor FTP.")
        } catch (Exception exception) {
            AsaasLogger.error("DomicileInstitutionSettlementConciliationFileService.uploadConciliationFile >>> Falha no upload do arquivo de liquidação.", exception)

            throw new RuntimeException("Falha no upload do arquivo de liquidação.")
        }
    }

    private DomicileInstitutionSettlementConciliationFile save(String fileName, String controlNumber, String fileContents, DomicileInstitutionSettlementType type, DomicileInstitutionSettlementConciliationFile conciliationFileReference) {
        validateSave(fileName, fileContents)

        AsaasFile asaasFile = fileService.createFile("${fileName}.xml", fileContents)

        DomicileInstitutionSettlementConciliationFile domicileInstitutionSettlementConciliationFile = new DomicileInstitutionSettlementConciliationFile()

        domicileInstitutionSettlementConciliationFile.status = DomicileInstitutionSettlementConciliationFileStatus.PENDING
        domicileInstitutionSettlementConciliationFile.type = type
        domicileInstitutionSettlementConciliationFile.fileName = fileName
        domicileInstitutionSettlementConciliationFile.controlNumber = controlNumber
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
