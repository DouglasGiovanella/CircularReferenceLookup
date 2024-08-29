package com.asaas.service.mundipagg

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.integration.mundipagg.api.CancelSaleRequestManager
import com.asaas.integration.mundipagg.api.CardIdManager
import com.asaas.integration.mundipagg.api.ChargeManager
import com.asaas.integration.mundipagg.api.CreateSaleRequestManager
import com.asaas.integration.mundipagg.api.OrderManager
import com.asaas.integration.mundipagg.objects.CreateSaleRequest
import com.asaas.log.AsaasLogger
import com.asaas.mundipagg.CreateSaleRequestBuilder

import grails.transaction.Transactional
import grails.util.Environment

import org.apache.commons.lang.RandomStringUtils

@Transactional
class MundiPaggRESTService {

    private final Boolean USE_NEW_API = true

    def creditCardAuthorizationInfoService
	def creditCardBlackListService
    def creditCardTransactionLogService

	public Map authorize(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
        if (USE_NEW_API) return createOrderRequest(installment, payment, creditCard, billingInfo, false, isFallback)
        return createSaleRequest(installment, payment, creditCard, billingInfo, false, isFallback)
	}

	public Map capture(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
        if (USE_NEW_API) {
            if (billingInfo && !billingInfo.creditCardInfo.buildToken().startsWith("card_") && !billingInfo.creditCardInfo.buildToken().startsWith("token_")) {
                return createSaleRequest(installment, payment, creditCard, billingInfo, true, isFallback)
            }

            return createOrderRequest(installment, payment, creditCard, billingInfo, true, isFallback)
        }

        return createSaleRequest(installment, payment, creditCard, billingInfo, true, isFallback)
	}

    public Map getInstantBuyKey(CreditCard creditCard, Payment payment, Boolean isFallback) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return [success: true, instantBuyKey: UUID.randomUUID()]

        if (USE_NEW_API && !CustomerParameter.getValue(payment.customerAccount.provider, CustomerParameterName.USE_MUNDIPAGG_OLD_API_ON_TOKENIZATION)) return getCardId(creditCard, payment.customerAccount, isFallback)

		CreateSaleRequestBuilder createSaleRequestBuilder = new CreateSaleRequestBuilder(null, payment, creditCard, null, false)

		CreateSaleRequest createSaleRequest = createSaleRequestBuilder.execute()

		CreateSaleRequestManager createSaleRequestManager = new CreateSaleRequestManager(createSaleRequest)

		try {
			createSaleRequestManager.execute()

            Map tokenizationInfoMap = [:]
            tokenizationInfoMap.event = CreditCardTransactionEvent.TOKENIZE
            tokenizationInfoMap.responseJson = createSaleRequestManager.apiResponseJson
            tokenizationInfoMap.httpStatus = createSaleRequestManager.httpStatus
            tokenizationInfoMap.providerId = payment.customerAccount.provider.id
            tokenizationInfoMap.customerAccountId = payment.customerAccount.id
            tokenizationInfoMap.gateway = CreditCardGateway.MUNDIPAGG
            tokenizationInfoMap.creditCardBin = creditCard.buildBin()
            tokenizationInfoMap.fallback = isFallback

            creditCardTransactionLogService.save(tokenizationInfoMap)
		} catch (Exception e) {
            AsaasLogger.error("Erro ao CreateSaleRequest (tokenização) na Mundipagg", e)
			return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
		}

