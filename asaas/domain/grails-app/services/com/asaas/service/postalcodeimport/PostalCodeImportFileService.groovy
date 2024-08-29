package com.asaas.service.postalcodeimport

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.postalcode.importdata.PostalCodeImportFile
import com.asaas.postalcode.importdata.PostalCodeImportFileStatus
import com.asaas.domain.user.User
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class PostalCodeImportFileService {

    def fileService

    public PostalCodeImportFile upload(User user, CommonsMultipartFile file) {
        AsaasFile asaasFile = fileService.saveFile(user.customer, file, null)
        PostalCodeImportFile postalCodeImportFile = new PostalCodeImportFile()
        postalCodeImportFile.file = asaasFile
        postalCodeImportFile.createdBy = user
        postalCodeImportFile.save(failOnError: true)
        return postalCodeImportFile
    }

    public PostalCodeImportFile update(Long id, PostalCodeImportFileStatus status) {
        PostalCodeImportFile postalCodeImportFile = PostalCodeImportFile.get(id)
        postalCodeImportFile.status = status
        postalCodeImportFile.save(failOnError: true, flush: true)
        return postalCodeImportFile
    }
}
