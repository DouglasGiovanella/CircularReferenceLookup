package com.asaas.service.customerdocument

import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.adapter.CustomerDocumentFileAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.file.AsaasFile
import com.asaas.file.adapter.FileAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallDocumentFileSearchParamsRequestAdapter
import grails.transaction.Transactional

@Transactional
class CustomerDocumentFileProxyService {

    def customerDocumentFileService
    def customerDocumentMigrationCacheService
    def fileService
    def heimdallAccountDocumentFileManagerService

    public CustomerDocumentFileAdapter find(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            HeimdallDocumentFileSearchParamsRequestAdapter adapter = new HeimdallDocumentFileSearchParamsRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentFileManagerService.find(adapter)
        }

        CustomerDocumentFile customerDocumentFile = customerDocumentFileService.find(searchParams)
        if (!customerDocumentFile) return null

        return new CustomerDocumentFileAdapter(customerDocumentFile, true)
    }

    public List<CustomerDocumentFileAdapter> list(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            HeimdallDocumentFileSearchParamsRequestAdapter adapter = new HeimdallDocumentFileSearchParamsRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentFileManagerService.list(adapter)
        }
        List<CustomerDocumentFile> customerDocumentFileList = customerDocumentFileService.list(searchParams)
        if (!customerDocumentFileList) return []

        return customerDocumentFileList.collect { new CustomerDocumentFileAdapter(it, true) }
    }

    public List<Object> listColumn(Long customerId, String column, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            HeimdallDocumentFileSearchParamsRequestAdapter adapter = new HeimdallDocumentFileSearchParamsRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentFileManagerService.listColumn(column, adapter)
        }
        return customerDocumentFileService.listColumn(column, searchParams)
    }

    public Integer count(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            HeimdallDocumentFileSearchParamsRequestAdapter adapter = new HeimdallDocumentFileSearchParamsRequestAdapter(customerId, searchParams)
            return heimdallAccountDocumentFileManagerService.count(adapter)
        }
        return customerDocumentFileService.count(searchParams)
    }

    public byte[] buildPreview(Long customerId, Long fileId, String path) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            File heimdallFile = fileService.getHeimdallFile(path)
            return heimdallFile?.readBytes()
        }
        return customerDocumentFileService.buildPreview(fileId)
    }

    public byte[] readBytes(Long customerId, Long fileId, String path) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            File heimdallFile = fileService.getHeimdallFile(path)
            return heimdallFile?.readBytes()
        }

        AsaasFile asaasFile = AsaasFile.read(fileId)
        return customerDocumentFileService.readBytes(asaasFile)
    }

    public List<CustomerDocumentFileAdapter> listNotApproved(Customer customer, Long customerDocumentId) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            Map searchParams = [customerDocumentId: customerDocumentId, "status[ne]": CustomerDocumentStatus.APPROVED]
            HeimdallDocumentFileSearchParamsRequestAdapter adapter = new HeimdallDocumentFileSearchParamsRequestAdapter(customer.id, searchParams)
            return heimdallAccountDocumentFileManagerService.list(adapter)
        }
        List<CustomerDocumentFile> customerDocumentFileList = customerDocumentFileService.listNotApproved(customer, customerDocumentId)

        return customerDocumentFileList.collect { new CustomerDocumentFileAdapter(it, false) }
    }

    public void remove(Customer customer, Long id) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            heimdallAccountDocumentFileManagerService.delete(customer.id, id)
            return
        }

        customerDocumentFileService.remove(customer, id)
    }

    public FileAdapter buildFile(Long customerId, Long fileId) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            CustomerDocumentFileAdapter customerDocumentFileAdapter = find(customerId, [heimdallFileId: fileId])
            if (!customerDocumentFileAdapter) return null

            return new FileAdapter(customerDocumentFileAdapter)
        }

        AsaasFile asaasFile = AsaasFile.read(fileId)
        FileAdapter fileAdapter = new FileAdapter(asaasFile, AsaasFile.DOCUMENTS_DIRECTORY)

        return fileAdapter
    }
}
