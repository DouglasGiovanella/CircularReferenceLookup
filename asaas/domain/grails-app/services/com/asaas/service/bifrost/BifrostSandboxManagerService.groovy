package com.asaas.service.bifrost

import com.asaas.asaascard.AsaasCardType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.sandbox.BifrostSandboxGetNewCardTypeRequestDTO
import com.asaas.integration.bifrost.dto.sandbox.BifrostSandboxSaveRefundRequestDTO
import com.asaas.integration.bifrost.dto.sandbox.BifrostSandboxUpdateCardTypeRequestDTO
import com.asaas.integration.bifrost.dto.sandbox.BifrostSandboxGetNewCardTypeResponseDTO
import com.asaas.integration.bifrost.dto.sandbox.transaction.BifrostSandboxSaveTransactionRequestDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils

import grails.converters.JSON
import grails.transaction.Transactional

import java.sql.Timestamp

@Transactional
class BifrostSandboxManagerService {

    public void savePurchase(Long asaasCardId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostSandboxSaveTransactionRequestDTO transactionRequestDTO = new MockJsonUtils("bifrost/BifrostSandboxManagerService/purchase.json").buildMock(BifrostSandboxSaveTransactionRequestDTO)
        transactionRequestDTO.installmentCount = 0

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/saveTransaction", buildTransactionInfo(transactionRequestDTO, asaasCardId).properties)
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void savePurchaseInInstallments(Long asaasCardId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostSandboxSaveTransactionRequestDTO transactionRequestDTO = new MockJsonUtils("bifrost/BifrostSandboxManagerService/purchase.json").buildMock(BifrostSandboxSaveTransactionRequestDTO)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/saveTransaction", buildTransactionInfo(transactionRequestDTO, asaasCardId).properties)
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void saveWithdrawal(Long asaasCardId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostSandboxSaveTransactionRequestDTO transactionRequestDTO = new MockJsonUtils("bifrost/BifrostSandboxManagerService/withdrawal.json").buildMock(BifrostSandboxSaveTransactionRequestDTO)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/saveTransaction", buildTransactionInfo(transactionRequestDTO, asaasCardId).properties)
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void saveRefund(String asaasCardId, Long transactionId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/saveRefund", buildRefundInfo(asaasCardId, transactionId).properties)
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCardType getNewCardType(AsaasCard asaasCard) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostSandboxGetNewCardTypeRequestDTO getNewCardTypeRequestDTO = new BifrostSandboxGetNewCardTypeRequestDTO(asaasCard.id.toString())

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/sandbox/getNewCardType", getNewCardTypeRequestDTO.properties)
        if (!bifrostManager.responseBody) throw new BusinessException(bifrostManager.getErrorMessage())

        BifrostSandboxGetNewCardTypeResponseDTO getNewCardTypeResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostSandboxGetNewCardTypeResponseDTO)
        return getNewCardTypeResponseDTO.newType
    }

    public void updateCardType(AsaasCard asaasCard) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostSandboxUpdateCardTypeRequestDTO updateCardTypeRequestDTO = new BifrostSandboxUpdateCardTypeRequestDTO(asaasCard)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/updateCardType", updateCardTypeRequestDTO.properties)
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    private BifrostSandboxSaveTransactionRequestDTO buildTransactionInfo(BifrostSandboxSaveTransactionRequestDTO requestDTO, Long asaasCardId) {
        requestDTO.externalId = new Timestamp(System.currentTimeMillis()).getTime().toString()
        requestDTO.asaasCardId = asaasCardId

        return requestDTO
    }

    private BifrostSandboxSaveRefundRequestDTO buildRefundInfo(String asaasCardId, Long transactionId) {
        String externalId = new Timestamp(System.currentTimeMillis()).getTime().toString()

        return new BifrostSandboxSaveRefundRequestDTO(externalId, asaasCardId, transactionId)
    }
}
