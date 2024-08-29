package com.asaas.service.postalcodeimport

import com.asaas.domain.postalcode.importdata.PostalCodeImportFile
import com.asaas.domain.postalcode.importdata.PostalCodeImportLogItem
import com.asaas.postalcode.importdata.PostalCodeImportLogStatus
import com.asaas.postalcode.importdata.PostalCodeImportLogType
import grails.transaction.Transactional

@Transactional
class PostalCodeImportLogItemService {

    public PostalCodeImportLogItem savePostalCodeLogItem(Long importFileId, String postalCode, PostalCodeImportLogStatus status) {
        return save(importFileId, postalCode, status, PostalCodeImportLogType.POSTAL_CODE)
    }

    public PostalCodeImportLogItem saveCityLogItem(Long importFileId, String ibgeCode, PostalCodeImportLogStatus status) {
        return save(importFileId, ibgeCode, status, PostalCodeImportLogType.CITY)
    }

    private PostalCodeImportLogItem save(Long importFileId, String ibgeCode, PostalCodeImportLogStatus status, PostalCodeImportLogType type) {
        PostalCodeImportLogItem postalCodeImportLogItem = new PostalCodeImportLogItem()
        PostalCodeImportFile importFile = PostalCodeImportFile.get(importFileId)
        postalCodeImportLogItem.importFile = importFile
        postalCodeImportLogItem.type = type
        postalCodeImportLogItem.code = ibgeCode
        postalCodeImportLogItem.status = status
        postalCodeImportLogItem.save(flush: true, failOnError: true)
        return postalCodeImportLogItem
    }
}
