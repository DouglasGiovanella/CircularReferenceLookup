package com.asaas.service.receivableregistration

import com.asaas.domain.file.AsaasFile
import com.asaas.integration.cerc.enums.CercBatchType
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class ReceivableRegistrationAdminService {

    def cercBatchService
    def fileService

    public void saveBatch(CercBatchType type, CommonsMultipartFile file) {
        String fileContents = file.inputStream.getText("UTF-8")
        String fileName = file.getOriginalFilename()

        AsaasFile asaasFile = fileService.createFile(fileName, fileContents)

        cercBatchService.save(type, asaasFile)
    }
}
