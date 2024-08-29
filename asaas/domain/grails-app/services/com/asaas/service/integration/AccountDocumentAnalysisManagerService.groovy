package com.asaas.service.integration

import com.asaas.documentanalysis.adapter.AccountDocumentAnalysisAdapter
import com.asaas.domain.customer.Customer
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.documentanalysis.get.account.AccountDocumentAnalysisResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class AccountDocumentAnalysisManagerService {

    public AccountDocumentAnalysisResponseDTO getById(Long id) {
        return get("/accountDocumentAnalysis/${id}")
    }

    public AccountDocumentAnalysisResponseDTO getByAccountId(Customer customer) {
        return get("/accountDocumentAnalysis/findByAccountId/${customer.id}")
    }

    public AccountDocumentAnalysisAdapter findByAccountId(Long customerId) {
        AccountDocumentAnalysisResponseDTO accountDocumentAnalysisResponseDTO = get("/accountDocumentAnalysis/findByAccountId/${customerId}")
        if (!accountDocumentAnalysisResponseDTO) {
            AsaasLogger.warn("AccountDocumentAnalysisManagerService.findByAccountId >> Erro ao buscar a análise de documentos. CustomerId [${customerId}]")
            return null
        }

        return new AccountDocumentAnalysisAdapter(accountDocumentAnalysisResponseDTO)
    }

    private AccountDocumentAnalysisResponseDTO get(String path) {
        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("Não foi possível encontrar a análise do documento da conta. Path [${path}].")
            } else {
                AsaasLogger.error("AccountDocumentAnalysisManagerService.get >> Heimdall retornou um status diferente de sucesso ao buscar a análise do documento da conta. Path ${path}, StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
            }

            return null
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        AccountDocumentAnalysisResponseDTO accountDocumentAnalysisResponseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, AccountDocumentAnalysisResponseDTO)
        return accountDocumentAnalysisResponseDTO
    }
}
