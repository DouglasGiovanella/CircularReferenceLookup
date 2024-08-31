package com.asaas.service.customerdocument

import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.customerdocument.CustomerDocumentFileVersion
import com.asaas.domain.file.AsaasFile
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyIdentificationCustomerDocumentFileAdapter
import com.asaas.utils.FileUtils
import grails.transaction.Transactional

@Transactional
class CustomerDocumentFileVersionService {

	def fileService

	public CustomerDocumentFileVersion save(CustomerDocumentFile customerDocumentFile, Long temporaryFileId) {
		CustomerDocumentFileVersion customerDocumentFileVersion = new CustomerDocumentFileVersion(customerDocumentFile: customerDocumentFile, status: CustomerDocumentStatus.PENDING)
		customerDocumentFileVersion.save(failOnError: true)

		customerDocumentFileVersion.file = fileService.saveDocumentFromTemporary(customerDocumentFileVersion.customerDocumentFile.customerDocument.group.customer, temporaryFileId, [deleteTemporaryFile: true])
		customerDocumentFileVersion.save(flush: true, failOnError: true)

		return customerDocumentFileVersion
	}

    public CustomerDocumentFileVersion save(CustomerDocumentFile customerDocumentFile, ThirdPartyIdentificationCustomerDocumentFileAdapter thirdPartyFile) {
        CustomerDocumentFileVersion customerDocumentFileVersion = new CustomerDocumentFileVersion(customerDocumentFile: customerDocumentFile, status: CustomerDocumentStatus.PENDING)
        customerDocumentFileVersion.save(failOnError: true)

        String extension = thirdPartyFile.extension
        String fileName = thirdPartyFile.type.toString().toLowerCase() + "." + extension
        File file = FileUtils.downloadFile(thirdPartyFile.filePath, fileName, extension)
        AsaasFile asaasFile = fileService.createDocument(customerDocumentFile.customerDocument.group.customer, file, fileName)

        customerDocumentFileVersion.file = asaasFile
        customerDocumentFileVersion.save(flush: true, failOnError: true)

        return customerDocumentFileVersion
    }

    public CustomerDocumentFileVersion save(CustomerDocumentFile customerDocumentFile, AsaasFile asaasFile) {
        CustomerDocumentFileVersion customerDocumentFileVersion = new CustomerDocumentFileVersion(customerDocumentFile: customerDocumentFile, status: customerDocumentFile.status)
        customerDocumentFileVersion.file = asaasFile
        customerDocumentFileVersion.save(failOnError: true)

        return customerDocumentFileVersion
    }

    public CustomerDocumentFileVersion find(Map searchParams) {
        return CustomerDocumentFileVersion.query(searchParams).get()
    }
}
