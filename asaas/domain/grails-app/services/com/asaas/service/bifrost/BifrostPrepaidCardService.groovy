package com.asaas.service.bifrost

import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.asaascard.balanceRefund.SaveBalanceRefundResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.recharge.RechargeAsaasCardRequestDTO
import com.asaas.integration.bifrost.dto.financialstatement.get.PrepaidCardFinancialStatementItemRequestDTO
import com.asaas.integration.bifrost.dto.financialstatement.get.PrepaidCardFinancialStatementItemResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.service.test.financialtransaction.AsaasCardRecalculateTestService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostPrepaidCardService {

    public AsaasCardRecharge save(AsaasCardRecharge asaasCardRecharge) {
        if (!asaasCardRecharge.asaasCard.type.isPrepaid()) throw new BusinessException("O tipo do cartão é inválido.")

        RechargeAsaasCardRequestDTO rechargeAsaasCardRequestDTO = new RechargeAsaasCardRequestDTO(asaasCardRecharge.id.toString(), asaasCardRecharge.value)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCardRecharge.asaasCard.id}/recharge", rechargeAsaasCardRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) {
            DomainUtils.addError(asaasCardRecharge, bifrostManager.getErrorMessage())
            AsaasLogger.error("BifrostPrepaidCardService.save() - Ocorreu um erro ao salvar a recarga [${asaasCardRecharge.id}]: ${bifrostManager.getErrorMessage()}")
        }

        return asaasCardRecharge
    }

    public List<Map> listPrepaidFinancialStatementItems(Long financialStatementId, Integer limit, Integer offset) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards/$financialStatementId/financialStatement", new PrepaidCardFinancialStatementItemRequestDTO(limit, offset).properties)

        if (!bifrostManager.isSuccessful()) {
            AsaasLogger.error("BifrostPrepaidCardService.getPrepaidFinancialStatementItem() -> Não foi possível buscar os itens do financialStatement. [financialStatementId: ${financialStatementId}]")
            return null
        }

        PrepaidCardFinancialStatementItemResponseDTO financialStatementItemResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), PrepaidCardFinancialStatementItemResponseDTO)

        List<Map> itemList = financialStatementItemResponseDTO.itemList.collect { it ->
            [
                dateCreated: CustomDateUtils.fromString(it.dateCreated, CustomDateUtils.DATABASE_DATETIME_FORMAT),
                transactionId: it.transactionId,
                asaasCardId: it.asaasCardId,
                customerId: it.asaasCustomerId,
                customerName: it.asaasCustomerName,
                value: it.value
            ]
        }

        return itemList
    }

    public Map saveBalanceRefund(AsaasCard asaasCard) {
        if (!BifrostManager.isEnabled()) {
            SaveBalanceRefundResponseDTO saveBalanceRefundResponseDTO = GsonBuilderUtils.buildClassFromJson(([id: Utils.generateRandomFourDigitsLong(), amount: AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE] as JSON).toString(), SaveBalanceRefundResponseDTO)
            return saveBalanceRefundResponseDTO.toMap()
        }

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/$asaasCard.id/refundPrepaidBalance", [asaasCardId: asaasCard.id])

        if (!bifrostManager.isSuccessful()) {
            AsaasLogger.error("BifrostPrepaidCardService.saveBalanceRefund() -> Erro ao estornar saldo recarregado do cartão. [id: ${asaasCard.id}, errorMessage: ${bifrostManager.getErrorMessage()}] ")
            throw new BusinessException(bifrostManager.getErrorMessage())
        }

        SaveBalanceRefundResponseDTO saveBalanceRefundResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), SaveBalanceRefundResponseDTO)

        return saveBalanceRefundResponseDTO.toMap()
    }
}