		if (!createSaleRequestManager.success) {
			return [success: false, errorMessage: "Transação não autorizada."]
		} else {
			return [success: true, instantBuyKey: createSaleRequestManager.instantBuyKey]
		}
	}

	public Map refund(String orderKey) throws Exception {
        AsaasLogger.info("Refunding transaction >> orderKey [${orderKey}]")
        if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return [success: true]

		if (orderKey.startsWith("ch_")) {
			return cancelCharge(orderKey)
		}

		try {
			CancelSaleRequestManager cancelSaleRequestManager = new CancelSaleRequestManager(orderKey)
			cancelSaleRequestManager.execute()
			return [success: cancelSaleRequestManager.success]
		} catch (Exception e) {
			AsaasLogger.error("Erro ao executar CancelSaleRequest na Mundipagg", e)
			return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
		}
	}

    private Map createSaleRequest(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean capture, Boolean isFallback) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return buildSimulationAuthorizationResponse(payment, installment ? installment.getRemainingValue() : payment.value, isFallback)

		CreateSaleRequestBuilder createSaleRequestBuilder = new CreateSaleRequestBuilder(installment, payment, creditCard, billingInfo, capture)
		CreateSaleRequest createSaleRequest = createSaleRequestBuilder.execute()

		CreateSaleRequestManager createSaleRequestManager = new CreateSaleRequestManager(createSaleRequest)

		try {
			createSaleRequestManager.execute()
		} catch (Exception e) {
			AsaasLogger.error("Erro ao executar CreateSaleRequest na Mundipagg", e)
			return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
		}

        Map responseMap = [:]
        CustomerAccount customerAccount = installment ? installment.customerAccount : payment.customerAccount
        CreditCardTransactionEvent creditCardTransactionEvent = capture ? CreditCardTransactionEvent.CAPTURE : CreditCardTransactionEvent.AUTHORIZATION

        Map gatewayInfoMap = createSaleRequestManager.properties

        if (billingInfo) {
            gatewayInfoMap.billingInfoId = billingInfo.id
            gatewayInfoMap.creditCardBin = billingInfo.creditCardInfo.bin
        } else {
            gatewayInfoMap.billingInfoId = creditCard.billingInfo ? creditCard.billingInfo.id : null
            gatewayInfoMap.creditCardBin = creditCard.buildBin()
        }

        gatewayInfoMap.softDescriptor = createSaleRequest.creditCardTransaction.options.softDescriptorText
        gatewayInfoMap.fallback = isFallback

		Long creditCardTransactionLogId = creditCardTransactionLogService.save(customerAccount.provider, customerAccount, payment, creditCardTransactionEvent, createSaleRequestManager.success, gatewayInfoMap)

        responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])
		responseMap.put("acquirer", CreditCardAcquirer.CIELO)

		if (!createSaleRequestManager.success) {
			responseMap.put("success", false)
            responseMap.put("message", createSaleRequestManager.getMessage())
            responseMap.put("errorMessage", createSaleRequestManager.getMessage())
			responseMap.put("acquirerReturnCode", createSaleRequestManager.getAcquirerReturnCode())

            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.parse(responseMap.acquirer), creditCard, billingInfo, createSaleRequestManager.getMessage(), responseMap.acquirerReturnCode)
            responseMap.put("blockedOnBlackList", blockedOnBlackList)
        } else {
			responseMap.put("success", true)
			responseMap.put("requestKey", createSaleRequestManager.requestKey)
			responseMap.put("orderKey", createSaleRequestManager.orderKey)
			responseMap.put("transactionKey", createSaleRequestManager.transactionKey)
			responseMap.put("transactionIdentifier", createSaleRequestManager.transactionIdentifier)
			responseMap.put("uniqueSequentialNumber", createSaleRequestManager.uniqueSequentialNumber)
			responseMap.put("authorizationCode", createSaleRequestManager.getAuthorizationCode())
			responseMap.put("instantBuyKey", createSaleRequestManager.instantBuyKey)
			responseMap.put("transactionReference", createSaleRequest.creditCardTransaction.transactionReference)
			responseMap.put("amountInCents", createSaleRequestManager.amountInCents)
			responseMap.put("gateway", CreditCardGateway.MUNDIPAGG)
			responseMap.put("message", createSaleRequestManager.getMessage())

            creditCardAuthorizationInfoService.save(payment, installment, responseMap.transactionReference, responseMap.transactionIdentifier, responseMap.amountInCents, billingInfo, creditCard, responseMap.gateway, responseMap.authorizationCode)
		}

		return responseMap
    }

	private Map buildSimulationAuthorizationResponse(Payment payment, Double value, Boolean isFallback) {
		Map responseMap = [:]
        Boolean isErrorSimulate = false

        if (isErrorSimulate) {
            Boolean isBlackListSimulate = false
            String refuseMessage = isBlackListSimulate ? "Refused | Restricted Card" : "Cartão sem saldo"

            CreditCard creditCard = new CreditCard([holderName: "SILVIO SANTOS",
                                                    number: "4444306219370001",
                                                    expiryMonth: "01",
                                                    expiryYear: "30",
                                                    ccv: "111",
                                                    lastDigits: "0001",
                                                    brand: CreditCardBrand.VISA,
                                                    gateway: CreditCardGateway.MUNDIPAGG])

            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, null, refuseMessage, null)

            responseMap.put("blockedOnBlackList", blockedOnBlackList)
            responseMap.put("success", false)
            responseMap.put("message", refuseMessage)
            responseMap.put("errorMessage", refuseMessage)
        } else {
            responseMap.put("success", true)
            responseMap.put("requestKey", UUID.randomUUID().toString())
            responseMap.put("orderKey", UUID.randomUUID().toString())
            responseMap.put("transactionKey", UUID.randomUUID().toString())
            responseMap.put("transactionIdentifier", UUID.randomUUID().toString())
            responseMap.put("uniqueSequentialNumber", RandomStringUtils.randomNumeric(8))
            responseMap.put("instantBuyKey", UUID.randomUUID().toString())
            responseMap.put("transactionReference", UUID.randomUUID().toString())
            responseMap.put("amountInCents", (value * 100).round())
            responseMap.put("acquirer", "Cielo")
            responseMap.put("gateway", CreditCardGateway.MUNDIPAGG)
            responseMap.put("customerToken", UUID.randomUUID().toString())
        }

        responseMap.put("fallback", isFallback)

        Long creditCardTransactionLogId = creditCardTransactionLogService.save(payment.provider, payment.customerAccount, payment, CreditCardTransactionEvent.AUTHORIZATION, true, responseMap)

        responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])

		return responseMap
	}

    private Map createOrderRequest(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean capture, Boolean isFallback) {
        if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return buildSimulationAuthorizationResponse(payment, installment ? installment.getRemainingValue() : payment.value, isFallback)

        OrderManager orderManager = new OrderManager(installment, payment, creditCard, billingInfo, capture)

        try {
			orderManager.execute()
		} catch (Exception e) {
            AsaasLogger.error("Erro ao executar createOrderRequest na Mundipagg", e)
			return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
		}

        Map gatewayInfoMap = [
            amountInCents: orderManager.getOrderAmountInCents(),
            orderKey: orderManager.getChargeId(),
            transactionKey: orderManager.getTransactionId(),
            transactionIdentifier: orderManager.getAcquirerTid(),
            message: orderManager.getAcquirerMessage(),
            apiResponseJson: orderManager.responseJson,
            httpStatus: orderManager.httpStatus,
            gateway: CreditCardGateway.MUNDIPAGG,
            softDescriptor: orderManager.requestMap.payments[0].credit_card.statement_descriptor,
            fallback: isFallback
        ]

        if (billingInfo) {
            gatewayInfoMap.billingInfoId = billingInfo.id
            gatewayInfoMap.creditCardBin = billingInfo.creditCardInfo.bin
        } else {
            gatewayInfoMap.billingInfoId = creditCard.billingInfo ? creditCard.billingInfo.id : null
            gatewayInfoMap.creditCardBin = creditCard.buildBin()
        }

        CustomerAccount customerAccount = installment ? installment.customerAccount : payment.customerAccount
        Long creditCardTransactionLogId = creditCardTransactionLogService.save(customerAccount.provider, customerAccount, payment, capture ? CreditCardTransactionEvent.CAPTURE : CreditCardTransactionEvent.AUTHORIZATION, orderManager.success, gatewayInfoMap)

        Map responseMap = [:]
        responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])
		responseMap.put("acquirer", orderManager.getAcquirerName())

        if (!orderManager.success) {
			responseMap.put("success", false)
            responseMap.put("message", orderManager.getAcquirerMessage())
            responseMap.put("errorMessage", orderManager.getAcquirerMessage())
			responseMap.put("acquirerReturnCode", orderManager.getAcquirerReturnCode())

            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.parse(responseMap.acquirer), creditCard, billingInfo, responseMap.errorMessage, responseMap.acquirerReturnCode)
            responseMap.put("blockedOnBlackList", blockedOnBlackList)
        } else {
			responseMap.put("success", true)
			responseMap.put("orderKey", orderManager.getChargeId())
            responseMap.put("transactionKey", orderManager.getTransactionId())
			responseMap.put("transactionIdentifier", orderManager.getAcquirerTid())
			responseMap.put("instantBuyKey", orderManager.getCardId())
			responseMap.put("transactionReference", orderManager.getOrderCode())
            responseMap.put("uniqueSequentialNumber", orderManager.getAcquirerNsu())
            responseMap.put("authorizationCode", orderManager.getAcquirerAuthCode())
			responseMap.put("amountInCents", orderManager.getOrderAmountInCents())
			responseMap.put("gateway", CreditCardGateway.MUNDIPAGG)
			responseMap.put("message", orderManager.getAcquirerMessage())
            responseMap.put("customerToken", orderManager.getCustomerId())

            creditCardAuthorizationInfoService.save(payment, installment, responseMap.transactionReference, responseMap.transactionIdentifier, responseMap.amountInCents, billingInfo, creditCard, responseMap.gateway, responseMap.authorizationCode)
		}

        return responseMap
    }

    private Map getCardId(CreditCard creditCard, CustomerAccount customerAccount, Boolean isFallback) {
        CardIdManager cardIdManager = new CardIdManager(creditCard, customerAccount)
        Long creditCardTransactionLogId

        try {
			cardIdManager.execute()

            Map tokenizationInfoMap = [:]
            tokenizationInfoMap.event = CreditCardTransactionEvent.TOKENIZE
            tokenizationInfoMap.responseJson = cardIdManager.cardResponseMap?.toString() ?: cardIdManager.customerResponseMap?.toString()
            tokenizationInfoMap.httpStatus = cardIdManager.httpStatus
            tokenizationInfoMap.providerId = customerAccount.provider.id
            tokenizationInfoMap.customerAccountId = customerAccount.id
            tokenizationInfoMap.gateway = CreditCardGateway.MUNDIPAGG
            tokenizationInfoMap.creditCardBin = creditCard.buildBin()
            tokenizationInfoMap.fallback = isFallback

            creditCardTransactionLogId = creditCardTransactionLogService.save(tokenizationInfoMap)
		} catch (Exception e) {
			AsaasLogger.error("Erro ao executar tokenização (cardId) na Mundipagg", e)
			return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
		}

        if (!cardIdManager.success) {
            return [success: false, errorMessage: "Transação não autorizada.", creditCardTransactionLogIdList: [creditCardTransactionLogId]]
        } else {
            return [success: true, instantBuyKey: cardIdManager.getCardId(), customerToken: cardIdManager.getCustomerId(), creditCardTransactionLogIdList: [creditCardTransactionLogId]]
        }
    }

    private Map cancelCharge(String chargeId) {
        try {
			ChargeManager chargeManager = new ChargeManager(chargeId)
			chargeManager.cancel()
			return [success: chargeManager.success]
		} catch (Exception e) {
			AsaasLogger.error("Erro ao executar cancelCharge (estorno) na Mundipagg", e)
			return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
		}
    }

}
