package com.asaas.service.api

import com.asaas.api.ApiAsaasCardParser
import com.asaas.api.ApiAsaasErrorParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiMobileUtils
import com.asaas.asaascard.AsaasCardBrand
import com.asaas.asaascard.AsaasCardStatus
import com.asaas.asaascard.AsaasCardType
import com.asaas.asaascard.validator.AsaasCardCustomerRequirementsValidator
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardListAdapter
import com.asaas.integration.bifrost.adapter.cardfinancialtransaction.AsaasCardFinancialTransactionListAdapter
import com.asaas.integration.bifrost.adapter.creditcardeventhistory.AsaasCreditCardEventHistoryListAdapter
import com.asaas.integration.bifrost.adapter.asaascard.AsaasCardAdapter
import com.asaas.user.UserUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ApiAsaasCardService extends ApiBaseService {

	def apiResponseBuilderService
    def asaasCardAgreementService
    def asaasCardHolderService
	def asaasCardService
    def asaasCardTransactionService
    def bifrostCardService
    def bifrostChargebackService
    def criticalActionService
    def productPromotionService

	def show(params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiAsaasCardParser.parseRequestParams(params)
        AsaasCardAdapter asaasCardAdapter = asaasCardService.findWithExternalCardSync(fields.id, customer.id)

        Map response = [:]
        if (asaasCardAdapter.asaasCard.type.isCredit()) {
            response = ApiAsaasCardParser.buildCreditCardAdapterResponseItem(asaasCardAdapter)
        } else {
            response = ApiAsaasCardParser.buildCardAdapterResponseItem(asaasCardAdapter, [buildCriticalAction: true])
        }

		return apiResponseBuilderService.buildSuccess(response)
	}

	def list(params) {
		Customer customer = getProviderInstance(params)

        Map filterMap = ApiAsaasCardParser.parseRequestFilters(params)

        AsaasCardListAdapter asaasCardListAdapter
        try {
            asaasCardListAdapter = asaasCardService.listExternalCards(customer.id, filterMap)
        } catch (RuntimeException exception) {
            return apiResponseBuilderService.buildServiceUnavailable(Utils.getMessageProperty("asaasCard.bifrostConnection.error"))
        }

		List<Map> responseItems = asaasCardListAdapter.asaasCardList.collect { asaasCardAdapter -> ApiAsaasCardParser.buildCardAdapterResponseItem(asaasCardAdapter, [:]) }
		List<Map> extraData = []

		if (ApiMobileUtils.isMobileAppRequest()) {
            Map customerData = [:]
            customerData.isEligibleForMigrateAsaasCardToCredit = productPromotionService.isEligibleForMigrateAsaasCardToCredit(customer)
            customerData.isEligibleForRequestAsaasCreditCard = productPromotionService.isEligibleForRequestAsaasCreditCard(customer)

            extraData << customerData

            Map cardsTypeInfo = [:]
            cardsTypeInfo.hasCreditCard = AsaasCard.query([customer: customer, exists: true, type: AsaasCardType.CREDIT]).get().asBoolean()

            extraData << cardsTypeInfo

            Map requestDenialReasons = [:]
            requestDenialReasons.creditCardDenialReasons = ApiAsaasErrorParser.buildResponseList(AsaasCardCustomerRequirementsValidator.validate(customer, AsaasCardType.CREDIT))
            requestDenialReasons.debitCardDenialReasons = ApiAsaasErrorParser.buildResponseList(AsaasCardCustomerRequirementsValidator.validate(customer, AsaasCardType.DEBIT))
            requestDenialReasons.prepaidCardDenialReasons = ApiAsaasErrorParser.buildResponseList(AsaasCardCustomerRequirementsValidator.validate(customer, AsaasCardType.PREPAID))

            extraData << requestDenialReasons
		}

		return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), asaasCardListAdapter.totalCount, extraData)
	}

	def save(params) {
		Map fields = ApiAsaasCardParser.parseRequestParams(params)

        if (!ApiMobileUtils.isMobileAppRequest() && !fields.type) throw new BusinessException("Informe o tipo do cartão")

		AsaasCard asaasCard = asaasCardService.save(getProviderInstance(params), fields)

		if (asaasCard.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(asaasCard)
		}

		return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
	}

	def syncWithExternalCard(params) {
		Map fields = ApiAsaasCardParser.parseRequestParams(params)

		AsaasCardAdapter asaasCardAdapter = asaasCardService.findWithExternalCardSync(fields.id, getProvider(params))

		if (asaasCardAdapter.asaasCard.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(asaasCardAdapter.asaasCard)
		}

		return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCardAdapter.asaasCard))
	}

	def activate(params) {
		if (!params.id) {
			return apiResponseBuilderService.buildError("required", "asaasCard", "id", ["Id"])
		}

		Map fields = ApiAsaasCardParser.parseRequestParams(params)

		AsaasCard asaasCard = asaasCardService.activate(fields.id, getProvider(params), fields)

		if (asaasCard.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(asaasCard)
		}

		return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
	}

	def block(params) {
		if (!params.id) {
			return apiResponseBuilderService.buildError("required", "asaasCard", "id", ["Id"])
		}

        Map fields = ApiAsaasCardParser.parseRequestParams(params)
		AsaasCard asaasCard = asaasCardService.block(fields.id, getProvider(params), true)

		if (asaasCard.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(asaasCard)
		}

		return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
	}

	def updateName(params) {
		if (!params.id) {
			return apiResponseBuilderService.buildError("required", "asaasCard", "id", ["Id"])
		}

        Map fields = ApiAsaasCardParser.parseRequestParams(params)
		AsaasCard asaasCard = asaasCardService.updateName(fields.id, getProvider(params), params.name)
		return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
	}

	def listStatements(params) {
		if (!params.id) return apiResponseBuilderService.buildError("required", "asaasCard", "id", ["Id"])

        Long customerId = getProvider(params)
        AsaasCard asaasCard = AsaasCard.find(params.id, customerId)

        Map filters = ApiAsaasCardParser.parseRequestStatementFilters(params)

        Map statement = [:]
        List<Map> responseItems = []
        AsaasCardFinancialTransactionListAdapter cardFinancialTransactionListAdapter

        cardFinancialTransactionListAdapter = asaasCardService.listAsaasCardFinancialTransaction(asaasCard.id, customerId, filters + [offset: getOffset(params), limit: getLimit(params)])

        if (cardFinancialTransactionListAdapter) {
            statement = [statementList: cardFinancialTransactionListAdapter.cardFinancialTransactionList, totalCount: cardFinancialTransactionListAdapter.totalCount]
            responseItems = statement.statementList.collect { ApiAsaasCardParser.buildCardFinancialTransactionResponseItem(it) }
        } else {
            statement.totalCount = 0
        }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), statement.totalCount)
	}

	public Map getTransactionWithExternalDetails(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.asaasCardId, getProvider(params))
        Map transactionWithExternalDetails = asaasCardTransactionService.getTransactionWithExternalDetails(asaasCard, Utils.toLong(params.externalId))

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildStatementResponseItem(asaasCard, transactionWithExternalDetails))
	}

    public Map getHolderInfoForCardRequest(Map params) {
        Map holderInfo = asaasCardHolderService.getHolderInfoIfNecessary(getProviderInstance(params))

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildHolderInfoForCardRequestResponseItem(holderInfo))
    }

    public Map requestActivateCardToken(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))
        CriticalActionGroup synchronousGroup = criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.ASAAS_CARD_ACTIVATE, asaasCard, null)

        return ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup)
    }

    public Map requestChangeCreditEnabledLimitToken(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))

        CriticalActionGroup synchronousGroup = asaasCardService.saveChangeCreditEnabledLimitCriticalActionGroup(asaasCard)

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup))
    }

    public Map requestChangePinCardToken(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))
        String authorizationMessage = "o código para alteração de senha do seu cartão ${asaasCard.getFormattedName()} é"

        CriticalActionGroup synchronousGroup = criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.ASAAS_CARD_CHANGE_PIN, asaasCard, authorizationMessage)

        return ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup)
    }

    public Map requestUnblockCardToken(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))
        String authorizationMessage = "o código para desbloquear seu cartão ${asaasCard.getFormattedName()} é"

        CriticalActionGroup synchronousGroup = criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.ASAAS_CARD_UNBLOCK, asaasCard, authorizationMessage)

        return ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup)
    }

    public Map requestUpdateDebitCardToCreditCardToken(Map params) {
        AsaasCard asaasCard = AsaasCard.query([customerId: getProvider(params), type: AsaasCardType.DEBIT, status: AsaasCardStatus.ACTIVE]).get()

        CriticalActionGroup synchronousGroup = criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.MIGRATE_ASAAS_CARD_TO_CREDIT, asaasCard, null)

		if (synchronousGroup.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(synchronousGroup)
		}

        return ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup)
    }

    public Map requestReissueToken(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))

        return ApiCriticalActionParser.buildGroupResponseItem(asaasCardService.saveReissueCriticalActionGroup(asaasCard))
    }

    public Map requestCreditCardRequestToken(Map params) {
        Customer customer = getProviderInstance(params)

        CriticalActionGroup synchronousGroup = asaasCardService.saveCreditCardRequestCriticalActionGroup(customer)
        Map responseMap = ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup)

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map changePin(Map params) {
        Map fields = ApiAsaasCardParser.parseChangePinRequestParams(params)

        Long asaasCardId = AsaasCard.query([column: "id", customerId: getProvider(params), publicId: params.id, includeWithReissue: true]).get()
        if (!asaasCardId) return apiResponseBuilderService.buildNotFoundItem()

        AsaasCard asaasCard = asaasCardService.changePin(asaasCardId, getProvider(params), fields)
        if (asaasCard.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(asaasCard)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
    }

    public Map unblock(Map params) {
        Map fields = ApiAsaasCardParser.parseRequestParams(params)
        AsaasCard asaasCard = asaasCardService.unblock(fields.id, getProvider(params), fields)

        if (asaasCard.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(asaasCard)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
    }

    public Map requestChargeback(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))
        Long externalId = Utils.toLong(params.externalId)
        Map transactionWithExternalDetails = asaasCardTransactionService.getTransactionWithExternalDetails(asaasCard, externalId)
        bifrostChargebackService.save(asaasCard, externalId, params.reason)

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildStatementResponseItem(asaasCard, transactionWithExternalDetails))
    }

    public Map buildAsaasCardPromotionBoxInfoMap(Customer customer) {
        Map promotionBoxInfo = [:]

        Boolean isEligibleForAsaasCardPromotionalBox = productPromotionService.isEligibleForAsaasCardPromotionalBox(customer)
        promotionBoxInfo.canDisplay = isEligibleForAsaasCardPromotionalBox

        if (isEligibleForAsaasCardPromotionalBox) {
            Map holderInfo = asaasCardHolderService.getHolderInfoIfNecessary(customer)
            promotionBoxInfo.holderName = holderInfo.name
            promotionBoxInfo.fourthLine = asaasCardService.getFourthLine(customer)
        }

        use (groovy.time.TimeCategory) {
            promotionBoxInfo.recurrenceInMilliseconds = ProductPromotion.ASAAS_CARD_PROMOTION_RECURRENCE_DAYS.days.toMilliseconds()
        }

        return promotionBoxInfo
    }

    public Map getRequestValidations(Map params) {
        Customer customer = getProviderInstance(params)

        BusinessValidation paysmartValidation = AsaasCardCustomerRequirementsValidator.validate(customer, AsaasCardType.DEBIT)
        BusinessValidation paysmartPrepaidValidation = AsaasCardCustomerRequirementsValidator.validate(customer, AsaasCardType.PREPAID)

        Map responseItem = [:]
        responseItem.eloDebitDeniedReasons = ApiAsaasErrorParser.buildResponseList(paysmartValidation)
        responseItem.eloDebitPrepaidDeniedReasons = ApiAsaasErrorParser.buildResponseList(paysmartPrepaidValidation)

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map getDeliveryTracking(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))

        List<Map> deliveryTrackingEvents = asaasCardService.getDeliveryTrackInfo(asaasCard)
        if (deliveryTrackingEvents == null) {
            return apiResponseBuilderService.buildErrorFrom("delivery_tracking_not_found", "Não foi possível encontrar informações referentes ao rastreio do cartão, por favor tente novamente.")
        }

        List<Map> responseItems = deliveryTrackingEvents.collect { ApiAsaasCardParser.buildDeliveryTrackingEventResponseItem(it) }

        return apiResponseBuilderService.buildSuccess([events: responseItems])
    }

    public Map validateLastDigits(Map params) {
        AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))
        bifrostCardService.validateLastDigits(asaasCard, params.lastDigits)

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map refundBalance(Map params) {
        if (!params.id) {
            return apiResponseBuilderService.buildError("required", "asaasCard", "id", ["Id"])
        }

        Map fields = ApiAsaasCardParser.parseRequestParams(params)
        AsaasCard asaasCard = asaasCardService.refundBalance(fields.id, getProvider(params))

        if (asaasCard.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(asaasCard)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
    }

    public Map getFees(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildFeeMap(customer, params.type))
    }

    public Map requestIndex(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildIndexConfigMap(customer))
    }

    public Map listEventHistory(Map params) {
        Customer customer = getProviderInstance(params)
        AsaasCard asaasCard = AsaasCard.find(params.id, customer.id)

        Map filters = ApiAsaasCardParser.parseCreditCardEventHistoryFilters(params)

        AsaasCreditCardEventHistoryListAdapter creditCardHistoryAdapter = asaasCardService.listCreditCardEventHistories(asaasCard.id, customer.id, filters)
        List<Map> historyItems = creditCardHistoryAdapter.creditCardEventHistoryList.collect { ApiAsaasCardParser.buildCreditCardEventHistoryResponseItem(it) }

        return apiResponseBuilderService.buildList(historyItems, getOffset(params), getLimit(params), creditCardHistoryAdapter.totalCount)
    }

    public Map getCurrentAgreement() {
        Map responseItem = [:]
        responseItem.currentAgreement = asaasCardAgreementService.getCurrentContractText(AsaasCardBrand.ELO)

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map updateDebitCardToCreditCard(Map params) {
        Map fields = ApiAsaasCardParser.parseUpdateDebitCardToCreditCardMap(params)
        AsaasCard asaasCard = asaasCardService.updateDebitCardToCreditCard(getProvider(params), UserUtils.getCurrentUser(), fields)

        if (asaasCard.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(asaasCard)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(asaasCard))
    }

    public Map reissue(Map params) {
        Map fields = ApiAsaasCardParser.parseRequestParams(params)

        Customer customer = getProviderInstance(fields)
        AsaasCard asaasCard = AsaasCard.find(fields.id, customer.id)

        AsaasCard newAsaasCard = asaasCardService.customerReissue(asaasCard, fields, customer)

        return apiResponseBuilderService.buildSuccess(ApiAsaasCardParser.buildResponseItem(newAsaasCard))
    }

    public Map updateConfig(Map params) {
        Map fields = ApiAsaasCardParser.parseUpdateConfig(params)

        Customer customer = getProviderInstance(fields)
        AsaasCard asaasCard = AsaasCard.find(fields.id, customer.id)

        asaasCardService.updateConfig(asaasCard, fields)

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map creditLimit(Map params) {
        BigDecimal limit = asaasCardService.getCustomerCreditLimit(getProvider(params))

        return apiResponseBuilderService.buildSuccess([limit: limit])
    }
}
