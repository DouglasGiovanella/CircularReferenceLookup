package com.asaas.service.integration.heimdall

import com.asaas.customerdocument.adapter.CustomerDocumentFileAdapter
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.adapter.document.HeimdallDocumentFileSearchParamsRequestAdapter
import com.asaas.integration.heimdall.dto.document.HeimdallDocumentFileResponseDTO
import com.asaas.integration.heimdall.dto.document.HeimdallDocumentFileSearchParamsRequestDTO
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class HeimdallAccountDocumentFileManagerService {

    public CustomerDocumentFileAdapter find(HeimdallDocumentFileSearchParamsRequestAdapter adapter) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${adapter.accountId}/documents/files"
        HeimdallDocumentFileSearchParamsRequestDTO requestDTO = new HeimdallDocumentFileSearchParamsRequestDTO(adapter)
        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                return null
            }
            throw new RuntimeException("HeimdallAccountDocumentFileManagerService.find >> Erro ao encontrar arquivo de documento. Customer [${adapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        HeimdallDocumentFileResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), HeimdallDocumentFileResponseDTO)
        CustomerDocumentFileAdapter customerDocumentFileAdapter = new CustomerDocumentFileAdapter(responseDTO)
        return customerDocumentFileAdapter
    }

    public Integer count(HeimdallDocumentFileSearchParamsRequestAdapter adapter) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${adapter.accountId}/documents/files/count"
        HeimdallDocumentFileSearchParamsRequestDTO requestDTO = new HeimdallDocumentFileSearchParamsRequestDTO(adapter)
        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentFileManagerService.count >> Erro ao contar arquivos de documentos. Customer [${adapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        Integer count = Integer.valueOf(heimdallManager.responseBody.count)
        return count
    }

    public List<CustomerDocumentFileAdapter> list(HeimdallDocumentFileSearchParamsRequestAdapter adapter) {
        if (!AsaasEnvironment.isProduction()) return []

        final String path = "/accounts/${adapter.accountId}/documents/files/list"
        HeimdallDocumentFileSearchParamsRequestDTO requestDTO = new HeimdallDocumentFileSearchParamsRequestDTO(adapter)
        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.enableReturnAsList()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentFileManagerService.list >> Erro ao listar arquivos de documentos. Customer [${adapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBodyList}]")
        }

        List<HeimdallDocumentFileResponseDTO> responseDTOList = GsonBuilderUtils.buildListFromJson((heimdallManager.responseBodyList as JSON).toString(), HeimdallDocumentFileResponseDTO)

        return responseDTOList.collect { HeimdallDocumentFileResponseDTO responseDTO ->
            new CustomerDocumentFileAdapter(responseDTO)
        }
    }

    public List<Object> listColumn(String column, HeimdallDocumentFileSearchParamsRequestAdapter adapter) {
        if (!AsaasEnvironment.isProduction()) return []

        final String path = "/accounts/${adapter.accountId}/documents/files/listColumn/${column}"
        HeimdallDocumentFileSearchParamsRequestDTO requestDTO = new HeimdallDocumentFileSearchParamsRequestDTO(adapter)
        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.enableReturnAsList()
        heimdallManager.get(path, requestDTO.properties)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentFileManagerService.listColumn >> Erro ao listar coluna dos arquivos de documentos. Customer [${adapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBodyList}]")
        }

        return heimdallManager.responseBodyList
    }

    public void delete(Long accountId, Long documentFileId) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${accountId}/documents/files/${documentFileId}"

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.delete(path, [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                throw new BusinessException("Documento não encontrado.")
            }

            throw new RuntimeException("HeimdallAccountDocumentFileManagerService.delete >> Erro ao deletar arquivo. Customer [${accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }
    }

    private HeimdallManager buildHeimdallManager() {
        final Integer timeout = 10000

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.setTimeout(timeout)

        return heimdallManager
    }
}
