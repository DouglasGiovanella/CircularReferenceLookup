package com.asaas.service.integration.heimdall

import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.customerdocumentgroup.adapter.CustomerDocumentGroupAdapter
import com.asaas.customerdocumentgroup.adapter.CustomerDocumentGroupHistoryAdapter
import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.adapter.document.HeimdallBuildDefaultDocumentGroupRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallBuildIdentificationDocumentGroupRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallDeleteDocumentGroupAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallDocumentGroupSearchParamsRequestAdapter
import com.asaas.integration.heimdall.adapter.document.HeimdallSaveDocumentListParamsAdapter
import com.asaas.integration.heimdall.dto.document.HeimdallBuildDefaultDocumentGroupRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallBuildIdentificationDocumentGroupTypeRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallDeleteDocumentGroupRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallDocumentGroupResponseDTO
import com.asaas.integration.heimdall.dto.document.HeimdallDocumentGroupSearchParamsRequestDTO
import com.asaas.integration.heimdall.dto.document.HeimdallSaveDocumentListRequestDTO
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HeimdallAccountDocumentGroupManagerService {

    public List<CustomerDocumentGroupAdapter> buildDefaultDocumentGroup(HeimdallBuildDefaultDocumentGroupRequestAdapter buildDefaultDocumentGroupRequestAdapter) {
        final String path = "/accounts/${buildDefaultDocumentGroupRequestAdapter.accountId}/documents/groups/buildDefault"

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.enableReturnAsList()

        HeimdallBuildDefaultDocumentGroupRequestDTO requestDTO = new HeimdallBuildDefaultDocumentGroupRequestDTO(buildDefaultDocumentGroupRequestAdapter)
        heimdallManager.post(path, requestDTO)

        if (!heimdallManager.isSuccessful()) throw new RuntimeException("Ocorreu um problema no servidor do Heimdall e não foi possível construir o grupo de documentos.")

        List<HeimdallDocumentGroupResponseDTO> responseDTOList = GsonBuilderUtils.buildListFromJson((heimdallManager.responseBodyList as JSON).toString(), HeimdallDocumentGroupResponseDTO)

        Customer customer = buildDefaultDocumentGroupRequestAdapter.customer
        return responseDTOList.collect({ new CustomerDocumentGroupAdapter(it, customer) })
    }

    public CustomerDocumentGroupType buildIdentificationGroupType(HeimdallBuildIdentificationDocumentGroupRequestAdapter adapter) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${adapter.accountId}/documents/identificationGroupType"

        HeimdallBuildIdentificationDocumentGroupTypeRequestDTO requestDTO = new HeimdallBuildIdentificationDocumentGroupTypeRequestDTO(adapter)
        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get(path, requestDTO.toMap())

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentGroupManagerService.buildIdentificationGroupType >> buscar tipo de grupo de identificação. Customer [${adapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        String groupTypeString = heimdallManager.responseBody.groupType
        return CustomerDocumentGroupType.convert(groupTypeString)
    }

    public CustomerDocumentGroupAdapter saveList(HeimdallSaveDocumentListParamsAdapter saveDocumentListAdapter) {
        final String path = "/accounts/${saveDocumentListAdapter.customer.id}/documents/groups"

        HeimdallSaveDocumentListRequestDTO requestDTO = new HeimdallSaveDocumentListRequestDTO(saveDocumentListAdapter)

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post(path, requestDTO.toMap())
        if (!heimdallManager.isSuccessful()) throw new RuntimeException("Ocorreu um problema no servidor do Heimdall e não foi possível salvar a lista de documentos.")

        HeimdallDocumentGroupResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), HeimdallDocumentGroupResponseDTO)
        return new CustomerDocumentGroupAdapter(responseDTO, saveDocumentListAdapter.customer)
    }

    public List<CustomerDocumentGroupAdapter> buildList(HeimdallDocumentGroupSearchParamsRequestAdapter requestAdapter) {
        List<HeimdallDocumentGroupResponseDTO> documentGroupDTOList = list(requestAdapter)

        Customer customer = requestAdapter.customer
        return documentGroupDTOList.collect({ new CustomerDocumentGroupAdapter(it, customer) })
    }

    public List<CustomerDocumentGroupHistoryAdapter> buildHistoryList(HeimdallDocumentGroupSearchParamsRequestAdapter requestAdapter) {
        List<HeimdallDocumentGroupResponseDTO> documentGroupDTOList = list(requestAdapter)

        Customer customer = requestAdapter.customer
        return documentGroupDTOList.collect({ new CustomerDocumentGroupHistoryAdapter(it, customer) })
    }

    public CustomerDocumentGroupAdapter findCustom(Customer customer) {
        if (!AsaasEnvironment.isProduction()) return null

        final String path = "/accounts/${customer.id}/documents/groups/findCustom"

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                return null
            }
            throw new RuntimeException("HeimdallAccountDocumentGroupManagerService.findCustom >> Erro ao encontrar grupo de documentos. Customer [${customer.id}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        HeimdallDocumentGroupResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), HeimdallDocumentGroupResponseDTO)

        return new CustomerDocumentGroupAdapter(responseDTO, customer)
    }

    public void delete(HeimdallDeleteDocumentGroupAdapter deleteDocumentGroupAdapter) {
        final String path = "/accounts/${deleteDocumentGroupAdapter.accountId}/documents/groups/delete"

        HeimdallDeleteDocumentGroupRequestDTO requestDTO = new HeimdallDeleteDocumentGroupRequestDTO(deleteDocumentGroupAdapter)

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post(path, requestDTO.toMap())

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentGroupManagerService.delete >> Erro ao deletar grupos de documentos. Customer [${deleteDocumentGroupAdapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }
    }

    private List<HeimdallDocumentGroupResponseDTO> list(HeimdallDocumentGroupSearchParamsRequestAdapter requestAdapter) {
        final String path = "/accounts/${requestAdapter.accountId}/documents/groups/list"

        HeimdallManager heimdallManager = new HeimdallManager()

        HeimdallDocumentGroupSearchParamsRequestDTO requestDTO = new HeimdallDocumentGroupSearchParamsRequestDTO(requestAdapter)
        heimdallManager.enableReturnAsList()
        heimdallManager.get(path, requestDTO.toMap())

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentGroupManagerService.list >> Erro ao listar grupos de documentos. Customer [${requestAdapter.accountId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        List<HeimdallDocumentGroupResponseDTO> responseDTOList = GsonBuilderUtils.buildListFromJson((heimdallManager.responseBodyList as JSON).toString(), HeimdallDocumentGroupResponseDTO) as List<HeimdallDocumentGroupResponseDTO>

        return responseDTOList
    }
}
