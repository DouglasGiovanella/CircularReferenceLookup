package com.asaas.service.bifrost

import com.asaas.domain.asaascard.AsaasCard
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.asaascard.transaction.BifrostGetTransactionDetailsResponseDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostTransactionManagerService {

    public Map getDetails(AsaasCard asaasCard, Long externalId) {
        if (!BifrostManager.isEnabled()) return (new MockJsonUtils("bifrost/BifrostTransactionManagerService/getDetails.json").buildMock(BifrostGetTransactionDetailsResponseDTO) as BifrostGetTransactionDetailsResponseDTO).toMap()

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/transactions/${externalId}", [asaasCardId: asaasCard.id])

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar esta transação. Por favor, tente novamente.")
        BifrostGetTransactionDetailsResponseDTO transactionDto = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetTransactionDetailsResponseDTO)

        return transactionDto.toMap()
    }

    public void refund(String asaasCardId, Long externalId, BigDecimal amount) {
        if (!BifrostManager.isEnabled()) return

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/transactions/${externalId}/refund", [asaasCardId: asaasCardId, amount: amount])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }
}
