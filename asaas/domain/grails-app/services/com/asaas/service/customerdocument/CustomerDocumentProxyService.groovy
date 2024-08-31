package com.asaas.service.customerdocument

import com.asaas.customerdocument.CustomerDocumentSaveThroughUserInterfaceAdapter
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocument.adapter.AccountDocumentValidateSaveAdapter
import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.file.AsaasFile
import com.asaas.integration.heimdall.adapter.document.AccountDocumentSearchRequestAdapter
import com.asaas.integration.heimdall.adapter.document.AccountDocumentValidateSaveRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallDocumentFileSearchParamsRequestAdapter
import com.asaas.integration.heimdall.adapter.document.SaveAccountDocumentRequestAdapter
import com.asaas.integration.heimdall.adapter.document.UpdateAccountDocumentsWhenCpfCnpjChangedRequestAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class CustomerDocumentProxyService {

    def customerDocumentMigrationCacheService
    def customerDocumentService
    def heimdallAccountDocumentFileManagerService
    def heimdallAccountDocumentManagerService

    public AccountDocumentValidateSaveAdapter canSendCustomerDocumentThroughUserInterface(Customer customer, CustomerDocumentType customerDocumentType, Long customerDocumentId) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            AccountDocumentValidateSaveRequestAdapter requestAdapter = new AccountDocumentValidateSaveRequestAdapter(customer, customerDocumentType, customerDocumentId, true)
            return heimdallAccountDocumentManagerService.validateSave(requestAdapter)
        }

        return customerDocumentService.canSendCustomerDocumentThroughUserInterface(customer, customerDocumentType, customerDocumentId)
    }

    public CustomerDocumentAdapter save(Customer customer, Long documentId, CustomerDocumentGroupType groupType, CustomerDocumentType documentType, List<String> temporaryFileIdList) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            SaveAccountDocumentRequestAdapter requestAdapter = new SaveAccountDocumentRequestAdapter(customer, documentType, temporaryFileIdList)
            return heimdallAccountDocumentManagerService.save(requestAdapter)
        }

        CustomerDocument customerDocument = customerDocumentService.save(customer, documentId, groupType, documentType, temporaryFileIdList)
        return new CustomerDocumentAdapter(customerDocument, true)
    }

    public CustomerDocumentAdapter saveThroughUserInterface(CustomerDocumentSaveThroughUserInterfaceAdapter saveThroughUserInterfaceAdapter) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(saveThroughUserInterfaceAdapter.customer.id)) {
            SaveAccountDocumentRequestAdapter requestAdapter = new SaveAccountDocumentRequestAdapter(saveThroughUserInterfaceAdapter)
            return heimdallAccountDocumentManagerService.save(requestAdapter)
        }

        CustomerDocument customerDocument = customerDocumentService.saveThroughUserInterface(saveThroughUserInterfaceAdapter)

        return new CustomerDocumentAdapter(customerDocument, true)
    }

    public void updateDocumentsWhenCpfCnpjChanged(Customer customer, CustomerUpdateRequest customerUpdateRequest) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            UpdateAccountDocumentsWhenCpfCnpjChangedRequestAdapter requestAdapter = new UpdateAccountDocumentsWhenCpfCnpjChangedRequestAdapter(customer, customerUpdateRequest)
            heimdallAccountDocumentManagerService.updateWhenCpfCnpjChanged(requestAdapter)
            return
        }

        customerDocumentService.updateDocumentsWhenCpfCnpjChanged(customer, customerUpdateRequest)
    }

    public void deletePendingDocuments(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            heimdallAccountDocumentManagerService.deletePendingDocuments(customer.id)
            return
        }

        customerDocumentService.deletePendingDocuments(customer)
    }

    public CustomerDocumentAdapter savePowerOfAttorneyDocument(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            SaveAccountDocumentRequestAdapter requestAdapter = new SaveAccountDocumentRequestAdapter(customer, CustomerDocumentType.POWER_OF_ATTORNEY, null)
            return heimdallAccountDocumentManagerService.save(requestAdapter)
        }

        CustomerDocument customerDocument = customerDocumentService.savePowerOfAttorneyDocument(customer)
        return new CustomerDocumentAdapter(customerDocument, false)
    }

    public List<CustomerDocumentAdapter> list(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            AccountDocumentSearchRequestAdapter requestAdapter = new AccountDocumentSearchRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentManagerService.list(requestAdapter)
        }

        List<CustomerDocument> customerDocumentList = customerDocumentService.list(customerId, searchParams)
        return customerDocumentList.collect { new CustomerDocumentAdapter(it, true) }
    }

    public Boolean exists(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            AccountDocumentSearchRequestAdapter requestAdapter = new AccountDocumentSearchRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentManagerService.exists(requestAdapter)
        }

        return customerDocumentService.exists(customerId, searchParams)
    }

    public CustomerDocumentAdapter find(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            AccountDocumentSearchRequestAdapter requestAdapter = new AccountDocumentSearchRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentManagerService.find(requestAdapter)
        }

        CustomerDocument customerDocument = customerDocumentService.find(customerId, searchParams)
        if (!customerDocument) return null

        return new CustomerDocumentAdapter(customerDocument, true)
    }

    public Integer retrieveNumberOfDocuments(Long customerId) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            HeimdallDocumentFileSearchParamsRequestAdapter adapter = new HeimdallDocumentFileSearchParamsRequestAdapter(customerId, null)
            return heimdallAccountDocumentFileManagerService.count(adapter)
        }
        return customerDocumentService.retrieveNumberOfDocuments(customerId)
    }

    public void updateStatusIfNoFilesFound(Long customerId, Long customerDocumentId) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) return

        customerDocumentService.updateStatusIfNoFilesFound(customerDocumentId)
    }

    public Boolean hasCustomerIdentificationDocumentNotSentOrRejected(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            return heimdallAccountDocumentManagerService.hasIdentificationDocumentNotSentOrRejected(customer.id)
        }

        return customerDocumentService.hasCustomerIdentificationDocumentNotSentOrRejected(customer)
    }

    public void updateExpirationDate(Long customerId, Long customerDocumentId, String expirationDate) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            Date newExpirationDate = CustomDateUtils.toDate(expirationDate)
            heimdallAccountDocumentManagerService.updateExpirationDate(customerId, customerDocumentId, newExpirationDate)
            return
        }

        customerDocumentService.updateExpirationDate(customerDocumentId, expirationDate)
    }

    public void copyFromAsaasFile(Customer customer, Long customerDocumentGroupId, AsaasFile file, String originDirectory) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            AsaasLogger.error("CustomerDocumentProxyService.copyFromAsaasFile >> Método não é aplicável a customer com documentos migrados. Customer: [${customer.id}]")
            return
        }

        customerDocumentService.copyFromAsaasFile(customerDocumentGroupId, file, originDirectory)
    }

    public CustomerDocumentAdapter update(Customer customer, CustomerDocumentAdapter customerDocumentAdapter, List<String> temporaryFileIdList) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            SaveAccountDocumentRequestAdapter requestAdapter = new SaveAccountDocumentRequestAdapter(customer, customerDocumentAdapter, temporaryFileIdList, true)
            return heimdallAccountDocumentManagerService.save(requestAdapter)
        }

        CustomerDocument originalCustomerDocument = CustomerDocument.get(customerDocumentAdapter.id)
        CustomerDocument customerDocument = customerDocumentService.update(customer, originalCustomerDocument, temporaryFileIdList)

        return new CustomerDocumentAdapter(customerDocument, true)
    }
}
