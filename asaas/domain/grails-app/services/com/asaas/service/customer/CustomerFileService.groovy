package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFile
import com.asaas.domain.file.AsaasFile
import com.asaas.file.FileValidator
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CustomerFileService {

	def fileService

    public CustomerFile save(Customer customer, CommonsMultipartFile documentFile) {
        final Long maxFileSize = 209715200

        CustomerFile customerFileValidated = new CustomerFile()

        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(documentFile, null, maxFileSize)
        if (!isValid) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(fileValidator, customerFileValidated)
            return customerFileValidated
        }

        AsaasFile asaasFile = fileService.saveFile(customer, documentFile, null)
        if (!asaasFile) {
            return null
        }

        CustomerFile customerFile = new CustomerFile()
        customerFile.customer = customer
        customerFile.file = asaasFile

        customerFile.save(flush: true, failOnError: true)

        return customerFile
    }


    public Boolean remove(Customer customer, Long id) {
		CustomerFile customerFile = CustomerFile.get(id)

		if (!customerFile) return false

		customerFile.deleted = true
		customerFile.save(failOnError: true)
		return true
	}

}
