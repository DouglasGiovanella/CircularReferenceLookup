package com.asaas.service.businessgroup

import com.asaas.customer.CustomerSegment
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.businessgroup.BusinessGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessGroup
import com.asaas.environment.AsaasEnvironment
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class BusinessGroupService {

    def asyncActionService

    public BusinessGroup save(String name, CustomerSegment segment, Long accountManagerId) {
        AccountManager accountManager = AccountManager.query([id: accountManagerId]).get()

        BusinessGroup validatedDomain = validateSave(name, accountManager, segment)
        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível cadastrar o grupo empresarial", validatedDomain.errors)

        BusinessGroup businessGroup = new BusinessGroup()
        businessGroup.name = name
        businessGroup.segment = segment
        businessGroup.accountManager = accountManager

        return businessGroup.save(failOnError: true)
    }

    public BusinessGroup update(Long id, String name, CustomerSegment segment, Long accountManagerId) {
        AccountManager accountManager = AccountManager.query([id: accountManagerId]).get()

        BusinessGroup validatedDomain = validateUpdate(id, accountManager, segment, name)
        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível alterar o grupo empresarial", validatedDomain.errors)

        BusinessGroup businessGroup = BusinessGroup.get(id)

        CustomerSegment oldSegment = businessGroup.segment
        AccountManager oldAccountManager = businessGroup.accountManager

        businessGroup.name = name
        businessGroup.segment = segment
        businessGroup.accountManager = accountManager
        businessGroup.save(failOnError: true)

        Boolean hasLinkedCustomersToMigrate = CustomerBusinessGroup.query([exists: true, businessGroupId: businessGroup.id]).asBoolean()
        if (!hasLinkedCustomersToMigrate) return businessGroup

        if (AsaasEnvironment.isSandbox()) return businessGroup

        if (segment != oldSegment) {
            asyncActionService.saveBusinessGroupSegmentReplication([businessGroupId: businessGroup.id, segment: segment])
        } else if (accountManager != oldAccountManager) {
            asyncActionService.saveBusinessGroupAccountManagerReplication([businessGroupId: businessGroup.id, accountManagerId: accountManager.id])
        }

        return businessGroup
    }

    public void linkCustomer(Long customerId, Long businessGroupId) {
        Customer customer = Customer.get(customerId)
        BusinessGroup businessGroup = BusinessGroup.query([id: businessGroupId]).get()
        CustomerBusinessGroup validatedDomain = validateLinkCustomer(customer, businessGroup)

        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerBusinessGroup customerBusinessGroup = CustomerBusinessGroup.query([customerId: customerId, includeDeleted: true]).get()

        if (!customerBusinessGroup) {
            customerBusinessGroup = new CustomerBusinessGroup()
            customerBusinessGroup.customer = customer
        }

        customerBusinessGroup.businessGroup = businessGroup
        customerBusinessGroup.deleted = false

        customerBusinessGroup.save(failOnError: true)
    }

    public void unlinkCustomer(Long customerId) {
        CustomerBusinessGroup validatedDomain = validateUnlinkCustomer(customerId)

        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível desvincular o cliente do grupo", validatedDomain.getErrors())

        CustomerBusinessGroup customerBusinessGroup = CustomerBusinessGroup.query([customerId: customerId]).get()
        customerBusinessGroup.deleted = true
        customerBusinessGroup.save(failOnError: true)
    }

    public BusinessValidation canUnlinkCustomerBusinessGroup(Long customerId) {
        BusinessValidation validation = new BusinessValidation()

        CustomerBusinessGroup validatedDomain = validateUnlinkCustomer(customerId)
        if (validatedDomain.hasErrors()) {
            validation.addError("businessGroup.validation.notLinked")
        }

        return validation
    }

    private BusinessGroup validateSave(String name, AccountManager accountManager, CustomerSegment segment) {
        BusinessGroup validatedDomain = new BusinessGroup()

        if (!name) {
            DomainUtils.addError(validatedDomain, "É necessário informar um nome para o grupo")
            return validatedDomain
        }

        BusinessGroup validatedSegmentAndManager = validateSegmentAndManager(accountManager, segment)
        validatedDomain = DomainUtils.copyAllErrorsFromObject(validatedSegmentAndManager, validatedDomain)

        if (BusinessGroup.query([exists: true, name: name]).get().asBoolean()) {
            DomainUtils.addError(validatedDomain, "Já existe um grupo com este nome")
        }

        return validatedDomain
    }

    private BusinessGroup validateUpdate(Long id, AccountManager accountManager, CustomerSegment segment, String name) {
        BusinessGroup validatedDomain = new BusinessGroup()

        Boolean groupExists = BusinessGroup.get(id).asBoolean()
        if (!groupExists) {
            DomainUtils.addError(validatedDomain, "O grupo empresarial informado não foi encontrado")
            return validatedDomain
        }

        if (!name) {
            DomainUtils.addError(validatedDomain, "É necessário informar um nome para o grupo")
            return validatedDomain
        }

        BusinessGroup validatedSegmentAndManager = validateSegmentAndManager(accountManager, segment)
        validatedDomain = DomainUtils.copyAllErrorsFromObject(validatedSegmentAndManager, validatedDomain)

        if (BusinessGroup.query([exists: true, name: name, "id[notIn]": id]).get().asBoolean()) {
            DomainUtils.addError(validatedDomain, "Já existe um grupo com este nome")
        }

        return validatedDomain
    }

    private BusinessGroup validateSegmentAndManager(AccountManager accountManager, CustomerSegment segment) {
        BusinessGroup validatedDomain = new BusinessGroup()

        if (!segment || !accountManager) {
            DomainUtils.addError(validatedDomain, "Verifique o preenchimento de todos os campos")
            return validatedDomain
        }

        if (accountManager.customerSegment != segment) {
            DomainUtils.addError(validatedDomain, "Este gerente não atende o segmento informado")
        }

        return validatedDomain
    }

    private CustomerBusinessGroup validateLinkCustomer(Customer customer, BusinessGroup businessGroup) {
        CustomerBusinessGroup validatedDomain = new CustomerBusinessGroup()

        if (!customer)  {
            DomainUtils.addError(validatedDomain, "O cliente informado não foi encontrado")
            return validatedDomain
        }

        if (!businessGroup) {
            DomainUtils.addError(validatedDomain, "O grupo empresarial informado não foi encontrado")
            return validatedDomain
        }

        if (customer.accountManagerId != businessGroup.accountManagerId) {
            DomainUtils.addError(validatedDomain, "O grupo empresarial e o cliente possuem gerentes de contas diferentes")
        }

        if (customer.segment != businessGroup.segment) {
            DomainUtils.addError(validatedDomain, "O grupo empresarial e o cliente são de segmentos diferentes")
        }

        return validatedDomain
    }

    private CustomerBusinessGroup validateUnlinkCustomer(Long customerId) {
        CustomerBusinessGroup validatedDomain = new CustomerBusinessGroup()
        Boolean customer = Customer.query([id: customerId, exists: true]).get().asBoolean()
        if (!customer) {
            DomainUtils.addError(validatedDomain, "O cliente informado não foi encontrado")
            return validatedDomain
        }

        Boolean customerBusinessGroup = CustomerBusinessGroup.query([customerId: customerId, exists: true]).get().asBoolean()
        if (!customerBusinessGroup) {
            DomainUtils.addError(validatedDomain, "O cliente informado não está vinculado a nenhum grupo empresarial")
        }

        return validatedDomain
    }
}
