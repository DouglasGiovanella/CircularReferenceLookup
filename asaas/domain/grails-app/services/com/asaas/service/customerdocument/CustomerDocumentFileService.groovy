package com.asaas.service.customerdocument

import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.file.FileValidator
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyIdentificationCustomerDocumentFileAdapter
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class CustomerDocumentFileService {

	def customerDocumentFileVersionService
	def fileService

    public CustomerDocumentFile find(Map searchParams) {
        CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.query(searchParams).get()

        return customerDocumentFile
    }

    public List<Object> listColumn(String column, Map searchParams) {
        searchParams.column = column

        return CustomerDocumentFile.query(searchParams).list()
    }

    public Integer count(Map searchParams) {
        return CustomerDocumentFile.query(searchParams).count()
    }

	public CustomerDocumentFile save(CustomerDocument customerDocument, Long temporaryFileId) {
        final Long maxFileSize = 209715200 // bytes ou 200 megabytes

        TemporaryFile temporaryFile = TemporaryFile.find(customerDocument.group.customer, temporaryFileId)
        CustomerDocumentFile validatedCustomerDocumentFile = new CustomerDocumentFile()

        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(null, temporaryFile, maxFileSize)
        if (!isValid) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(fileValidator, validatedCustomerDocumentFile)
            return validatedCustomerDocumentFile
        }

        CustomerDocumentFile customerDocumentFile = new CustomerDocumentFile(customerDocument: customerDocument)
        customerDocumentFile.publicId = UUID.randomUUID().toString()
        customerDocumentFile.save(failOnError: true)

        customerDocumentFile.lastCustomerDocumentFileVersion = customerDocumentFileVersionService.save(customerDocumentFile, temporaryFileId)

		customerDocumentFile.save(flush: true, failOnError: true)

		return customerDocumentFile
	}

    public CustomerDocumentFile save(CustomerDocument customerDocument, ThirdPartyIdentificationCustomerDocumentFileAdapter thirdPartyFileAdapter) {
        CustomerDocumentFile customerDocumentFile = new CustomerDocumentFile(customerDocument: customerDocument)
        customerDocumentFile.publicId = UUID.randomUUID().toString()
        customerDocumentFile.save(flush: true, failOnError: true)

        customerDocumentFile.lastCustomerDocumentFileVersion = customerDocumentFileVersionService.save(customerDocumentFile, thirdPartyFileAdapter)

        return customerDocumentFile
    }

    public CustomerDocumentFile save(CustomerDocument customerDocument, AsaasFile asaasFile) {
        CustomerDocumentFile customerDocumentFile = new CustomerDocumentFile(customerDocument: customerDocument)
        customerDocumentFile.publicId = UUID.randomUUID().toString()
        customerDocumentFile.status = customerDocument.status
        customerDocumentFile.save(flush: true, failOnError: true)

        customerDocumentFile.lastCustomerDocumentFileVersion = customerDocumentFileVersionService.save(customerDocumentFile, asaasFile)

        return customerDocumentFile
    }

    public CustomerDocumentFile update(Customer customer, CustomerDocument customerDocument, Long customerDocumentFileId, Long temporaryFileId) {
		CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.find("from CustomerDocumentFile cdf where cdf.customerDocument.group.customer = :customer and cdf.customerDocument.id = :customerDocumentId and cdf.id = :customerDocumentFileId and cdf.deleted = false", [customer: customer, customerDocumentId: customerDocument.id, customerDocumentFileId: customerDocumentFileId])

		customerDocumentFile.status = CustomerDocumentStatus.PENDING
		customerDocumentFile.lastCustomerDocumentFileVersion = customerDocumentFileVersionService.save(customerDocumentFile, temporaryFileId)
		customerDocumentFile.save(flush: true, failOnError: true)

		return customerDocumentFile
	}

	public void deletePendingFilesFromCustomerDocument(CustomerDocument customerDocument) {
		List<CustomerDocumentFile> customerDocumentFiles = CustomerDocumentFile.pending([customerDocument: customerDocument]).list()
		customerDocumentFiles.each { documentFile ->
			documentFile.deleted = true
			documentFile.save(failOnError: true)
		}
	}

	public void removeList(Customer customer, List<String> idList) {
		for (id in idList) {
			remove(customer, id.toLong())
		}
	}

	public CustomerDocumentFile remove(Customer customer, Long id) {
		CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.find("from CustomerDocumentFile cdf where cdf.customerDocument.group.customer = :customer and cdf.id = :id and cdf.deleted = false", [customer: customer, id: id])

		if (!customerDocumentFile) return null

		customerDocumentFile.deleted = true
		customerDocumentFile.save(flush: true, failOnError: true)

		return customerDocumentFile
	}

	public List<CustomerDocumentFile> list(Customer customer, Long customerDocumentId) {
		List<CustomerDocumentFile> list = CustomerDocumentFile.executeQuery("from CustomerDocumentFile cdf where cdf.customerDocument.group.customer = :customer and cdf.customerDocument.id = :customerDocumentId and cdf.deleted = false", [customer: customer, customerDocumentId: customerDocumentId])

		return list
	}

    public List<CustomerDocumentFile> list(Map searchParams) {
        return CustomerDocumentFile.query(searchParams).list()
    }

	public List<CustomerDocumentFile> listNotApproved(Customer customer, Long customerDocumentId) {
		return CustomerDocumentFile.query([customer: customer, customerDocumentId: customerDocumentId, "status[ne]": CustomerDocumentStatus.APPROVED]).list()
	}

	public CustomerDocumentFile ignore(Long id) {
		CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.findById(id)
		customerDocumentFile.status = CustomerDocumentStatus.IGNORED
		customerDocumentFile.save(flush: true, failOnError: true)

		return customerDocumentFile
	}

	public byte[] buildPreview(Long id) {
		return fileService.buildDocumentPreview(id)
	}

    public byte[] readBytes(AsaasFile asaasFile) {
        return asaasFile?.getDocumentBytes()
    }
}
