package com.asaas.service.bifrost

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardAdapter
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardListAdapter
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardPermissionsAdapter
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCreditCardLimitAdapter
import com.asaas.integration.bifrost.adapter.cardfinancialtransaction.AsaasCardFinancialTransactionListAdapter
import com.asaas.integration.bifrost.adapter.cardfinancialtransaction.DetailedCardFinancialTransactionListAdapter
import com.asaas.integration.bifrost.adapter.creditcardeventhistory.AsaasCreditCardEventHistoryListAdapter
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.asaascard.activate.ActivateAsaasCardRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.cardfinancialtransaction.BifrostListCardFinancialTransactionRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.cardfinancialtransaction.BifrostListCardFinancialTransactionResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.cardfinancialtransaction.BifrostListDetailedCardFinancialTransactionRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.cardfinancialtransaction.BifrostListDetailedCardFinancialTransactionResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.changepin.ChangePinAsaasCardRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.configcard.ConfigRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.create.CreateAsaasCardRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.creditcardeventhistory.BifrostListCreditCardEventHistoryRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.creditcardeventhistory.BifrostListCreditCardEventHistoryResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.creditcardlimit.BifrostGetCreditCardLimitResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.deliverytrack.DeliveryTrackDTO
import com.asaas.integration.bifrost.dto.asaascard.deliverytrack.children.DeliveryTrackEventDTO
import com.asaas.integration.bifrost.dto.asaascard.deliverytrack.children.DeliveryTrackEventType
import com.asaas.integration.bifrost.dto.asaascard.get.BifrostGetAsaasCardResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.list.BifrostListCardRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.list.BifrostListCardResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.permissions.BifrostGetCardPermissionsResponseDTO
import com.asaas.integration.bifrost.dto.asaascard.reissue.ReissueAsaasCardRequestDTO
import com.asaas.integration.bifrost.dto.asaascard.updatetype.BifrostUpdateDebitCardToCreditCardRequestDTO
import com.asaas.integration.bifrost.vo.CardConfigVO
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostCardService {

    def criticalActionService

    public void save(AsaasCard asaasCard) {
        if (asaasCard.brand.isMasterCard() && !asaasCard.customer.asaasCardMastercardEnabled()) throw new BusinessException("Bandeira inválida.")

        CreateAsaasCardRequestDTO createAsaasCardRequestDTO = new CreateAsaasCardRequestDTO(asaasCard)
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.isLogEnabled = true
        bifrostManager.post("/cards", createAsaasCardRequestDTO.properties)
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCardAdapter get(AsaasCard asaasCard) {
        if (!BifrostManager.isEnabled()) return new AsaasCardAdapter(asaasCard, new AsaasCardPermissionsAdapter(new MockJsonUtils("bifrost/BifrostCardService/getPermissions.json").buildMock(BifrostGetCardPermissionsResponseDTO) as BifrostGetCardPermissionsResponseDTO), new AsaasCreditCardLimitAdapter(new MockJsonUtils("bifrost/BifrostCardService/getCreditCardLimit.json").buildMock(BifrostGetCreditCardLimitResponseDTO) as BifrostGetCreditCardLimitResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards/${asaasCard.id}", [:])

        if (!bifrostManager.isSuccessful()) {
            AsaasLogger.error("BifrostCardService -> O seguinte erro foi retornado ao consultar o cartão ${asaasCard.id}: ${bifrostManager.getErrorMessage()}")
            throw new BusinessException(bifrostManager.getErrorMessage())
        }

        BifrostGetAsaasCardResponseDTO asaasCardResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetAsaasCardResponseDTO)
        return new AsaasCardAdapter(asaasCardResponseDTO)
    }

    public AsaasCardListAdapter list(Long customerId, Map filters) {
        if (!BifrostManager.isEnabled()) {
            List<AsaasCard> asaasCardList = AsaasCard.query([customerId: customerId]).list(readOnly: true, max: filters.limit, offset: filters.offset) as List<AsaasCard>
            AsaasCardPermissionsAdapter permissionsAdapter = new AsaasCardPermissionsAdapter(new MockJsonUtils("bifrost/BifrostCardService/getPermissions.json").buildMock(BifrostGetCardPermissionsResponseDTO) as BifrostGetCardPermissionsResponseDTO)
            AsaasCreditCardLimitAdapter creditCardLimitAdapter = new AsaasCreditCardLimitAdapter(new MockJsonUtils("bifrost/BifrostCardService/getCreditCardLimit.json").buildMock(BifrostGetCreditCardLimitResponseDTO) as BifrostGetCreditCardLimitResponseDTO)

            return new AsaasCardListAdapter(asaasCardList, permissionsAdapter, creditCardLimitAdapter)
        }

        BifrostListCardRequestDTO bifrostListCardRequestDTO = new BifrostListCardRequestDTO(customerId, filters)
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards", bifrostListCardRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) {
            AsaasLogger.warn("BifrostCardService -> O seguinte erro foi retornado ao listar cartões [customerId: ${customerId}]: ${bifrostManager.getErrorMessage()}")
            throw new RuntimeException(bifrostManager.getErrorMessage())
        }

        BifrostListCardResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListCardResponseDTO)
        return new AsaasCardListAdapter(responseDTO)
    }

    public void block(AsaasCard asaasCard, Boolean isUnlockable) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/block", [unlockable: isUnlockable])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCard unblock(AsaasCard asaasCard, Long groupId, String token, Boolean bypassCriticalActionValidation) {
        if (!bypassCriticalActionValidation) {
            AsaasCard validatedAsaasCardUnblockToken = validateToken(asaasCard, groupId, token, CriticalActionType.ASAAS_CARD_UNBLOCK)
            if (validatedAsaasCardUnblockToken.hasErrors()) return validatedAsaasCardUnblockToken
        }

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/unblock", [:])

        if (bifrostManager.isSuccessful()) return asaasCard
        throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCard cancel(AsaasCard asaasCard) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/cancel", [:])

        if (bifrostManager.isSuccessful()) return asaasCard
        throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCard activate(AsaasCard asaasCard, Long groupId, String token, String pin, String lastFourDigits) {
        if (!BifrostManager.isEnabled()) return asaasCard

        AsaasCard validatedAsaasCardActivateToken = validateToken(asaasCard, groupId, token, CriticalActionType.ASAAS_CARD_ACTIVATE)
        if (validatedAsaasCardActivateToken.hasErrors()) return validatedAsaasCardActivateToken

        ActivateAsaasCardRequestDTO activateAsaasCardRequestDTO = new ActivateAsaasCardRequestDTO(lastFourDigits, pin)
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/activate", activateAsaasCardRequestDTO.properties)

        if (bifrostManager.isSuccessful()) return asaasCard

        throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void validateLastDigits(AsaasCard asaasCard, String lastFourDigits) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards/${asaasCard.id}/validateLastDigits", [lastFourDigits: lastFourDigits])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCard changePin(AsaasCard asaasCard, Long groupId, String token, String pin) {
        AsaasCard validatedAsaasCardToken = validateToken(asaasCard, groupId, token, CriticalActionType.ASAAS_CARD_CHANGE_PIN)
        if (validatedAsaasCardToken.hasErrors()) return validatedAsaasCardToken

        ChangePinAsaasCardRequestDTO changePinAsaasCardRequestDTO = new ChangePinAsaasCardRequestDTO(asaasCard.lastDigits, pin)
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/changePin", changePinAsaasCardRequestDTO.properties)

        if (bifrostManager.isSuccessful()) return asaasCard
        throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void reissue(AsaasCard asaasCard, AsaasCard newAsaasCard) {
        ReissueAsaasCardRequestDTO reissueAsaasCardRequestDTO = new ReissueAsaasCardRequestDTO(asaasCard, newAsaasCard)
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/reissue", reissueAsaasCardRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCardFinancialTransactionListAdapter listAsaasCardFinancialTransaction(AsaasCard asaasCard, Map search) {
        if (!BifrostManager.isEnabled()) return new AsaasCardFinancialTransactionListAdapter(new MockJsonUtils("bifrost/BifrostCardService/listCardFinancialTransaction.json").buildMock(BifrostListCardFinancialTransactionResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardFinancialTransactions/list", new BifrostListCardFinancialTransactionRequestDTO(asaasCard, search).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar as movimentações do Cartão Asaas. Por favor, tente novamente.")
        BifrostListCardFinancialTransactionResponseDTO listCardFinancialTransactionResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListCardFinancialTransactionResponseDTO)

        return new AsaasCardFinancialTransactionListAdapter(listCardFinancialTransactionResponseDTO)
    }

    public List<Map> getDeliveryTrackInfo(AsaasCard asaasCard) {
        if (!asaasCard.brand.isElo()) throw new RuntimeException("Bandeira inválida.")

        DeliveryTrackDTO deliveryTrackDto

        if (BifrostManager.isEnabled()) {
            BifrostManager bifrostManager = new BifrostManager()
            bifrostManager.get("/cards/${asaasCard.id}/tracking", [:])

            if (!bifrostManager.isSuccessful()) return null

            deliveryTrackDto = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), DeliveryTrackDTO)
        } else {
            deliveryTrackDto = getMockedTracking()
        }

        if (!deliveryTrackDto) return []
        return deliveryTrackDto.events.collect{ it.properties }.sort{ it.date }.reverse()
    }

    public void updateDebitCardToCreditCard(AsaasCard asaasCard) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/updateDebitToCredit", new BifrostUpdateDebitCardToCreditCardRequestDTO(AsaasCard.DEFAULT_BILL_DUE_DAY).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCreditCardLimitAdapter getCreditCardLimit(AsaasCard asaasCard) {
        if (!BifrostManager.isEnabled()) return new AsaasCreditCardLimitAdapter(new MockJsonUtils("bifrost/BifrostCardService/getCreditCardLimit.json").buildMock(BifrostGetCreditCardLimitResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards/${asaasCard.id}/getCreditCardLimit", [:])

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar os limites do Cartão Asaas. Por favor, tente novamente.")
        BifrostGetCreditCardLimitResponseDTO getCreditCardLimitResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetCreditCardLimitResponseDTO)

        return new AsaasCreditCardLimitAdapter(getCreditCardLimitResponseDTO)
    }


    public AsaasCreditCardEventHistoryListAdapter listCreditCardEventHistories(AsaasCard asaasCard, Map params) {
        if (!BifrostManager.isEnabled()) return new AsaasCreditCardEventHistoryListAdapter(new MockJsonUtils("bifrost/BifrostCardService/listCreditCardEventHistories.json").buildMock(BifrostListCreditCardEventHistoryResponseDTO))

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/creditCardEventHistories/list", new BifrostListCreditCardEventHistoryRequestDTO(asaasCard, params).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar o histórico do Cartão Asaas. Por favor, tente novamente.")
        BifrostListCreditCardEventHistoryResponseDTO listCreditCardEventHistoryResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListCreditCardEventHistoryResponseDTO)

        return new AsaasCreditCardEventHistoryListAdapter(listCreditCardEventHistoryResponseDTO)
    }

    public AsaasCard validateToken(AsaasCard asaasCard, Long groupId, String token, CriticalActionType criticalActionType) {
        Boolean criticalActionConfigAsaasCardStatusManipulation = CustomerCriticalActionConfig.query([column: "asaasCardStatusManipulation", customerId: asaasCard.customerId]).get()
        Boolean hasDisabledToken = (criticalActionConfigAsaasCardStatusManipulation == false)
        if (hasDisabledToken) return asaasCard

        CriticalAction criticalAction = CriticalAction.awaitingAuthorization([includeSynchronous: true, synchronous: true, customer: asaasCard.customer, asaasCard: asaasCard, type: criticalActionType, sort: "id", order: "desc"]).get()
        if (!criticalAction) {
            DomainUtils.addError(asaasCard, Utils.getMessageProperty("criticalAction.error.expired"))
            return asaasCard
        }

        CriticalActionGroup criticalActionGroup = CriticalActionGroup.find(asaasCard.customer, groupId)
        if (criticalActionGroup != criticalAction.group) {
            DomainUtils.addError(asaasCard, Utils.getMessageProperty("criticalAction.error.expired"))
            return asaasCard
        }

        CriticalActionGroup authorizedCriticalActionGroup = criticalActionService.authorizeGroup(asaasCard.customer, criticalAction.group.id, token)
        if (!authorizedCriticalActionGroup.isAuthorized()) {
            String errorCode = "criticalAction.error.invalid.token.withRemainingAuthorizationAttempts"
            List errorArguments = [criticalActionGroup.getRemainingAuthorizationAttempts()]

            if (criticalActionGroup.getRemainingAuthorizationAttempts() == 1) {
                errorCode = "criticalAction.error.invalid.token.withoutRemainingAuthorizationAttempts"
                errorArguments = [AuthorizationDevice.findCurrentTypeDescription(asaasCard.customer)]
            }

            DomainUtils.addError(asaasCard, Utils.getMessageProperty(errorCode, errorArguments))
            return asaasCard
        }

        return asaasCard
    }

    public void updateUnlockable(AsaasCard asaasCard, Boolean unlockable) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/updateUnlockable", [unlockable: unlockable])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void config(AsaasCard asaasCard, CardConfigVO cardConfigVO) {
        ConfigRequestDTO configRequestDTO = new ConfigRequestDTO(cardConfigVO)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/config", configRequestDTO.properties)

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public DetailedCardFinancialTransactionListAdapter listDetailedCardFinancialTransaction(Integer limit, Integer offset, Map params) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cardFinancialTransactions/listDetailed", new BifrostListDetailedCardFinancialTransactionRequestDTO(params + [limit: limit, offset: offset]).toMap())

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())

        BifrostListDetailedCardFinancialTransactionResponseDTO listCardFinancialTransactionResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListDetailedCardFinancialTransactionResponseDTO)

        return new DetailedCardFinancialTransactionListAdapter(listCardFinancialTransactionResponseDTO)
    }

    public AsaasCard getBalance(AsaasCard asaasCard, Map filter) {
        if (!BifrostManager.isEnabled()) return asaasCard

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards/${asaasCard.id}/balance", filter)

        asaasCard.cachedBalance = null

        if (bifrostManager.isSuccessful()) {
            asaasCard.cachedBalance = bifrostManager.responseBody.totalBalance
        } else {
            DomainUtils.addError(asaasCard, "unknow.error")
        }

        return asaasCard
    }

    public AsaasCardPermissionsAdapter getPermissions(AsaasCard asaasCard) {
        if (!BifrostManager.isEnabled()) return new AsaasCardPermissionsAdapter(new MockJsonUtils("bifrost/BifrostCardService/getPermissions.json").buildMock(BifrostGetCardPermissionsResponseDTO) as BifrostGetCardPermissionsResponseDTO)

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/cards/${asaasCard.id}/permissions", [:])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())

        BifrostGetCardPermissionsResponseDTO permissionsResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetCardPermissionsResponseDTO)

        return new AsaasCardPermissionsAdapter(permissionsResponseDTO)
    }

    private DeliveryTrackDTO getMockedTracking() {
        DeliveryTrackDTO deliveryTrackDto = new DeliveryTrackDTO()

        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.WAITING_TO_BE_SENT, date: CustomDateUtils.sumDays(new Date(), -19), description: "Aguardando envio"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.READY_TO_SHIP, date: CustomDateUtils.sumDays(new Date(), -18), description: "Preparado para envio"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.POSTED_FOR_DELIVERY, date: CustomDateUtils.sumDays(new Date(), -17), description: "Cartão postado"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.INCORRECT_ADDRESS, date: CustomDateUtils.sumDays(new Date(), -16), description: "Endereço incorreto"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.CUSTOMER_CONTACT_ATTEMPT, date: CustomDateUtils.sumDays(new Date(), -15), description: "Em tentativa de contato com o cliente"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.DELIVERY_FAIL, date: CustomDateUtils.sumDays(new Date(), -14), description: "Tentativa de entrega sem sucesso"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.HELD_FOR_DEVOLUTION, date: CustomDateUtils.sumDays(new Date(), -13), description: "Retido para devolução"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.IN_DEVOLUTION_PROCESS, date: CustomDateUtils.sumDays(new Date(), -12), description: "Cartão em processo de devolução"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.AWAITING_WITHDRAWAL_AT_DELIVERY_AGENCY, date: CustomDateUtils.sumDays(new Date(), -11), description: "Aguardando retirada em agência correios"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.CHANGED_ADDRESS, date: CustomDateUtils.sumDays(new Date(), -10), description: "Endereço alterado"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.RESEND_REQUESTED, date: CustomDateUtils.sumDays(new Date(), -9), description: "Solicitado reenvio do cartão"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.CARD_RESEND, date: CustomDateUtils.sumDays(new Date(), -8), description: "Cartão reenviado"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.NEW_ROUTE_SCHEDULED, date: CustomDateUtils.sumDays(new Date(), -7), description: "Nova programação de roteiro"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.IN_ROUTE_TO_DELIVERY, date: CustomDateUtils.sumDays(new Date(), -6), description: "Cartão em rota para entrega"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.IN_TRANSFER_TO_DESTINATION, date: CustomDateUtils.sumDays(new Date(), -5), description: "Em transferência para local de destino"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.LOST_OR_MISPLACED, date: CustomDateUtils.sumDays(new Date(), -4), description: "Extravio ou roubo do cartão"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.IN_CUSTODY_AWAITING_CUSTOMER_CONTACT, date: CustomDateUtils.sumDays(new Date(), -3), description: "Cartão em custódia aguardando contato com o cliente"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.RECEIVED_BY_DEVOLUTION_MATRIX, date: CustomDateUtils.sumDays(new Date(), -2), description: "Cartão recebido na matriz de devolução"]))
        deliveryTrackDto.events.add(new DeliveryTrackEventDTO([eventType: DeliveryTrackEventType.DELIVERED, date: CustomDateUtils.sumDays(new Date(), -1), description: "Entregue"]))

        return deliveryTrackDto
    }
}
