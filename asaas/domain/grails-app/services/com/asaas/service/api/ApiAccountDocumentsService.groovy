package com.asaas.service.api

import com.asaas.api.ApiAccountDocumentsParser
import com.asaas.api.ApiMobileUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.customerdocument.adapter.CustomerDocumentFileAdapter
import com.asaas.customerdocumentgroup.adapter.CustomerDocumentGroupAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.file.TemporaryFile
import com.asaas.redis.RedissonProxy
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingRequestAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

import java.util.concurrent.TimeUnit

@Transactional
class ApiAccountDocumentsService extends ApiBaseService {

    def apiResponseBuilderService
    def customerDocumentFileProxyService
    def customerDocumentGroupProxyService
    def customerDocumentProxyService
    def customerRegisterStatusService
    def onboardingService
    def temporaryFileService
    def thirdPartyDocumentationOnboardingService

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)

        if (!customer.getLastCpfCnpj()) {
            return apiResponseBuilderService.buildErrorFrom("commercial_info_not_informed", "Para obter os documentos da sua conta é necessário preencher os seus dados comerciais.")
        }

        List<CustomerDocumentGroupAdapter> customerDocumentGroupAdapterList = customerDocumentGroupProxyService.buildListForCustomer(customer)

        Map responseMap = [:]
        List<Map> customerDocuments = []
        String thirdPartyDocumentationOnboardingUrl = getThirdPartyDocumentationOnboardingUrlIfPossible(customer)

        for (CustomerDocumentGroupAdapter customerDocumentGroupAdapter : customerDocumentGroupAdapterList) {
            customerDocumentGroupAdapter = saveDocumentGroup(customerDocumentGroupAdapter)

            customerDocuments.addAll(customerDocumentGroupAdapter.customerDocumentList.collect { ApiAccountDocumentsParser.buildDocumentResponseItem(it, customerDocumentGroupAdapter, thirdPartyDocumentationOnboardingUrl) })
        }

        DocumentAnalysis lastApprovedOrRejectedAnalysis = DocumentAnalysis.approvedOrRejected(customer).get()

        String rejectedDocumentAnalysisObservations
        if (lastApprovedOrRejectedAnalysis?.status?.isRejected()) {
            rejectedDocumentAnalysisObservations = lastApprovedOrRejectedAnalysis.observations ?: null
        }

        if (ApiMobileUtils.isMobileAppRequest()) {
            Map editPermissionMap = customerRegisterStatusService.getDocumentationEditPermission(customer)

            responseMap.shouldFillBankAccountInfo = editPermissionMap.shouldFillBankAccountInfo
            responseMap.shouldFillCommercialInfo = editPermissionMap.shouldFillCommercialInfo

            responseMap.rejectedDocumentAnalysisObservations = rejectedDocumentAnalysisObservations
        } else {
            responseMap.rejectReasons = rejectedDocumentAnalysisObservations
        }

        responseMap.data = customerDocuments

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)
        Map fields = ApiAccountDocumentsParser.parseRequestParams(params)

        CustomerDocumentAdapter customerDocumentAdapter = findByPublicId(customer, fields.publicId, fields.documentType)

        if (!customerDocumentAdapter) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        CustomerDocumentType documentType = customerDocumentAdapter.type
        CustomerDocumentFile customerDocumentFileValidation = validateSave(customer, documentType, fields)
        if (customerDocumentFileValidation.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerDocumentFileValidation)
        }

        if (fields.containsKey("documentFile")) {
            TemporaryFile temporaryFile = temporaryFileService.save(customer, fields.documentFile, true)
            fields.temporaryFileIdList = [temporaryFile.id]
        }

        Map responseMap = [:]

        if (ApiMobileUtils.isMobileAppRequest()) {
            responseMap.isFirstDocumentSent = customerDocumentProxyService.exists(customer.id, [:])
        }

        CustomerDocumentAdapter updatedCustomerDocumentAdapter = customerDocumentProxyService.save(customer, customerDocumentAdapter.id, customerDocumentAdapter.group?.type, documentType, fields.temporaryFileIdList)

        CustomerDocumentFileAdapter customerDocumentFileAdapter = (updatedCustomerDocumentAdapter.customerDocumentFileList.sort { it.lastUpdated }).last()

        responseMap << ApiAccountDocumentsParser.buildDocumentFileResponseItem(customerDocumentFileAdapter)

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map showDocumentFile(Map params) {
        Customer customer = getProviderInstance(params)
        Map searchParams = [publicId: params.id, customer: customer, "status[ne]": CustomerDocumentStatus.APPROVED]
        CustomerDocumentFileAdapter customerDocumentFileAdapter = customerDocumentFileProxyService.find(customer.id, searchParams)

        if (!customerDocumentFileAdapter.asBoolean()) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        return apiResponseBuilderService.buildSuccess(ApiAccountDocumentsParser.buildDocumentFileResponseItem(customerDocumentFileAdapter))
    }

    public Map updateDocumentFile(Map params) {
        Map fields = ApiAccountDocumentsParser.parseRequestParams(params)
        Customer customer = getProviderInstance(params)

        Map searchParams = [publicId: params.id, customer: customer, "status[ne]": CustomerDocumentStatus.APPROVED]
        CustomerDocumentFileAdapter customerDocumentFileAdapter = customerDocumentFileProxyService.find(customer.id, searchParams)

        if (!customerDocumentFileAdapter.asBoolean()) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        CustomerDocumentFile customerDocumentFileValidation = validateUpdate(customer, customerDocumentFileAdapter.customerDocument.type, fields)
        if (customerDocumentFileValidation.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerDocumentFileValidation)
        }

        TemporaryFile temporaryFile = temporaryFileService.save(customer, fields.documentFile, true)
        CustomerDocumentAdapter customerDocumentAdapter = customerDocumentProxyService.update(customer, customerDocumentFileAdapter.customerDocument, ["${customerDocumentFileAdapter.id}|${temporaryFile.id}"])

        customerDocumentFileAdapter = customerDocumentAdapter.customerDocumentFileList.find { it.publicId == customerDocumentFileAdapter.publicId }

        return apiResponseBuilderService.buildSuccess(ApiAccountDocumentsParser.buildDocumentFileResponseItem(customerDocumentFileAdapter))
    }

    public Map deleteDocumentFile(Map params) {
        Customer customer = getProviderInstance(params)
        Map searchParams = [publicId: params.id, customer: customer, "status[ne]": CustomerDocumentStatus.APPROVED]
        CustomerDocumentFileAdapter customerDocumentFileAdapter = customerDocumentFileProxyService.find(customer.id, searchParams)
        if (!customerDocumentFileAdapter.asBoolean()) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        customerDocumentFileProxyService.remove(customer, customerDocumentFileAdapter.id)
        customerDocumentProxyService.updateStatusIfNoFilesFound(customer.id, customerDocumentFileAdapter.customerDocument.id)

        return apiResponseBuilderService.buildDeleted(customerDocumentFileAdapter.publicId)
    }

    public Map retrieveNumberOfDocumentsSentByCustomer(Map params) {
        Integer documentsSent = customerDocumentProxyService.retrieveNumberOfDocuments(getProvider(params))

        return apiResponseBuilderService.buildSuccess([documentsSent: documentsSent])
    }

    private CustomerDocumentFile validateSave(Customer customer, CustomerDocumentType customerDocumentType, Map params) {
        return validateSaveOrUpdate(customer, customerDocumentType, params.documentFile, params.temporaryFileIdList)
    }

    private CustomerDocumentFile validateUpdate(Customer customer, CustomerDocumentType customerDocumentType, Map params) {
        return validateSaveOrUpdate(customer, customerDocumentType, params.documentFile, params.temporaryFileIdList)
    }

    private CustomerDocumentFile validateSaveOrUpdate(Customer customer, CustomerDocumentType documentType, def documentFile, List<Long> temporaryFileIdList) {
        CustomerDocumentFile customerDocumentFile = new CustomerDocumentFile()

        if (!ApiMobileUtils.isMobileAppRequest() &&
                documentType.isIdentificationTypes() &&
                customer.accountOwner &&
                CustomerParameter.getValue(customer.accountOwner, CustomerParameterName.ENABLE_THIRD_PARTY_ONBOARDING_FOR_CHILD_ACCOUNTS)) {
            DomainUtils.addError(customerDocumentFile, "Este tipo de documento não pode ser enviado via API. Por favor utilize o link de onboarding.")

            return customerDocumentFile
        }

        if (customer.accountDisabled()) {
            DomainUtils.addError(customerDocumentFile, "Não foi possível enviar os documentos. Verifique o status da sua conta.")

            return customerDocumentFile
        }

        if (!(documentFile instanceof CommonsMultipartFile) && !temporaryFileIdList) {
            DomainUtils.addError(customerDocumentFile, "Nenhum arquivo recebido.")
        }

        if (documentType.isIdentificationTypes() && !CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) {
            DomainUtils.addError(customerDocumentFile, "Esse tipo de documento não pode ser enviado via API. Por favor, entre em contato com o suporte.")
        }

        return customerDocumentFile
    }

    private String getThirdPartyDocumentationOnboardingUrlIfPossible(Customer customer) {
        if (!canRequestThirdPartyDocumentationOnboardingUrl(customer)) return null

        ThirdPartyDocumentationOnboardingRequestAdapter requestAdapter = new ThirdPartyDocumentationOnboardingRequestAdapter(customer, ApiMobileUtils.isMobileAppRequest(), false)
        return thirdPartyDocumentationOnboardingService.requestThirdPartyDocumentationOnboardingShortenedUrl(requestAdapter)
    }

    private Boolean canRequestThirdPartyDocumentationOnboardingUrl(Customer customer) {
        if (!ApiMobileUtils.isMobileAppRequest()) {
            if (!customer.accountOwner) return false
            if (!CustomerParameter.getValue(customer.accountOwner, CustomerParameterName.ENABLE_THIRD_PARTY_ONBOARDING_FOR_CHILD_ACCOUNTS)) return false
        }

        return onboardingService.shouldShowInternalThirdPartyOnboarding(customer)
    }

    private CustomerDocumentGroupAdapter saveDocumentGroup(CustomerDocumentGroupAdapter customerDocumentGroupAdapter) {
        final Long leaseTimeInSeconds = 2
        final Long waitTimeInSeconds = 5
        final String key = "lock:CustomerDocumentGroupSave:${customerDocumentGroupAdapter.customer.id}:${customerDocumentGroupAdapter.type}"

        RedissonProxy.instance.lock(key, waitTimeInSeconds, leaseTimeInSeconds, TimeUnit.SECONDS)

        Utils.withNewTransactionAndRollbackOnError({
            customerDocumentGroupAdapter = customerDocumentGroupProxyService.save(customerDocumentGroupAdapter.customer, customerDocumentGroupAdapter.type)
        })

        RedissonProxy.instance.unlock(key)

        return customerDocumentGroupAdapter
    }

    private CustomerDocumentAdapter findByPublicId(Customer customer, String publicId, CustomerDocumentType type) {
        CustomerDocumentAdapter foundCustomerDocumentAdapter = customerDocumentProxyService.find(customer.id, [publicId: publicId])
        if (foundCustomerDocumentAdapter) return foundCustomerDocumentAdapter

        if (!type) return null

        if (type.isCustom()) {
            Long documentId = Utils.toLong(publicId.split("-").last())
            return customerDocumentProxyService.find(customer.id, [id: documentId, type: type])
        }

        return customerDocumentProxyService.find(customer.id, [groupPublicId: publicId, type: type])
    }
}
