package com.asaas.service.customerdocumentgroup

import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.customerdocumentgroup.adapter.CustomerDocumentGroupAdapter
import com.asaas.customerdocumentgroup.adapter.CustomerDocumentGroupHistoryAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.adapter.document.HeimdallBuildDefaultDocumentGroupRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallBuildIdentificationDocumentGroupRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallDocumentGroupSearchParamsRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallDeleteDocumentGroupAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallSaveCustomDocumentParamsAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallSaveDocumentListParamsAdapter
import com.asaas.integration.heimdall.dto.document.HeimdallCheckHasDocumentsNotSentOrRejectedResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDocumentGroupProxyService {

    def customerDocumentGroupService
    def customerDocumentMigrationCacheService
    def heimdallAccountDocumentGroupManagerService
    def heimdallAccountDocumentManagerService

    public List<CustomerDocumentGroupAdapter> buildListForCustomer(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallBuildDefaultDocumentGroupRequestAdapter requestAdapter = new HeimdallBuildDefaultDocumentGroupRequestAdapter(customer)
            return heimdallAccountDocumentGroupManagerService.buildDefaultDocumentGroup(requestAdapter)
        }

        List<CustomerDocumentGroup> customerDocumentGroupList = customerDocumentGroupService.buildListForCustomer(customer)
        List<CustomerDocumentGroupAdapter> customerDocumentGroupAdapterList = customerDocumentGroupList.collect({ new CustomerDocumentGroupAdapter(it, true) })
        validateSliceOfBuildListForCustomerInHeimdall(customerDocumentGroupAdapterList, customer)
        return customerDocumentGroupAdapterList
    }

    public CustomerDocumentGroupAdapter save(Customer customer, CustomerDocumentGroupType type) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallSaveDocumentListParamsAdapter adapter = new HeimdallSaveDocumentListParamsAdapter(customer, type)
            return heimdallAccountDocumentGroupManagerService.saveList(adapter)
        }

        CustomerDocumentGroup customerDocumentGroup = customerDocumentGroupService.save(customer, type)
        return new CustomerDocumentGroupAdapter(customerDocumentGroup, true)
    }

    public Boolean hasCustomerDocumentGroupSaved(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            return heimdallAccountDocumentManagerService.hasDocument(customer.id)
        }

        return customerDocumentGroupService.hasCustomerDocumentGroupSaved(customer)
    }

    public CustomerDocumentGroupType getIdentificationGroupType(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallBuildIdentificationDocumentGroupRequestAdapter adapter = new HeimdallBuildIdentificationDocumentGroupRequestAdapter(customer)

            return heimdallAccountDocumentGroupManagerService.buildIdentificationGroupType(adapter)
        }

        return customerDocumentGroupService.getIdentificationGroupType(customer)
    }

    public Map checkIfhasCustomerDocumentNotSentOrRejected(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallCheckHasDocumentsNotSentOrRejectedResponseDTO responseDTO = heimdallAccountDocumentManagerService.checkIfHasCustomerDocumentNotSentOrRejected(customer)
            return responseDTO.toMap()
        }
        return customerDocumentGroupService.checkIfhasCustomerDocumentNotSentOrRejected(customer)
    }

    public CustomerDocumentGroupAdapter findCustom(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            return heimdallAccountDocumentGroupManagerService.findCustom(customer)
        }

        Map searchParams = [type: CustomerDocumentGroupType.CUSTOM, customer: customer]
        CustomerDocumentGroup customerDocumentGroup = customerDocumentGroupService.find(searchParams)
        if (!customerDocumentGroup) return null

        return new CustomerDocumentGroupAdapter(customerDocumentGroup, true)
    }

    public List<CustomerDocumentGroupAdapter> list(Customer customer, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallDocumentGroupSearchParamsRequestAdapter requestAdapter = new HeimdallDocumentGroupSearchParamsRequestAdapter(customer, searchParams)
            return heimdallAccountDocumentGroupManagerService.buildList(requestAdapter)
        }

        List<CustomerDocumentGroup> customerDocumentGroupList = customerDocumentGroupService.list(searchParams)
        return customerDocumentGroupList.collect { new CustomerDocumentGroupAdapter(it, true) }
    }

    public List<CustomerDocumentGroupHistoryAdapter> listHistory(Customer customer) {
        final Map searchParams = [includeDeleted: true, customer: customer]

        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallDocumentGroupSearchParamsRequestAdapter requestAdapter = new HeimdallDocumentGroupSearchParamsRequestAdapter(customer, searchParams)
            return heimdallAccountDocumentGroupManagerService.buildHistoryList(requestAdapter)
        }

        List<CustomerDocumentGroup> customerDocumentGroupList = customerDocumentGroupService.list(searchParams)
        return customerDocumentGroupList.collect { new CustomerDocumentGroupHistoryAdapter(it) }
    }

    public void deleteCustom(Customer customer) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            heimdallAccountDocumentManagerService.deleteCustomDocument(customer.id)
            return
        }

        customerDocumentGroupService.deleteCustom(customer)
    }

    public CustomerDocumentGroupAdapter saveCustomDocument(Customer customer, String name, String description) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallSaveCustomDocumentParamsAdapter paramsAdapter = new HeimdallSaveCustomDocumentParamsAdapter()
            paramsAdapter.customerId = customer.id
            paramsAdapter.name = name
            paramsAdapter.description = description

            heimdallAccountDocumentManagerService.saveCustomDocument(paramsAdapter)

            return findCustom(customer)
        }

        CustomerDocumentGroup customerDocumentGroup = customerDocumentGroupService.saveCustomDocument(customer, name, description)
        return new CustomerDocumentGroupAdapter(customerDocumentGroup, true)
    }

    public void delete(Customer customer, List<CustomerDocumentGroupType> typeList) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customer.id)) {
            HeimdallDeleteDocumentGroupAdapter deleteDocumentGroupAdapter = new HeimdallDeleteDocumentGroupAdapter(customer, typeList)
            heimdallAccountDocumentGroupManagerService.delete(deleteDocumentGroupAdapter)
        }

        customerDocumentGroupService.delete(customer, typeList)
    }

    public void addSelfieToIdentificationGroupIfNecessary(Customer customer) {
        customerDocumentGroupService.addSelfieToIdentificationGroupIfNecessary(customer)
    }

    private void validateSliceOfBuildListForCustomerInHeimdall(List<CustomerDocumentGroupAdapter> customerDocumentGroupAdapterList, Customer customer) {
        if (!AsaasEnvironment.isProduction()) return

        final Integer percentOfRequestsInHeimdall = 15
        if (!Utils.isPropertyInPercentageRange(customer.id, percentOfRequestsInHeimdall)) return

        Utils.withSafeException({
            List<CustomerDocumentGroupAdapter> heimdallAdapterList = heimdallAccountDocumentGroupManagerService.buildDefaultDocumentGroup(new HeimdallBuildDefaultDocumentGroupRequestAdapter(customer))
            validateHeimdallGroupsIsSameOfAsaasGroups(heimdallAdapterList, customerDocumentGroupAdapterList, customer.id)
        })
    }

    private void validateHeimdallGroupsIsSameOfAsaasGroups(List<CustomerDocumentGroupAdapter> heimdallAdapterList, List<CustomerDocumentGroupAdapter> customerDocumentGroupAdapterList, Long customerId) {
        final List<CustomerDocumentGroupType> groupTypeListToValidate = [
            CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER,
            CustomerDocumentGroupType.ASSOCIATION,
            CustomerDocumentGroupType.PARTNER,
            CustomerDocumentGroupType.DIRECTOR,
            CustomerDocumentGroupType.INDIVIDUAL_COMPANY,
            CustomerDocumentGroupType.MEI,
            CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER_EMANCIPATION_AGE,
        ]

        List<CustomerDocumentGroupAdapter> customerDocumentGroupAdapterIdentificationList = customerDocumentGroupAdapterList.findAll({ groupTypeListToValidate.contains(it.type) })

        if (heimdallAdapterList.size() != customerDocumentGroupAdapterIdentificationList.size()) {
            String asaasTypes = customerDocumentGroupAdapterIdentificationList.collect { it.type }.join(",")
            String heimdallTypes = heimdallAdapterList.collect { it.type }.join(",")

            AsaasLogger.info("CustomerDocumentGroupProxyService.buildListForCustomer >> Heimdall retornou estrutura de documentos diferente do asaas para o cliente ID = ${customerId} [asaas.types = ${asaasTypes}] [heimdall.types = ${heimdallTypes}]")
            return
        }

        for (CustomerDocumentGroupAdapter heimdallGroup : heimdallAdapterList) {
            CustomerDocumentGroupAdapter documentGroup = customerDocumentGroupAdapterIdentificationList.find({ it.type == heimdallGroup.type })

            if (!documentGroup) {
                AsaasLogger.info("CustomerDocumentGroupProxyService.buildListForCustomer >> Heimdall retornou estrutura de documentos diferente do asaas para o cliente ID = ${customerId} [type = ${heimdallGroup.type.toString()}]")
                return
            }

            if (documentGroup.customerDocumentList.size() != heimdallGroup.customerDocumentList.size()) {
                AsaasLogger.info("CustomerDocumentGroupProxyService.buildListForCustomer >> Heimdall retornou estrutura de documentos diferente do asaas para o cliente ID = ${customerId}. [asaas.size() = ${documentGroup.customerDocumentList.size()}] [heimdall.size() = ${heimdallGroup.customerDocumentList.size()}]")
                return
            }

            AsaasLogger.info("CustomerDocumentGroupProxyService.buildListForCustomer >> Sucesso! Heimdall retornou estrutura de documentos igual ao asaas para o cliente ID = ${customerId}.")
        }
    }

}
