package com.asaas.service.customerdocumentgroup

import com.asaas.customer.CompanyType
import com.asaas.customer.PersonType
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customerdocument.CustomCustomerDocument
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.domain.proofOfLife.CustomerProofOfLife
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.proofOfLife.ProofOfLifeType
import com.asaas.status.Status
import grails.transaction.Transactional

@Transactional
class CustomerDocumentGroupService {

    def customerDocumentAnalysisStatusService
    def customerRegisterStatusService
    def revenueServiceRegisterService

	public CustomerDocumentGroup save(Customer customer, CustomerDocumentGroupType type) {
		CustomerDocumentGroup customerDocumentGroup = CustomerDocumentGroup.query([customer: customer, type: type]).get()

        if (!customerDocumentGroup.asBoolean()) {
            customerDocumentGroup = new CustomerDocumentGroup(customer: customer, type: type)
            customerDocumentGroup.publicId = UUID.randomUUID().toString()
            customerDocumentGroup.save(flush: true, failOnError: true)
        }

        saveDocumentListIfNecessary(customerDocumentGroup)

 		customerDocumentGroup.customerDocumentsAsList = []
		customerDocumentGroup.customerDocumentsAsList.addAll(customerDocumentGroup.findCustomerDocumentList())
		Collections.sort(customerDocumentGroup.customerDocumentsAsList)

		return customerDocumentGroup
	}

