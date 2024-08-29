package com.asaas.service.integration.heimdall

import com.asaas.customerdocument.adapter.AccountDocumentValidateSaveAdapter
import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.adapter.document.AccountDocumentSearchRequestAdapter
import com.asaas.integration.heimdall.adapter.document.AccountDocumentValidateSaveRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallBuildDefaultDocumentGroupRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallSaveCustomDocumentParamsAdapter
import com.asaas.integration.heimdall.adapter.document.SaveAccountDocumentRequestAdapter
import com.asaas.integration.heimdall.adapter.document.UpdateAccountDocumentsWhenCpfCnpjChangedRequestAdapter
import com.asaas.integration.heimdall.dto.document.HeimdallAccountDocumentSearchRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallCheckHasDocumentsNotSentOrRejectedRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallCheckHasDocumentsNotSentOrRejectedResponseDTO
import com.asaas.integration.heimdall.dto.document.HeimdallDocumentResponseDTO
import com.asaas.integration.heimdall.dto.document.HeimdallSaveAccountDocumentRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallSaveCustomDocumentRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallUpdateAccountDocumentExpirationDateRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallUpdateAccountDocumentsWhenCpfCnpjChangedRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallValidateSaveAccountDocumentRequestDTO
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class HeimdallAccountDocumentManagerService {

    public CustomerDocumentAdapter saveCustomDocument(HeimdallSaveCustomDocumentParamsAdapter saveCustomDocumentParamsAdapter) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${saveCustomDocumentParamsAdapter.customerId}/documents/custom"
        HeimdallManager heimdallManager = buildHeimdallManager()

        HeimdallSaveCustomDocumentRequestDTO heimdallSaveCustomDocumentRequestDTO = new HeimdallSaveCustomDocumentRequestDTO()
        heimdallSaveCustomDocumentRequestDTO.name = saveCustomDocumentParamsAdapter.name
        heimdallSaveCustomDocumentRequestDTO.description = saveCustomDocumentParamsAdapter.description

        heimdallManager.post(path, heimdallSaveCustomDocumentRequestDTO)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.saveCustomDocument >> Erro ao salvar documento. Customer ${saveCustomDocumentParamsAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        HeimdallDocumentResponseDTO documentResponseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, HeimdallDocumentResponseDTO)

        return new CustomerDocumentAdapter(documentResponseDTO, false)
    }

    public Boolean hasDocument(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return false

        final String path = "/accounts/${customerId}/documents/hasDocument"
        HeimdallManager heimdallManager = buildHeimdallManager()

        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.hasDocument >> Erro ao verificar se cliente tem documento. Customer [${customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        Boolean hasDocument = heimdallManager.responseBody.hasDocument?.asBoolean()
        return hasDocument
    }

    public void deleteCustomDocument(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${customerId}/documents/custom"
        HeimdallManager heimdallManager = buildHeimdallManager()

        heimdallManager.delete(path, [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.deleteCustomDocument >> Erro ao deletar documento extra. Customer [${customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }
    }

    public HeimdallCheckHasDocumentsNotSentOrRejectedResponseDTO checkIfHasCustomerDocumentNotSentOrRejected(Customer customer) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${customer.id}/documents/hasDocumentsNotSentOrRejected"
        HeimdallManager heimdallManager = buildHeimdallManager()

        HeimdallCheckHasDocumentsNotSentOrRejectedRequestDTO requestDTO = new HeimdallCheckHasDocumentsNotSentOrRejectedRequestDTO(new HeimdallBuildDefaultDocumentGroupRequestAdapter(customer))
        heimdallManager.post(path, requestDTO.toMap())

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.checkIfhasCustomerDocumentNotSentOrRejected >> Erro ao verificar se cliente tem documento não enviado ou rejeitado. Customer [${buildDefaultDocumentGroupRequestAdapter.customer.id}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        return GsonBuilderUtils.buildClassFromJson(responseBodyJson, HeimdallCheckHasDocumentsNotSentOrRejectedResponseDTO)
    }

    public Boolean hasIdentificationDocumentNotSentOrRejected(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return false

        final String path = "/accounts/${customerId}/documents/hasIdentificationDocumentNotSentOrRejected"
        HeimdallManager heimdallManager = buildHeimdallManager()

        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.hasIdentificationDocumentNotSentOrRejected >> Erro ao verificar se documento de identificação não foi enviado ou está rejeitado. Customer [${customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        Boolean hasIdentificationDocumentNotSentOrRejected = heimdallManager.responseBody.hasIdentificationDocumentNotSentOrRejected?.asBoolean()
        return hasIdentificationDocumentNotSentOrRejected
    }

    public CustomerDocumentAdapter save(SaveAccountDocumentRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${requestAdapter.customerId}/documents"

        HeimdallSaveAccountDocumentRequestDTO requestDTO = new HeimdallSaveAccountDocumentRequestDTO(requestAdapter)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post(path, requestDTO)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            }

            throw new RuntimeException("HeimdallAccountDocumentManagerService.save >> Erro ao salvar documento. Customer ${requestAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        HeimdallDocumentResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), HeimdallDocumentResponseDTO)

        return new CustomerDocumentAdapter(responseDTO, true)
    }

    public AccountDocumentValidateSaveAdapter validateSave(AccountDocumentValidateSaveRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) return new AccountDocumentValidateSaveAdapter(false)

        final String path = "/accounts/${requestAdapter.customerId}/documents/validateSave"

        HeimdallValidateSaveAccountDocumentRequestDTO requestDTO = new HeimdallValidateSaveAccountDocumentRequestDTO(requestAdapter)
        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post(path, requestDTO)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isUnprocessableEntity()) {
                return new AccountDocumentValidateSaveAdapter(false, heimdallManager.getErrorMessages().first())
            }

            throw new RuntimeException("HeimdallAccountDocumentManagerService.validateSave >> Erro ao validar envio de documento do usuário. Customer ${requestAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        return new AccountDocumentValidateSaveAdapter(true)
    }

    public void deletePendingDocuments(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${customerId}/documents/deletePending"

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.delete(path, [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.deletePendingDocuments >> Erro ao deletar documentos pendentes da conta. Customer ${customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }
    }

    public void updateWhenCpfCnpjChanged(UpdateAccountDocumentsWhenCpfCnpjChangedRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${requestAdapter.customerId}/documents/updateWhenCpfCnpjChanged"

        HeimdallUpdateAccountDocumentsWhenCpfCnpjChangedRequestDTO requestDTO = new HeimdallUpdateAccountDocumentsWhenCpfCnpjChangedRequestDTO(requestAdapter)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post(path, requestDTO)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.updateWhenCpfCnpjChanged >> Erro ao atualizar documentos da conta após alteração de cpf/cnpj. Customer ${requestAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }
    }

    public void updateExpirationDate(Long customerId, Long documentId, Date expirationDate) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${customerId}/documents/expirationDate"

        HeimdallUpdateAccountDocumentExpirationDateRequestDTO requestDTO = new HeimdallUpdateAccountDocumentExpirationDateRequestDTO(documentId, expirationDate)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.put(path, requestDTO)

        if (heimdallManager.isSuccessful()) return

        if (heimdallManager.isUnprocessableEntity()) {
            throw new BusinessException(heimdallManager.getErrorMessages().first())
        }

        if (heimdallManager.isNotFound()) {
            throw new BusinessException("Não foi possível encontrar o documento.")
        }

        throw new RuntimeException("HeimdallAccountDocumentManagerService.updateExpirationDate >> Erro ao atualizar data de vencimento do documento. Customer: [${customerId}], DocumentId: [${documentId}], StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
    }

    public List<CustomerDocumentAdapter> list(AccountDocumentSearchRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) return []

        final String path = "/accounts/${requestAdapter.customerId}/documents/list"

        HeimdallAccountDocumentSearchRequestDTO requestDTO = new HeimdallAccountDocumentSearchRequestDTO(requestAdapter)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.enableReturnAsList()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.list >> Erro ao listar documentos da conta. Customer ${requestAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        List<HeimdallDocumentResponseDTO> responseDTOList = GsonBuilderUtils.buildListFromJson((heimdallManager.responseBodyList as JSON).toString(), HeimdallDocumentResponseDTO)

        return responseDTOList.collect { new CustomerDocumentAdapter(it, true) }
    }

    public Boolean exists(AccountDocumentSearchRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) return false

        final String path = "/accounts/${requestAdapter.customerId}/documents/exists"

        HeimdallAccountDocumentSearchRequestDTO requestDTO = new HeimdallAccountDocumentSearchRequestDTO(requestAdapter)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentManagerService.exists >> Erro ao verificar existência de documento. Customer ${requestAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        Boolean exists = heimdallManager.responseBody.exists?.asBoolean()
        return exists
    }

    public CustomerDocumentAdapter find(AccountDocumentSearchRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${requestAdapter.customerId}/documents"

        HeimdallAccountDocumentSearchRequestDTO requestDTO = new HeimdallAccountDocumentSearchRequestDTO(requestAdapter)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) return null

            throw new RuntimeException("HeimdallAccountDocumentManagerService.find >> Erro ao buscar documento. Customer ${requestAdapter.customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        HeimdallDocumentResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), HeimdallDocumentResponseDTO)
        return new CustomerDocumentAdapter(responseDTO, true)
    }

    private HeimdallManager buildHeimdallManager() {
        final Integer timeout = 10000

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.setTimeout(timeout)

        return heimdallManager
    }
}
