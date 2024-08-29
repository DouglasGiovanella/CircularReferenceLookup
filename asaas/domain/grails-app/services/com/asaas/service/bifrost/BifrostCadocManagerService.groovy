package com.asaas.service.bifrost

import com.asaas.integration.bifrost.adapter.cadoc.CadocAsaasCardBillsInfoAdapter
import com.asaas.integration.bifrost.adapter.cadoc.CadocAsaasCardTransactionsInfoAdapter
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.cadoc.BifrostGetCadocBillsInfoResponseDTO
import com.asaas.integration.bifrost.dto.cadoc.BifrostGetCadocSearchInfoRequestDTO
import com.asaas.integration.bifrost.dto.cadoc.BifrostGetCadocTransactionsInfoResponseDTO
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostCadocManagerService {

    public CadocAsaasCardTransactionsInfoAdapter findTransactionsInfo(Date referenceDate) {
        BifrostGetCadocSearchInfoRequestDTO bifrostCadocSearchInfoRequestDTO = new BifrostGetCadocSearchInfoRequestDTO(referenceDate)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cadoc/transactions", bifrostCadocSearchInfoRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) throw new RuntimeException("Não foi possível consultar as informações de transações no Bifrost.")

        BifrostGetCadocTransactionsInfoResponseDTO bifrostGetCadocTransactionsInfoResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetCadocTransactionsInfoResponseDTO)

        return new CadocAsaasCardTransactionsInfoAdapter(bifrostGetCadocTransactionsInfoResponseDTO)
    }

    public CadocAsaasCardBillsInfoAdapter findBillsInfo(Date referenceDate) {
        BifrostGetCadocSearchInfoRequestDTO bifrostCadocSearchInfoRequestDTO = new BifrostGetCadocSearchInfoRequestDTO(referenceDate)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cadoc/bills", bifrostCadocSearchInfoRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) throw new RuntimeException("Não foi possível consultar as informações de faturas no Bifrost.")

        BifrostGetCadocBillsInfoResponseDTO bifrostGetCadocBillsInfoResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetCadocBillsInfoResponseDTO)

        return new CadocAsaasCardBillsInfoAdapter(bifrostGetCadocBillsInfoResponseDTO)
    }
}