	public List<CustomerDocumentGroup> buildGroupListByCustomerType(Customer customer) {
		List<CustomerDocumentGroup> defaultCustomerDocumentGroupList = []

        PersonType personType = getCustomerPersonType(customer)
        CompanyType companyType = getCustomerCompanyType(customer)

		if (personType == PersonType.FISICA) {
			CustomerDocumentGroup customerDocumentGroupFisica = customerDocumentGroupForPersonTypeFisica(customer)
			if (customerDocumentGroupFisica) defaultCustomerDocumentGroupList.add(customerDocumentGroupFisica)
		} else if (personType == PersonType.JURIDICA) {
			if (companyType == CompanyType.INDIVIDUAL) {
				defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.INDIVIDUAL_COMPANY, customer: customer))
			} else if (companyType == CompanyType.MEI) {
				defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.MEI, customer: customer))
			} else if (companyType == CompanyType.LIMITED) {
                if (socialContactIsRequired(customer)) {
                    defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.LIMITED_COMPANY, customer: customer))
                }
				defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.PARTNER, customer: customer))
			} else if (companyType in [CompanyType.ASSOCIATION, CompanyType.NON_PROFIT_ASSOCIATION]) {
				defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.ASSOCIATION, customer: customer))
				defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.DIRECTOR, customer: customer))
			}
		}

        if (shouldRequestBankAccountOwnerDocument(customer)) {
            defaultCustomerDocumentGroupList.add(customerDocumentGroupForBankOwner(customer))
            if (customer.personType.isJuridica()) defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.ALLOW_BANK_ACCOUNT_DEPOSIT_STATEMENT, customer: customer))
        }

        Boolean hasCustomDocumentGroup = CustomerDocumentGroup.query([column: 'id', type: CustomerDocumentGroupType.CUSTOM, customer: customer]).get().asBoolean()
        if (hasCustomDocumentGroup) {
            defaultCustomerDocumentGroupList.add(new CustomerDocumentGroup(type: CustomerDocumentGroupType.CUSTOM, customer: customer))
        }

		return defaultCustomerDocumentGroupList
	}

    public CustomerDocumentGroupType getIdentificationGroupType(Customer customer) {
        PersonType personType = getCustomerPersonType(customer)
        CompanyType companyType = getCustomerCompanyType(customer)

        if (personType == PersonType.FISICA) return customerDocumentGroupTypeForPersonTypeFisica(customer)

        if (!companyType) return null

        if (companyType.isIndividual()) return CustomerDocumentGroupType.INDIVIDUAL_COMPANY

        if (companyType.isMEI()) return CustomerDocumentGroupType.MEI

        if (companyType.isLimited()) return CustomerDocumentGroupType.PARTNER

        if ([CompanyType.ASSOCIATION, CompanyType.NON_PROFIT_ASSOCIATION].contains(companyType)) return CustomerDocumentGroupType.DIRECTOR

        throw new RuntimeException("Não foi possível encontrar o tipo de grupo de documento para o Customer ${customer.id}")
    }

    public List<CustomerDocumentGroup> buildListForCustomer(Customer customer) {
        List<CustomerDocumentGroup> defaultCustomerDocumentGroupList = buildGroupListByCustomerType(customer)
        for (CustomerDocumentGroup group in defaultCustomerDocumentGroupList) {
            group.customerDocuments = []
            group.customerDocuments.addAll(buildCustomerDocumentListByGroup(group))
        }

        List<CustomerDocumentGroup> resultCustomerDocumentGroupList = []

        for (CustomerDocumentGroup defaultGroup in defaultCustomerDocumentGroupList) {
            List<CustomerDocumentGroup> existingGroupList = CustomerDocumentGroup.findAll("from CustomerDocumentGroup where customer = :customer and type = :type and deleted = false", [customer: customer, type: defaultGroup.type])
            if (existingGroupList.size == 0) {
                resultCustomerDocumentGroupList.add(defaultGroup)
            } else {
                for (CustomerDocumentGroup existingGroup in existingGroupList) {
                    CustomerDocumentGroup resultGroup = new CustomerDocumentGroup()

                    resultGroup.id = existingGroup.id
                    resultGroup.type = existingGroup.type
                    resultGroup.publicId = existingGroup.publicId

                    resultGroup.customerDocuments = []
                    for (CustomerDocument defaultCustomerDocument in defaultGroup.customerDocuments) {
                        if (defaultCustomerDocument.id)  {
                            resultGroup.customerDocuments.add(defaultCustomerDocument)
                            continue
                        }

                        CustomerDocument existingCustomerDocument = CustomerDocument.query([group: existingGroup, type: defaultCustomerDocument.type]).get()

                        if (!existingCustomerDocument) {
                            resultGroup.customerDocuments.add(new CustomerDocument(type: defaultCustomerDocument.type, status: CustomerDocumentStatus.NOT_SENT))
                        } else {
                            CustomerDocument resultCustomerDocument = new CustomerDocument()

                            resultCustomerDocument.id = existingCustomerDocument.id
                            resultCustomerDocument.type = existingCustomerDocument.type
                            resultCustomerDocument.status = existingCustomerDocument.status
                            resultCustomerDocument.expirationDate = existingCustomerDocument.expirationDate
                            resultCustomerDocument.group = resultGroup
                            resultCustomerDocument.lastUpdated = existingCustomerDocument.lastUpdated
                            resultCustomerDocument.publicId = existingCustomerDocument.publicId
                            resultCustomerDocument.groupPublicId = existingCustomerDocument.groupPublicId

                            resultGroup.customerDocuments.add(resultCustomerDocument)
                        }
                    }

                    resultCustomerDocumentGroupList.add(resultGroup)
                }
            }
        }

        for (CustomerDocumentGroup group in resultCustomerDocumentGroupList) {
            group.customer = customer
            group.customerDocumentsAsList = []
            group.customerDocumentsAsList.addAll(group.customerDocuments)

            Collections.sort(group.customerDocumentsAsList)
        }

        return resultCustomerDocumentGroupList
    }

    public void deleteCustom(Customer customer) {
        List<CustomerDocumentGroup> customerDocumentGroupList = CustomerDocumentGroup.query([customer: customer, type: CustomerDocumentGroupType.CUSTOM]).list()
        for (CustomerDocumentGroup customerDocumentGroup : customerDocumentGroupList) {
            Boolean hasOtherTypesOfDocuments = CustomerDocument.query([exists: true, groupId: customerDocumentGroup.id, "type[ne]": CustomerDocumentType.CUSTOM]).get().asBoolean()

            if (!hasOtherTypesOfDocuments) {
                customerDocumentGroup.deleted = true
                customerDocumentGroup.save(failOnError: true)
            }
        }

        List<CustomerDocument> customerDocumentList = CustomerDocument.query([customerId: customer.id, type: CustomerDocumentType.CUSTOM]).list()
        for (CustomerDocument customerDocument : customerDocumentList) {
            customerDocument.deleted = true
            customerDocument.save(flush: true, failOnError: true)

            List<CustomerDocumentFile> customerDocumentFileList = CustomerDocumentFile.query([customerDocument: customerDocument]).list()
            for (CustomerDocumentFile customerDocumentFile : customerDocumentFileList) {
                customerDocumentFile.deleted = true
                customerDocumentFile.save(flush: true, failOnError: true)
            }
        }
    }

    public Boolean sendDocumentsToAnalysisIfPossible(Customer customer) {
        Map canSendDocuments = customerRegisterStatusService.canSendDocumentsToAnalysis(customer)
        if (!canSendDocuments.allowedToSendDocument) {
            AsaasLogger.error("CustomerDocumentGroupService.sendDocumentsToAnalysis >> Tentativa de envio de documentos em condição não permitida: [${canSendDocuments.reason}] customerId: [${customer.id}]")
            return false
        }

        Map hasCustomerDocumentNotSentOrRejected = checkIfhasCustomerDocumentNotSentOrRejected(customer)

        if (!hasCustomerDocumentNotSentOrRejected.success) return false

        if (customer.customerRegisterStatus.documentStatus == Status.AWAITING_APPROVAL) return true

        customerRegisterStatusService.updateDocumentStatus(customer, Status.AWAITING_APPROVAL)
        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, DocumentAnalysisStatus.MANUAL_ANALYSIS_REQUIRED)

        approveSandboxAccount(customer)

        return true
    }

    public Map checkIfhasCustomerDocumentNotSentOrRejected(Customer customer) {
        Boolean hasCustomerDocumentNotSent = false
        Boolean hasCustomerDocumentRejected = false
        Boolean success = true

        List<CustomerDocumentGroup> customerDocumentGroupList = buildListForCustomer(customer)

        for (CustomerDocumentGroup group in customerDocumentGroupList) {
            for (CustomerDocument customerDocument in group.customerDocuments) {
                if (customerDocument.status == CustomerDocumentStatus.NOT_SENT) {
                    hasCustomerDocumentNotSent = true
                    success = false
                    break
                }

                if (customerDocument.status == CustomerDocumentStatus.REJECTED) {
                    hasCustomerDocumentRejected = true
                    success = false
                    break
                }
            }
        }

        return [
            hasCustomerDocumentNotSent: hasCustomerDocumentNotSent,
            hasCustomerDocumentRejected: hasCustomerDocumentRejected,
            success: success
        ]
    }

    public CustomerDocumentGroup find(Map searchParams) {
        return CustomerDocumentGroup.query(searchParams).get()
    }

    public List<CustomerDocumentGroup> list(Map searchParams) {
        return CustomerDocumentGroup.query(searchParams).list()
    }

    public CustomerDocumentGroup saveCustomDocument(Customer customer, String name, String description) {
        if (!name || !description) {
            throw new BusinessException("Nome e descrição do documento precisam ser informados!")
        }

        CustomerDocumentGroup customerDocumentGroup = save(customer, CustomerDocumentGroupType.CUSTOM)

        CustomerDocument customerDocument = new CustomerDocument()
        customerDocument.type = CustomerDocumentType.CUSTOM
        customerDocument.publicId = UUID.randomUUID().toString()
        customerDocument.groupPublicId = customerDocumentGroup.publicId
        customerDocument.group = customerDocumentGroup
        customerDocument.save(flush: true)

        CustomCustomerDocument customCustomerDocument = new CustomCustomerDocument()
        customCustomerDocument.customerDocument = customerDocument
        customCustomerDocument.name = name
        customCustomerDocument.description = description
        customCustomerDocument.save(failOnError: true)

        List<CustomerDocument> customDocuments = CustomerDocument.query(["group.type": CustomerDocumentGroupType.CUSTOM, customer: customer]).list()
        customerDocumentGroup.customerDocumentsAsList = []
        customerDocumentGroup.customerDocumentsAsList.addAll(customDocuments)
        Collections.sort(customerDocumentGroup.customerDocumentsAsList)

        return customerDocumentGroup
    }

    public Boolean hasCustomerDocumentGroupSaved(Customer customer) {
        return CustomerDocumentGroup.query([exists: true, customer: customer]).get().asBoolean()
    }

    public void delete(Customer customer, List<CustomerDocumentGroupType> typeList) {
        List<CustomerDocumentGroup> customerDocumentGroupList = CustomerDocumentGroup.query([customer: customer, typeList: typeList]).list()
        if (!customerDocumentGroupList) return

        for (CustomerDocumentGroup customerDocumentGroup : customerDocumentGroupList) {
            customerDocumentGroup.deleted = true
            customerDocumentGroup.save(failOnError: true)
        }

        List<CustomerDocument> customerDocumentList = CustomerDocument.query(["group[in]": customerDocumentGroupList]).list()
        if (!customerDocumentList) return
        for (CustomerDocument customerDocument : customerDocumentList) {
            customerDocument.deleted = true
            customerDocument.save(failOnError: true)
        }

        List<CustomCustomerDocument> customCustomerDocumentList = CustomCustomerDocument.query(["customerDocument[in]": customerDocumentList]).list()
        if (!customCustomerDocumentList) return
        for (CustomCustomerDocument customCustomerDocument : customCustomerDocumentList) {
            customCustomerDocument.deleted = true
            customCustomerDocument.save(failOnError: true)
        }
    }

    public void addSelfieToIdentificationGroupIfNecessary(Customer customer) {
        CustomerDocumentGroupType customerDocumentGroupType = getIdentificationGroupType(customer)
        if (!customerDocumentGroupType) return

        CustomerDocumentGroup customerDocumentGroup = CustomerDocumentGroup.query([customer: customer, type: customerDocumentGroupType]).get()
        if (!customerDocumentGroup) return

        if (!isIdentificationSelfieRequired(customerDocumentGroup)) return

        Boolean hasIdentificationSelfie = CustomerDocument.query([
            exists: true,
            customer: customer,
            type: CustomerDocumentType.IDENTIFICATION_SELFIE,
            group: customerDocumentGroup
        ]).get().asBoolean()

        if (hasIdentificationSelfie) return

        CustomerDocument customerDocument = new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION_SELFIE, group: customerDocumentGroup)
        customerDocument.publicId = UUID.randomUUID().toString()
        customerDocument.groupPublicId = customerDocumentGroup.publicId
        customerDocument.save(failOnError: true)
    }

    private Boolean shouldRequestBankAccountOwnerDocument(Customer customer) {
        if (hasProofOfLifeSelfie(customer)) return false

        BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(customer.id)
        if (!bankAccountInfo) return false

        return !revenueServiceRegisterService.isSameOwnership(customer, bankAccountInfo.cpfCnpj)
    }

	private List<CustomerDocument> buildCustomerDocumentListByGroup(CustomerDocumentGroup customerDocumentGroup) {
		List<CustomerDocument> customerDocumentList = []

		switch (customerDocumentGroup.type) {
            case CustomerDocumentGroupType.ALLOW_BANK_ACCOUNT_DEPOSIT_STATEMENT:
                customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.ALLOW_BANK_ACCOUNT_DEPOSIT_STATEMENT, group: customerDocumentGroup))
                break
			case [CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER, CustomerDocumentGroupType.BANK_ACCOUNT_OWNER]:
				customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION, group: customerDocumentGroup))
                break
            case [CustomerDocumentGroupType.DIRECTOR, CustomerDocumentGroupType.PARTNER]:
                customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION, group: customerDocumentGroup))
                break
            case CustomerDocumentGroupType.INDIVIDUAL_COMPANY:
                customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION, group: customerDocumentGroup))
                if (entrepreneurRequirementIsRequired(customerDocumentGroup.customer)) {
                    customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.ENTREPRENEUR_REQUIREMENT, group: customerDocumentGroup))
                }
                break
            case CustomerDocumentGroupType.MEI:
                customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION, group: customerDocumentGroup))
                break
            case CustomerDocumentGroupType.CUSTOM:
                List<CustomerDocument> existingDocuments = CustomerDocument.query([customer: customerDocumentGroup.customer, "group.type": CustomerDocumentGroupType.CUSTOM]).list()
                customerDocumentList.addAll(existingDocuments)
				break
			case CustomerDocumentGroupType.LIMITED_COMPANY:
                customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.SOCIAL_CONTRACT, group: customerDocumentGroup))
				break
			case CustomerDocumentGroupType.ASSOCIATION:
                if (minutesOfConstitutionIsRequired(customerDocumentGroup.customer, customerDocumentGroup.type)) {
				    customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.MINUTES_OF_CONSTITUTION, group: customerDocumentGroup))
                }
				customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.MINUTES_OF_ELECTION, group: customerDocumentGroup))
				break
			case [CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER_EMANCIPATION_AGE, CustomerDocumentGroupType.BANK_ACCOUNT_OWNER_EMANCIPATION_AGE]:
				customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION, group: customerDocumentGroup))
				customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.EMANCIPATION_OF_MINORS, group: customerDocumentGroup))
				break
		}

        if (isIdentificationSelfieRequired(customerDocumentGroup)) {
            customerDocumentList.add(new CustomerDocument(type: CustomerDocumentType.IDENTIFICATION_SELFIE, group: customerDocumentGroup))
        }

		return customerDocumentList
	}

	private Boolean entrepreneurRequirementIsRequired(Customer customer) {
		Boolean hasSentEntrepreneurRequirementDocument = CustomerDocument.query([customer: customer, type: CustomerDocumentType.ENTREPRENEUR_REQUIREMENT, "group.type": CustomerDocumentGroupType.INDIVIDUAL_COMPANY, column: "id"]).get().asBoolean()
		if (hasSentEntrepreneurRequirementDocument) return true

		if (!CompanyPartnerQuery.hasAdminPartner(customer.getLastCpfCnpj())) return true

		return false
	}

	private Boolean socialContactIsRequired(Customer customer) {
		Boolean hasSentSocialContractDocument = CustomerDocument.query([customer: customer, type: CustomerDocumentType.SOCIAL_CONTRACT, "group.type": CustomerDocumentGroupType.LIMITED_COMPANY, column: "id"]).get().asBoolean()
		if (hasSentSocialContractDocument) return true

		if (!CompanyPartnerQuery.hasAdminPartner(customer.getLastCpfCnpj())) return true

		return false
	}

    private Boolean minutesOfConstitutionIsRequired(Customer customer, CustomerDocumentGroupType groupType) {
        return CustomerDocument.query([customer: customer, type: CustomerDocumentType.MINUTES_OF_CONSTITUTION, "group.type": groupType, column: "id"]).get().asBoolean()
    }

    private void approveSandboxAccount(Customer customer) {
        if (!AsaasEnvironment.isSandbox()) return
        List<CustomerDocument> approvalCustomerDocuments = CustomerDocument.query([customer: customer]).list()
        for (CustomerDocument  customerDocument in approvalCustomerDocuments) {
            customerDocument.status = CustomerDocumentStatus.APPROVED
            customerDocument.save(flush: true, failOnError: true)
        }
        customerRegisterStatusService.updateAllStatusAsApprovedToSandboxAccount(customer)
    }

    private PersonType getCustomerPersonType(Customer customer) {
        PersonType personType = customer.personType

        CustomerUpdateRequest latestCustomerUpdateRequestIfIsPendingOrDenied = CustomerUpdateRequest.findLatestIfIsPendingOrDenied(customer)
        if (latestCustomerUpdateRequestIfIsPendingOrDenied) {
            personType = latestCustomerUpdateRequestIfIsPendingOrDenied.personType
        }

        return personType
    }

    private CompanyType getCustomerCompanyType(Customer customer) {
        CompanyType companyType = customer.companyType

        CustomerUpdateRequest latestCustomerUpdateRequestIfIsPendingOrDenied = CustomerUpdateRequest.findLatestIfIsPendingOrDenied(customer)
        if (latestCustomerUpdateRequestIfIsPendingOrDenied) {
            companyType = latestCustomerUpdateRequestIfIsPendingOrDenied.companyType
        }

        return companyType
    }

    private CustomerDocumentGroup customerDocumentGroupForBankOwner(Customer customer) {
        BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(customer.id)

        CustomerDocumentGroupType customerDocumentGroupType
        if (bankAccountInfo.ownerBirthDate && Customer.isEmancipationAge(bankAccountInfo.ownerBirthDate)) {
            customerDocumentGroupType = CustomerDocumentGroupType.BANK_ACCOUNT_OWNER_EMANCIPATION_AGE
        } else {
            customerDocumentGroupType = CustomerDocumentGroupType.BANK_ACCOUNT_OWNER
        }

        return new CustomerDocumentGroup(type: customerDocumentGroupType, customer: customer)
    }

    private Boolean isIdentificationSelfieRequired(CustomerDocumentGroup customerDocumentGroup) {
        Customer customer = customerDocumentGroup.customer

        if (!customer.bankAccountInfoApprovalIsNotRequired() && BankAccountInfo.findMainAccount(customer).asBoolean()) {
            return false
        }

        if (hasProofOfLifeSelfie(customer)) {
            return CustomerDocumentGroupType.includeIdentificationSelfieForCustomerWithBankAccountNotRequiredList().contains(customerDocumentGroup.type)
        }

        return false
    }

    private CustomerDocumentGroup customerDocumentGroupForPersonTypeFisica(Customer customer) {
        CustomerDocumentGroupType customerDocumentGroupType = customerDocumentGroupTypeForPersonTypeFisica(customer)
        return new CustomerDocumentGroup(type: customerDocumentGroupType, customer: customer)
    }

    private CustomerDocumentGroupType customerDocumentGroupTypeForPersonTypeFisica(Customer customer) {
        if (customer.birthDate && Customer.isEmancipationAge(customer.birthDate)) {
            return CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER_EMANCIPATION_AGE
        }

        return CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER
    }

    private Boolean hasProofOfLifeSelfie(Customer customer) {
        return CustomerProofOfLife.query([exists: true, customer: customer, type: ProofOfLifeType.SELFIE]).get().asBoolean()
    }

    private void saveDocumentListIfNecessary(CustomerDocumentGroup customerDocumentGroup) {
        if (customerDocumentGroup.type.isCustom()) return

        List<CustomerDocument> builtCustomerDocumentList = buildCustomerDocumentListByGroup(customerDocumentGroup)

        for (CustomerDocument builtCustomerDocument : builtCustomerDocumentList) {
            CustomerDocument customerDocument = customerDocumentGroup.customerDocuments.find { !it.deleted && it.type == builtCustomerDocument.type }

            if (customerDocument) {
                continue
            }

            builtCustomerDocument.publicId = UUID.randomUUID().toString()
            builtCustomerDocument.groupPublicId = customerDocumentGroup.publicId
            builtCustomerDocument.save(flush: true, failOnError: true)

            if (!customerDocumentGroup.customerDocuments) {
                customerDocumentGroup.customerDocuments = []
            }

            customerDocumentGroup.customerDocuments.add(builtCustomerDocument)
        }
    }
}
