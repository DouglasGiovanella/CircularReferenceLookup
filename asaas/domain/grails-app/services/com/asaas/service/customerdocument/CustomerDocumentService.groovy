package com.asaas.service.customerdocument

import com.asaas.customer.CustomerStatus
import com.asaas.customerdocument.CustomerDocumentSaveThroughUserInterfaceAdapter
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocument.adapter.AccountDocumentValidateSaveAdapter
import com.asaas.customerdocument.querybuilder.CustomerDocumentQueryBuilder
import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.api.ApiConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.stage.StageCode
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CustomerDocumentService {

    def customerDocumentFileService
    def customerDocumentGroupService
    def customerDocumentAnalysisStatusService
    def customerRegisterStatusService
    def fileService
    def sessionFactory

    public CustomerDocument save(Customer customer, CustomerDocument customerDocument, List<String> temporaryFileIdList) {
        if (!temporaryFileIdList) return customerDocument

		customerDocument.status = CustomerDocumentStatus.PENDING
		customerDocument.save(flush: true, failOnError: true)

		if (customerDocument.isIdentificationSelfie()) {
			customerDocumentFileService.deletePendingFilesFromCustomerDocument(customerDocument)
		}

        for (String temporaryFileId : temporaryFileIdList) {
            CustomerDocumentFile customerDocumentFile = customerDocumentFileService.save(customerDocument, temporaryFileId.toLong())
            CustomerDocument validatedCustomerDocument = validateCustomerDocumentFileError(customerDocumentFile)
            if (validatedCustomerDocument.hasErrors()) {
                throw new BusinessException(validatedCustomerDocument.errors.allErrors[0].defaultMessage)
            }
        }

        customerDocumentGroupService.sendDocumentsToAnalysisIfPossible(customer)

		return customerDocument
    }

    public CustomerDocument saveThroughUserInterface(CustomerDocumentSaveThroughUserInterfaceAdapter saveThroughUserInterfaceAdapter) {
        AccountDocumentValidateSaveAdapter canSendDocumentThroughUserInterface = canSendCustomerDocumentThroughUserInterface(saveThroughUserInterfaceAdapter.customer, saveThroughUserInterfaceAdapter.customerDocumentType, saveThroughUserInterfaceAdapter.customerDocumentId)
        if (!canSendDocumentThroughUserInterface.isValid) {
            AsaasLogger.error("CustomerDocumentService.saveThroughUserInterface >> Tentativa de envio de um arquivo em condição não permitida: [${canSendDocumentThroughUserInterface.reason}] customerId: [${saveThroughUserInterfaceAdapter.customer.id}], customerDocumentType: [${saveThroughUserInterfaceAdapter.customerDocumentType}], customerDocumentId: [${saveThroughUserInterfaceAdapter.customerDocumentId}]")
            throw new BusinessException("Não foi possível enviar o(s) documentos(s). Por favor, entre em contato com o suporte.")
        }

        customerDocumentFileService.removeList(saveThroughUserInterfaceAdapter.customer, saveThroughUserInterfaceAdapter.removedFilesIdList)

        CustomerDocument customerDocument = save(saveThroughUserInterfaceAdapter.customer, saveThroughUserInterfaceAdapter.customerDocumentId, saveThroughUserInterfaceAdapter.customerDocumentGroupType, saveThroughUserInterfaceAdapter.customerDocumentType, saveThroughUserInterfaceAdapter.addedFilesIdList)

        customerDocument = update(saveThroughUserInterfaceAdapter.customer, customerDocument, saveThroughUserInterfaceAdapter.updatedFilesIdList)

        updateStatusIfNoFilesFound(customerDocument.id)

        return customerDocument
    }

    public CustomerDocument updateExpirationDate(Long customerDocumentId, String expirationDate) {
        CustomerDocument customerDocument = CustomerDocument.get(customerDocumentId)
        if (!customerDocument) throw new ResourceNotFoundException("Não foi possível encontrar o documento")

        validateExpirationDate(customerDocument, expirationDate)

        Date newExpirationDate = CustomDateUtils.toDate(expirationDate)
        customerDocument.expirationDate = newExpirationDate
        customerDocument.save(failOnError: true)

        return customerDocument
    }

    public CustomerDocument save(Customer customer, Long documentId, CustomerDocumentGroupType groupType, CustomerDocumentType documentType, List<String> temporaryFileIdList) {
        CustomerDocument customerDocument = getCustomerDocument(documentId, customer, documentType, groupType)
        if (!customerDocument) {
            customerDocument = createNewCustomerDocument(customer, documentType, groupType)
        }

        if (!customerDocument) throw new ResourceNotFoundException("Não foi possível encontrar o documento")

        return save(customer, customerDocument, temporaryFileIdList)
    }

	public CustomerDocument update(Customer customer, CustomerDocument customerDocument, List<String> temporaryFileIdList) {
		if (temporaryFileIdList.size() == 0) return customerDocument

		for (id in temporaryFileIdList) {
			Long customerDocumentFileId = id.tokenize("|")[0].toLong()
			Long temporaryFileId = id.tokenize("|")[1].toLong()

			CustomerDocumentFile customerDocumentFile = customerDocumentFileService.update(customer, customerDocument, customerDocumentFileId, temporaryFileId)
		}

		customerDocument.status = CustomerDocumentStatus.PENDING
		customerDocument.save(flush: true, failOnError: true)

        customerDocumentGroupService.sendDocumentsToAnalysisIfPossible(customer)

		return customerDocument
	}

	public CustomerDocument approve(CustomerDocument customerDocument) {
		customerDocument.status = CustomerDocumentStatus.APPROVED
		customerDocument.save(flush: true, failOnError: true)

		return customerDocument
	}

	public CustomerDocument reject(CustomerDocument customerDocument) {
		customerDocument.status = CustomerDocumentStatus.REJECTED
		customerDocument.save(flush: true, failOnError: true)

		return customerDocument
	}

	public void updateStatusIfNoFilesFound(Long customerDocumentId) {
		def session = sessionFactory.currentSession

		def query = session.createSQLQuery("select count(1) from customer_document_file cdf where cdf.customer_document_id = ${customerDocumentId} and cdf.deleted = 0")

		if (query.list()[0] == 0) {
			CustomerDocument customerDocument = CustomerDocument.findById(customerDocumentId)
			customerDocument.status = CustomerDocumentStatus.NOT_SENT
			customerDocument.save(flush: true, failOnError: true)
		}
	}

	public Integer retrieveNumberOfDocuments(Long customerId) {
		String sql = CustomerDocumentQueryBuilder.buildCountCustomerDocumentFiles()

		def query = sessionFactory.currentSession.createSQLQuery(sql)
		query.setLong("customerId", customerId)

		return query.list()[0]
	}

    public void updateDocumentsWhenCpfCnpjChanged(Customer customer, CustomerUpdateRequest customerUpdateRequest) {
		List<Long> identificationDocumentIds = CustomerDocument.query([column: 'id', customer: customer, typeList: CustomerDocumentType.identificationTypes(), statusList: CustomerDocumentStatus.pendingOrApproved()]).list()

        Boolean shouldUpdateStatus = shouldUpdateStatusWhenCpfCnpjChanged(customerUpdateRequest)

		for (Long customerDocumentId : identificationDocumentIds) {
            updateToNewIdentificationGroup(customerDocumentId, shouldUpdateStatus)
		}

        deleteNotIdentificationDocuments(customer, identificationDocumentIds)

        Boolean documentStatusIsApproved = customer.customerRegisterStatus.documentStatus.isApproved()
        if (shouldUpdateStatus && documentStatusIsApproved) {
            customerRegisterStatusService.updateDocumentStatus(customer, Status.AWAITING_APPROVAL)
            customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, DocumentAnalysisStatus.MANUAL_ANALYSIS_REQUIRED)
        }
	}

    public AccountDocumentValidateSaveAdapter canSendCustomerDocumentThroughUserInterface(Customer customer, CustomerDocumentType customerDocumentType, Long customerDocumentId) {
        AccountDocumentValidateSaveAdapter validateSaveThroughUserInterfaceAdapter = new AccountDocumentValidateSaveAdapter(true)

        if (customer.accountDisabled()) {
            validateSaveThroughUserInterfaceAdapter.isValid = false
            validateSaveThroughUserInterfaceAdapter.reason = "Conta desabilitada."
            return validateSaveThroughUserInterfaceAdapter
        }

        if (AsaasEnvironment.isProduction() && CustomerDocumentType.identificationTypes().contains(customerDocumentType)) {
            validateSaveThroughUserInterfaceAdapter.isValid = false
            validateSaveThroughUserInterfaceAdapter.reason = "Documento do tipo identificação."
            return validateSaveThroughUserInterfaceAdapter
        }

        Boolean isApprovedDocument = CustomerDocument.query([id: customerDocumentId, status: CustomerDocumentStatus.APPROVED, exists: true]).get().asBoolean()
        Boolean hasAccountAutoApprovalEnabled = customer.hasAccountAutoApprovalEnabled()

        if (isApprovedDocument && !hasAccountAutoApprovalEnabled) {
            validateSaveThroughUserInterfaceAdapter.isValid = false
            validateSaveThroughUserInterfaceAdapter.reason = "Documento já foi aprovado."
            return validateSaveThroughUserInterfaceAdapter
        }

        return validateSaveThroughUserInterfaceAdapter
    }

    public Boolean hasCustomerIdentificationDocumentNotSentOrRejected(Customer customer) {
        CustomerDocumentGroupType identificationGroupType = customerDocumentGroupService.getIdentificationGroupType(customer)
        if (!identificationGroupType) return true

        List<CustomerDocumentStatus> identificationCustomerDocumentStatusList = CustomerDocument.query([column: "status",
                                                                                                        customer: customer,
                                                                                                        "group.type": identificationGroupType,
                                                                                                        typeList: CustomerDocumentType.identificationTypes()]).list(readOnly: true)
        if (!identificationCustomerDocumentStatusList) return true

        Boolean hasCustomerIdentificationDocumentNotSentOrRejected = identificationCustomerDocumentStatusList.any {
            [CustomerDocumentStatus.REJECTED, CustomerDocumentStatus.NOT_SENT].contains(it)
        }

        return hasCustomerIdentificationDocumentNotSentOrRejected
    }

    public void deletePendingDocuments(Customer customer) {
        List<Long> listOfCustomerDocumentId = CustomerDocument.query([column: "id", customer: customer, status: CustomerDocumentStatus.PENDING]).list()
        for (Long customerDocumentId : listOfCustomerDocumentId) {
            delete(customer, customerDocumentId)
        }
    }

    public CustomerDocument savePowerOfAttorneyDocument(Customer customer) {
        CustomerDocumentGroup customerDocumentGroup = customerDocumentGroupService.save(customer, CustomerDocumentGroupType.CUSTOM)

        CustomerDocument customerDocument = new CustomerDocument()
        customerDocument.publicId = UUID.randomUUID().toString()
        customerDocument.groupPublicId = customerDocumentGroup.publicId
        customerDocument.type = CustomerDocumentType.POWER_OF_ATTORNEY
        customerDocument.group = customerDocumentGroup
        customerDocument.save(flush: true)

        return customerDocument
    }

    public BusinessValidation canRequestPowerOfAttorneyDocument(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customer.customerRegisterStatus.documentStatus.isApproved()) {
            businessValidation.addError("customerDocumentAdmin.requestDocumentRule.customerDocumentStatusApproved")
            return businessValidation
        }

        Boolean hasRequestedPowerOfAttorneyDocument = CustomerDocument.query([exists: true, customer: customer, type: CustomerDocumentType.POWER_OF_ATTORNEY]).get().asBoolean()
        if (hasRequestedPowerOfAttorneyDocument) {
            businessValidation.addError("customerDocumentAdmin.requestDocumentRule.alreadyRequested")
            return businessValidation
        }

        return businessValidation
    }

    public void copyFromAsaasFile(Long customerDocumentGroupId, AsaasFile file, String originDirectory) {
        CustomerDocumentGroup customerDocumentGroup = CustomerDocumentGroup.get(customerDocumentGroupId)
        Customer customer = customerDocumentGroup.customer

        CustomerDocument identificationSelfieDocument = CustomerDocument.query([customer: customer, type: CustomerDocumentType.IDENTIFICATION_SELFIE]).get()
        if (!identificationSelfieDocument) {
            identificationSelfieDocument = new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION_SELFIE, group: customerDocumentGroup)
            identificationSelfieDocument.publicId = UUID.randomUUID().toString()
            identificationSelfieDocument.groupPublicId = customerDocumentGroup.publicId
        }
        approve(identificationSelfieDocument)

        AsaasFile copiedAsaasFile = fileService.saveAsaasFileCopy(customer, file, originDirectory, AsaasFile.DOCUMENTS_DIRECTORY)
        customerDocumentFileService.save(identificationSelfieDocument, copiedAsaasFile)
    }

    public List<CustomerDocument> list(Long customerId, Map searchParams) {
        searchParams.customerId = customerId

        List<CustomerDocument> customerDocumentList = CustomerDocument.query(searchParams).list()
        return customerDocumentList
    }

    public CustomerDocument find(Long customerId, Map searchParams) {
        searchParams.customerId = customerId

        return CustomerDocument.query(searchParams).get()
    }

    public Boolean exists(Long customerId, Map searchParams) {
        searchParams.customerId = customerId
        searchParams.exists = true

        return CustomerDocument.query(searchParams).get().asBoolean()
    }

    public List<Long> findCustomerIdWithExpiredDocumentList() {
        List<Long> customerIdList = CustomerDocument.createCriteria().list(readOnly: true) {
            projections {
                distinct "customer.id"
            }

            createAlias("group", "group")
            createAlias("group.customer", "customer")
            createAlias("customer.customerRegisterStatus", "customerRegisterStatus")
            createAlias("customer.currentCustomerStage", "currentCustomerStage")
            createAlias("currentCustomerStage.stage", "stage")

            eq("deleted", false)
            eq("customer.deleted", false)
            eq("status", CustomerDocumentStatus.APPROVED)
            isNotNull("expirationDate")
            lt("expirationDate", new Date())
            not { 'in'("customer.status", CustomerStatus.inactive()) }
            eq("customerRegisterStatus.generalApproval", GeneralApprovalStatus.APPROVED)
            'in'("stage.code", [StageCode.CONVERTED, StageCode.RETAINED, StageCode.RECOVERED])

            notExists ApiConfig.where {
                setAlias("apiConfig")

                eq("deleted", false)
                eqProperty("apiConfig.provider.id", "customer.accountOwner.id")
                eq("apiConfig.autoApproveCreatedAccount", true)
            }.id()
        }

        return customerIdList
    }

    private CustomerDocument getCustomerDocument(Long documentId, Customer customer, CustomerDocumentType documentType, CustomerDocumentGroupType groupType) {
        if (documentId) return CustomerDocument.get(documentId)
        if (!documentType || !groupType) return null

        return CustomerDocument.query([customerId: customer.id, type: documentType, "group.type": groupType]).get()
    }

    private CustomerDocument createNewCustomerDocument(Customer customer, CustomerDocumentType documentType, CustomerDocumentGroupType groupType) {
        if (!documentType || !groupType) return null

        CustomerDocumentGroup customerDocumentGroup = CustomerDocumentGroup.query([type: groupType, customer: customer]).get()
        if (!customerDocumentGroup) {
            customerDocumentGroup = customerDocumentGroupService.save(customer, groupType)
        }

        CustomerDocument customerDocument = new CustomerDocument()
        customerDocument.publicId = UUID.randomUUID().toString()
        customerDocument.groupPublicId = customerDocumentGroup.publicId
        customerDocument.type = documentType
        customerDocument.group = customerDocumentGroup
        customerDocument.save(flush: true, failOnError: true)

        return customerDocument
    }

    private CustomerDocument delete(Customer customer, Long id) {
        CustomerDocument customerDocument = CustomerDocument.query([customerId: customer.id, id: id]).get()
        if (!customerDocument) return null

        for (CustomerDocumentFile customerDocumentFile : customerDocument.getCustomerDocumentFiles()) {
            customerDocumentFile.deleted = true
            customerDocumentFile.save(flush: true, failOnError: true)
        }

        customerDocument.deleted = true
        customerDocument.save(flush: true, failOnError: true)

        Boolean existingCustomerDocument = CustomerDocument.query([group: customerDocument.group, column: "id"]).get().asBoolean()
        if (!existingCustomerDocument) {
            customerDocument.group.deleted = true
            customerDocument.group.save(failOnError: true)
        }

        return customerDocument
    }

    private void updateToNewIdentificationGroup(Long customerDocumentId, Boolean shouldUpdateStatus) {
        CustomerDocument customerDocument = CustomerDocument.get(customerDocumentId)
        Customer customer = customerDocument.group.customer

        List<CustomerDocumentGroupType> customerDocumentGroupTypeList = customerDocumentGroupService.buildGroupListByCustomerType(customer).collect { it.type }
        List<CustomerDocumentGroupType> identificationDocumentGroupList = CustomerDocumentGroupType.includeIdentificationDocumentList()

        CustomerDocumentGroupType newIdentificationGroupType = customerDocumentGroupTypeList.intersect(identificationDocumentGroupList).first()

        CustomerDocumentGroup newIdentificationGroup = customerDocumentGroupService.save(customer, newIdentificationGroupType)

        customerDocument.group = newIdentificationGroup
        customerDocument.save(failOnError: true, flush: true)

         if (shouldUpdateStatus) updateIdentificationDocumentsStatusToPending(customerDocumentId)

    }

    private void updateIdentificationDocumentsStatusToPending(Long customerDocumentId) {
        CustomerDocument customerDocument = CustomerDocument.get(customerDocumentId)
        for (CustomerDocumentFile item : customerDocument.getCustomerDocumentFiles()) {
            item.status = CustomerDocumentStatus.PENDING
            item.save(failOnError: true)
        }
        customerDocument.status = CustomerDocumentStatus.PENDING
        customerDocument.save(failOnError: true, flush: true)
    }

    private void deleteNotIdentificationDocuments(Customer customer, List<Long> identificationDocumentIds) {
        Map search = [:]
        if (identificationDocumentIds) search.'id[notIn]' = identificationDocumentIds
		List<Long> listOfCustomerDocumentId = CustomerDocument.query(search + [column: "id", customer: customer]).list()
		for (Long customerDocumentId : listOfCustomerDocumentId) {
			delete(customer, customerDocumentId)
		}
	}

    private CustomerDocument validateCustomerDocumentFileError(CustomerDocumentFile customerDocumentFile) {
        CustomerDocument customerDocument = new CustomerDocument()
        if (customerDocumentFile.hasErrors()) {
            DomainUtils.copyAllErrorsFromObject(customerDocumentFile, customerDocument)
        }
        return customerDocument
    }

    private void validateExpirationDate(CustomerDocument customerDocument, String expirationDate) {
        if (!customerDocument.type.hasExpirationDate()) {
            throw new BusinessException(Utils.getMessageProperty("customerDocument.updateCustomerDocumentExpirationDate.hasNoExpirationDate"))
        }

        if (!CustomDateUtils.isValidFormat("dd/mm/yyyy", expirationDate)) {
            throw new BusinessException(Utils.getMessageProperty("customerDocument.updateCustomerDocumentExpirationDate.formatDateError"))
        }
    }

    private Boolean shouldUpdateStatusWhenCpfCnpjChanged(CustomerUpdateRequest customerUpdateRequest) {
        Boolean changedFromFisicaToMei = customerUpdateRequest.provider.personType.isFisica() && customerUpdateRequest.companyType?.isMEI()

        if (!changedFromFisicaToMei) return true

        RevenueServiceRegister revenueServiceRegister = customerUpdateRequest.provider.getRevenueServiceRegister()
        String ownerCpf = revenueServiceRegister?.getMEIOwnerCpf()
        if (ownerCpf == customerUpdateRequest.provider.cpfCnpj) return false

        return true
    }
}
