package com.asaas.service.integration

import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.customerdocument.adapter.CustomerDocumentFileAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.documentanalysis.save.identification.IdentificationDocumentRequestDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.identification.IdentificationDocumentAnalysisResponseDTO
import com.asaas.integration.heimdall.dto.documentanalysis.save.identification.children.IdentificationDocumentFileRequestDTO
import com.asaas.integration.heimdall.enums.DocumentFileType
import com.asaas.integration.heimdall.enums.identificationdocumentanalysis.IdentificationDocumentAnalysisType
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class IdentificationDocumentAnalysisManagerService {

    public void saveAuthorizationDeviceAnalysis(CustomerDocumentAdapter customerDocumentAdapter, AsaasFile selfieFile) {
        try {
            List<CustomerDocumentFileAdapter> customerDocumentFileList = customerDocumentAdapter.customerDocumentFileList

            IdentificationDocumentRequestDTO identificationDocumentRequestDTO = new IdentificationDocumentRequestDTO(customerDocumentAdapter)
            identificationDocumentRequestDTO.analysisType = IdentificationDocumentAnalysisType.AUTHORIZATION_DEVICE
            identificationDocumentRequestDTO.cpf = customerDocumentAdapter.group.customer.getOwnerCpf()

            IdentificationDocumentFileRequestDTO selfieFileDTO = new IdentificationDocumentFileRequestDTO(selfieFile, DocumentFileType.SELFIE)
            identificationDocumentRequestDTO.files.add(selfieFileDTO)

            CustomerDocumentFileAdapter frontFile = customerDocumentFileList.first()
            IdentificationDocumentFileRequestDTO frontFileDTO = new IdentificationDocumentFileRequestDTO(frontFile, DocumentFileType.FRONT)
            identificationDocumentRequestDTO.files.add(frontFileDTO)

            if (customerDocumentFileList.size() > 1) {
                CustomerDocumentFileAdapter backFile = customerDocumentFileList.last()
                IdentificationDocumentFileRequestDTO backFileDTO = new IdentificationDocumentFileRequestDTO(backFile, DocumentFileType.BACK)
                identificationDocumentRequestDTO.files.add(backFileDTO)
            }

            HeimdallManager heimdallManager = new HeimdallManager()
            heimdallManager.post("/identificationDocuments", identificationDocumentRequestDTO)

            if (!heimdallManager.isSuccessful()) {
                AsaasLogger.error("Heimdall retornou um status diferente de sucesso ao salvar o documento de identificação. StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
            }
        } catch (Exception exception) {
            AsaasLogger.error("IdentificationDocumentAnalysisManagerService.saveAuthorizationDeviceAnalysis >> Ocorreu um erro ao salvar análise de autorização de dispositivo.", exception)
        }
    }

    public IdentificationDocumentAnalysisResponseDTO getById(Long id) {
        return get("/identificationDocumentAnalysis/${id}")
    }

    public IdentificationDocumentAnalysisResponseDTO getByAccountId(Customer customer) {
        return get("/identificationDocumentAnalysis/findByAccountId/${customer.id}")
    }

    private IdentificationDocumentAnalysisResponseDTO get(String path) {
        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("Não foi possível encontrar a análise do documento de identificação. Path [${path}].")
            } else {
                AsaasLogger.error("Heimdall retornou um status diferente de sucesso ao buscar a análise do documento de identificação. Path ${path}, StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
            }
            return null
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        IdentificationDocumentAnalysisResponseDTO identificationDocumentAnalysisResponseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, IdentificationDocumentAnalysisResponseDTO)
        return identificationDocumentAnalysisResponseDTO
    }
}
