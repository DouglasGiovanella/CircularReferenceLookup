package com.asaas.service.bifrost

import com.asaas.domain.asaascard.AsaasCard
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillAdapter
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillDetailedInfoAdapter
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillListAdapter
import com.asaas.integration.bifrost.adapter.cardbillitem.AsaasCardBillItemListAdapter
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostFindCardBillRequestDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostFindDetailedCardBillInfoRequestDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostFindDetailedCardBillInfoResponseDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostListCardBillRequestDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostListCardBillResponseDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostSaveBillPaymentRequestDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostSaveBillPaymentResponseDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostShowCardBillRequestDTO
import com.asaas.integration.bifrost.dto.asaascardbill.BifrostShowCardBillResponseDTO
import com.asaas.integration.bifrost.dto.asaascardbillitem.BifrostListCardBillItemRequestDTO
import com.asaas.integration.bifrost.dto.asaascardbillitem.BifrostListCardBillItemResponseDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostCardBillManagerService {

    BifrostCardService bifrostCardService

    public AsaasCardBillAdapter get(AsaasCard asaasCard, Map search) {
        if (!BifrostManager.isEnabled()) return new AsaasCardBillAdapter(new MockJsonUtils("bifrost/BifrostCardBillManagerService/get.json").buildMock(BifrostShowCardBillResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardBills/show", new BifrostShowCardBillRequestDTO(asaasCard, search).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar a fatura. Por favor, tente novamente.")
        if (!bifrostManager.responseBody) return null

        BifrostShowCardBillResponseDTO showCardBillResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostShowCardBillResponseDTO)
        return new AsaasCardBillAdapter(showCardBillResponseDTO)
    }

    public AsaasCardBillAdapter find(AsaasCard asaasCard, Map search) {
        if (!BifrostManager.isEnabled()) return new AsaasCardBillAdapter(new MockJsonUtils("bifrost/BifrostCardBillManagerService/get.json").buildMock(BifrostShowCardBillResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardBills/find", new BifrostFindCardBillRequestDTO(asaasCard.id, search).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar a fatura. Por favor, tente novamente.")
        if (!bifrostManager.responseBody) return null

        BifrostShowCardBillResponseDTO showCardBillResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostShowCardBillResponseDTO)
        return new AsaasCardBillAdapter(showCardBillResponseDTO)
    }

    public AsaasCardBillDetailedInfoAdapter getDetails(AsaasCard asaasCard, Long cardBillId) {
        if (!BifrostManager.isEnabled()) return new AsaasCardBillDetailedInfoAdapter(new MockJsonUtils("bifrost/BifrostCardBillManagerService/getDetailedInfo.json").buildMock(BifrostFindDetailedCardBillInfoResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardBills/findDetailed", new BifrostFindDetailedCardBillInfoRequestDTO(asaasCard.id, cardBillId).properties)

        if (!bifrostManager.isSuccessful()) throw new RuntimeException("Não foi possível consultar as informações detalhadas da fatura. Por favor, tente novamente.")
        if (!bifrostManager.responseBody) return null

        BifrostFindDetailedCardBillInfoResponseDTO bifrostFindDetailedCardBillInfoResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostFindDetailedCardBillInfoResponseDTO)

        return new AsaasCardBillDetailedInfoAdapter(bifrostFindDetailedCardBillInfoResponseDTO)
    }

    public AsaasCardBillListAdapter listCardBills(AsaasCard asaasCard, Map params) {
        if (!BifrostManager.isEnabled()) return new AsaasCardBillListAdapter(new MockJsonUtils("bifrost/BifrostCardBillManagerService/list.json").buildMock(BifrostListCardBillResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardBills/list", new BifrostListCardBillRequestDTO(asaasCard.id, params).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível listar as faturas do cartão. Por favor, tente novamente.")
        BifrostListCardBillResponseDTO listCardBillResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListCardBillResponseDTO)

        return new AsaasCardBillListAdapter(listCardBillResponseDTO)
    }

    public AsaasCardBillItemListAdapter listItems(AsaasCard asaasCard, Map params) {
        if (!BifrostManager.isEnabled()) return new AsaasCardBillItemListAdapter(asaasCard, new MockJsonUtils("bifrost/BifrostCardBillManagerService/listItems.json").buildMock(BifrostListCardBillItemResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardBillItems/list", new BifrostListCardBillItemRequestDTO(asaasCard, params).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível listar os itens da fatura do cartão. Por favor, tente novamente.")
        BifrostListCardBillItemResponseDTO listCardBillItemResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListCardBillItemResponseDTO)

        return new AsaasCardBillItemListAdapter(asaasCard, listCardBillItemResponseDTO)
    }

    public Long savePayment(AsaasCard asaasCard, BigDecimal value) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cardBills/savePayment", new BifrostSaveBillPaymentRequestDTO(asaasCard.id, value).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
        BifrostSaveBillPaymentResponseDTO saveBillPaymentResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostSaveBillPaymentResponseDTO)

        return saveBillPaymentResponseDTO.id
    }
}
