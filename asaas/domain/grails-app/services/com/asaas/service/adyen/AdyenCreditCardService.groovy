package com.asaas.service.adyen

import com.asaas.creditcard.*
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.integration.adyen.creditcard.api.ApiRequestManager
import com.asaas.integration.adyen.creditcard.builders.AuthoriseRequestBuilder
import com.asaas.integration.adyen.creditcard.builders.CaptureRequestBuilder
import com.asaas.integration.adyen.creditcard.builders.RefundRequestBuilder
import com.asaas.integration.adyen.creditcard.dto.refund.RefundRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.MoneyUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AdyenCreditCardService {

	static final String AUTHORIZED_RESULT_CODE = "Authorised"

	static final String CAPTURED_RESULT_CODE = "[capture-received]"

	static final String REFUNDED_RESULT_CODE = "[cancelOrRefund-received]"

	static final String PARTIAL_REFUNDED_RESULT_CODE = "[refund-received]"

    def creditCardBlackListService
	def creditCardTransactionLogService
    def creditCardAuthorizationInfoService

    public Map authorize(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(installment, payment, creditCard, billingInfo)

        Map result = authorizeTransaction(creditCardTransactionVO, isFallback)

        if (!result.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.ADYEN, creditCard, billingInfo, result.message, result.acquirerReturnCode)
            result.blockedOnBlackList = blockedOnBlackList

            return result
        }

        result.creditCardAuthorizationInfo = creditCardAuthorizationInfoService.save(payment, installment, result.transactionReference, result.transactionIdentifier, result.amountInCents, billingInfo, creditCard, result.gateway, result.authorizationCode)

        return result
    }

	public Map capture(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
	    Map authorizationResponse = authorize(installment, payment, creditCard, billingInfo, isFallback)
        if (!authorizationResponse.success) return authorizationResponse

        Map captureResponse = capturePreviouslyAuthorized(authorizationResponse.creditCardAuthorizationInfo, false, authorizationResponse, isFallback)
        authorizationResponse.success = captureResponse.success

        return authorizationResponse
	}

	public Map capturePreviouslyAuthorized(CreditCardAuthorizationInfo authorizationInfo, Boolean preAuth, Map authorizationResponseMap, Boolean isFallback) {
		if (!AsaasConfig.getInstance().useMundiPaggProduction) return buildSimulationCaptureResponse(authorizationInfo)
        Map responseMap = [:]

        Payment payment = authorizationInfo.payment

		CaptureRequestBuilder captureRequestBuilder = new CaptureRequestBuilder()
		captureRequestBuilder.payment = payment
		captureRequestBuilder.installment = authorizationInfo.installment
		captureRequestBuilder.originalReference = authorizationInfo.transactionIdentifier

		captureRequestBuilder.execute()

		ApiRequestManager apiRequestManager = new ApiRequestManager(captureRequestBuilder.requestMap)
		apiRequestManager.capture()

		Boolean success = (!apiRequestManager.error && apiRequestManager.responseMap.response == CAPTURED_RESULT_CODE)

		Map gatewayInfo = [:]
		gatewayInfo.message = apiRequestManager.responseMap?.response
		gatewayInfo.transactionIdentifier = captureRequestBuilder.originalReference
		gatewayInfo.httpStatus = apiRequestManager.responseHttpStatus
		gatewayInfo.apiResponseJson = apiRequestManager.responseBody
        gatewayInfo.gateway = CreditCardGateway.ADYEN
        gatewayInfo.billingInfoId = authorizationInfo.billingInfoId
        gatewayInfo.creditCardBin = authorizationInfo.creditCardBin
        gatewayInfo.fallback = isFallback

        Long creditCardTransactionLogId = creditCardTransactionLogService.save(payment.provider, payment.customerAccount, payment, CreditCardTransactionEvent.CAPTURE, success, gatewayInfo)

        responseMap.creditCardTransactionLogIdList = authorizationResponseMap?.creditCardTransactionLogIdList

        if (!responseMap.creditCardTransactionLogIdList) responseMap.creditCardTransactionLogIdList = []

        responseMap.creditCardTransactionLogIdList.add(creditCardTransactionLogId)

        responseMap.put("success", success)

        if (preAuth) {
            if (success) {
                responseMap.put("message", apiRequestManager.responseMap.resultCode)
                responseMap.put("transactionIdentifier", authorizationInfo.transactionIdentifier)
                if (payment.billingInfo) responseMap.put("instantBuyKey", payment.billingInfo.creditCardInfo.buildToken())
                responseMap.put("amountInCents", authorizationInfo.amountInCents)
                responseMap.put("transactionReference", authorizationInfo.transactionReference)
                responseMap.put("acquirer", CreditCardAcquirer.ADYEN)
                responseMap.put("gateway", CreditCardGateway.ADYEN)
                responseMap.put("customerToken", buildCustomerToken(payment.billingInfo, payment, authorizationInfo.installment))
            } else {
                responseMap.put("message", apiRequestManager.responseMap?.message)
                responseMap.put("acquirerReturnCode", apiRequestManager.responseMap?.errorCode)

                Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.ADYEN, null, payment.billingInfo, null, apiRequestManager.responseMap?.errorCode)
                responseMap.put("blockedOnBlackList", blockedOnBlackList)
            }
        }

        return responseMap
	}

    public Map refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier) {
        if (!AsaasConfig.getInstance().useMundiPaggProduction) return [success: true]

    	RefundRequestBuilder refundRequestBuilder = new RefundRequestBuilder(transactionIdentifier)
    	refundRequestBuilder.execute()

    	ApiRequestManager apiRequestManager = new ApiRequestManager(refundRequestBuilder.requestMap)
		apiRequestManager.refund()

		Boolean success = (!apiRequestManager.error && apiRequestManager.responseMap.response == REFUNDED_RESULT_CODE)

        Map gatewayInfo = [:]
        gatewayInfo.message = apiRequestManager.responseMap?.response
        gatewayInfo.transactionIdentifier = transactionIdentifier
        gatewayInfo.httpStatus = apiRequestManager.responseHttpStatus
        gatewayInfo.apiResponseJson = apiRequestManager.responseBody
        gatewayInfo.gateway = CreditCardGateway.ADYEN

        creditCardTransactionLogService.save(customer, customerAccount, null, CreditCardTransactionEvent.REFUND, success, gatewayInfo)

		return [success: success]
    }

    public Map refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, Long amountInCents) {
        if (!AsaasConfig.getInstance().useMundiPaggProduction) return [success: true]

        RefundRequestDTO refundRequestDTO = new RefundRequestDTO(transactionIdentifier, amountInCents)

        ApiRequestManager apiRequestManager = new ApiRequestManager(refundRequestDTO.properties)
        apiRequestManager.partialRefund()

        Boolean refundSuccess = (!apiRequestManager.error && apiRequestManager.responseMap.response == PARTIAL_REFUNDED_RESULT_CODE)

        Map gatewayInfo = [:]
        gatewayInfo.message = apiRequestManager.responseMap?.response
        gatewayInfo.transactionIdentifier = transactionIdentifier
        gatewayInfo.httpStatus = apiRequestManager.responseHttpStatus
        gatewayInfo.apiResponseJson = apiRequestManager.responseBody
        gatewayInfo.amountInCents = refundRequestDTO.modificationAmount.value
        gatewayInfo.gateway = CreditCardGateway.ADYEN

        creditCardTransactionLogService.save(customer, customerAccount, null, CreditCardTransactionEvent.REFUND, refundSuccess, gatewayInfo)

        return [success: refundSuccess]
    }

    public Map getInstantBuyKey(CreditCard creditCard, CustomerAccount customerAccount, Boolean isFallback) {
        if (!AsaasConfig.getInstance().useMundiPaggProduction) return [success: true, instantBuyKey: UUID.randomUUID(), customerToken: customerAccount.id, creditCardTransactionLogIdList: null]

        Map authorizationResponse

        try {
            final BigDecimal authorizationValue = 0
            CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(customerAccount, creditCard, authorizationValue)

            authorizationResponse = authorizeTransaction(creditCardTransactionVO, isFallback)
        } catch (Exception exception) {
            AsaasLogger.error("Erro na tokenização da Adyen", exception)
            return [success: false, errorMessage: "Serviço temporariamente indisponível. Tente novamente mais tarde."]
        }

        if (!authorizationResponse.success) {
            return [success: false, errorMessage: "Transação não autorizada.", creditCardTransactionLogIdList: authorizationResponse.creditCardTransactionLogIdList, acquirerReturnCode: authorizationResponse.acquirerReturnCode]
        } else {
            return [success: true, instantBuyKey: authorizationResponse.instantBuyKey, customerToken: customerAccount.id, creditCardTransactionLogIdList: authorizationResponse.creditCardTransactionLogIdList]
        }
    }

    private Map authorizeTransaction(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        if (!AsaasConfig.getInstance().useMundiPaggProduction) {
            return buildSimulationAuthorizationResponse(creditCardTransactionVO, isFallback)
        }

        AuthoriseRequestBuilder authoriseRequestBuilder = new AuthoriseRequestBuilder()
        authoriseRequestBuilder.creditCardTransactionVO = creditCardTransactionVO
        authoriseRequestBuilder.canSendCardOnFileCodeOnCreditCardTransaction = creditCardTransactionVO.creditCard?.billingInfo && CustomerParameter.getValue(creditCardTransactionVO.customer, CustomerParameterName.SEND_CARD_ON_FILE_CODE_ON_CREDIT_CARD_TRANSACTION)
        authoriseRequestBuilder.execute()

        ApiRequestManager apiRequestManager = new ApiRequestManager(authoriseRequestBuilder.requestMap)
        apiRequestManager.authorize()

        Boolean success = (!apiRequestManager.error && apiRequestManager.responseMap.resultCode == AUTHORIZED_RESULT_CODE)
        Map responseMap = [:]

        String responseMessage = ""
        String acquirerReturnCode = null
        if (apiRequestManager.responseMap) {
            Map gatewayInfo = [:]

            if (success) {
                responseMessage = apiRequestManager.responseMap.resultCode
            } else {
                if (apiRequestManager.responseMap.message) {
                    responseMessage = apiRequestManager.responseMap.message
                } else {
                    responseMessage = "${apiRequestManager.responseMap.resultCode} | ${apiRequestManager.responseMap.refusalReason}"
                }
            }

            acquirerReturnCode = apiRequestManager.responseMap.additionalData?.refusalReasonRaw?.tokenize(" ")?.first()
            acquirerReturnCode = Utils.removeNonNumeric(acquirerReturnCode)

            gatewayInfo.transactionIdentifier = apiRequestManager.responseMap.pspReference
            gatewayInfo.httpStatus = apiRequestManager.responseHttpStatus
            gatewayInfo.apiResponseJson = apiRequestManager.responseBody
            gatewayInfo.amountInCents = authoriseRequestBuilder.requestMap.amount.value
            gatewayInfo.gateway = CreditCardGateway.ADYEN
            gatewayInfo.softDescriptor = authoriseRequestBuilder.requestMap.shopperStatement
            gatewayInfo.message = responseMessage
            gatewayInfo.acquirerReturnCode = acquirerReturnCode
            gatewayInfo.fallback = isFallback

            if (creditCardTransactionVO.billingInfo) {
                gatewayInfo.billingInfoId = creditCardTransactionVO.billingInfo.id
                gatewayInfo.creditCardBin = creditCardTransactionVO.billingInfo.creditCardInfo.bin
            } else {
                gatewayInfo.billingInfoId = creditCardTransactionVO.creditCard.billingInfo ? creditCardTransactionVO.creditCard.billingInfo.id : null
                gatewayInfo.creditCardBin = creditCardTransactionVO.creditCard.buildBin()
            }

            Long creditCardTransactionLogId = creditCardTransactionLogService.save(creditCardTransactionVO.customer, creditCardTransactionVO.customerAccount, creditCardTransactionVO.payment, CreditCardTransactionEvent.AUTHORIZATION, success, gatewayInfo)

            responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])
        }

        responseMap.put("gateway", CreditCardGateway.ADYEN)
        responseMap.put("acquirer", CreditCardAcquirer.ADYEN)

        if (success) {
            responseMap.put("success", true)
            responseMap.put("message", responseMessage)
            responseMap.put("transactionIdentifier", apiRequestManager.responseMap.pspReference)
            responseMap.put("instantBuyKey", apiRequestManager.responseMap.additionalData?."recurring.recurringDetailReference")
            responseMap.put("amountInCents", authoriseRequestBuilder.requestMap.amount.value)
            responseMap.put("transactionReference", authoriseRequestBuilder.requestMap.reference)
            responseMap.put("customerToken", creditCardTransactionVO.billingInfo?.customerAccount?.id ?: creditCardTransactionVO.customerAccount.id)
            responseMap.put("authorizationCode", apiRequestManager.responseMap.additionalData?.authCode)
        } else {
            responseMap.put("success", false)
            responseMap.put("message", responseMessage)
            responseMap.put("acquirerReturnCode", acquirerReturnCode)
        }

        return responseMap
    }

    private Map buildSimulationAuthorizationResponse(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        Map responseMap = [:]
        Boolean isErrorSimulate = false

        if (isErrorSimulate) {
            Boolean isBlackListSimulate = false
            String refuseMessage = isBlackListSimulate ? "Refused | Restricted Card" : "Cartão sem saldo"

            responseMap.put("success", false)
            responseMap.put("message", refuseMessage)
            responseMap.put("errorMessage", refuseMessage)
            responseMap.put("gateway", CreditCardGateway.ADYEN)
        } else {
            responseMap.put("success", true)
            responseMap.put("transactionIdentifier", UUID.randomUUID().toString())
            responseMap.put("instantBuyKey", UUID.randomUUID().toString())
            responseMap.put("amountInCents", MoneyUtils.valueInCents(creditCardTransactionVO.value))
            responseMap.put("transactionReference", UUID.randomUUID().toString())
            responseMap.put("acquirer", CreditCardAcquirer.ADYEN)
            responseMap.put("gateway", CreditCardGateway.ADYEN)
            responseMap.put("customerToken", creditCardTransactionVO.billingInfo?.customerAccount?.id ?: creditCardTransactionVO.customerAccount.id)
        }

        responseMap.put("fallback", isFallback)

        Long creditCardTransactionLogId = creditCardTransactionLogService.save(creditCardTransactionVO.customer, creditCardTransactionVO.customerAccount, creditCardTransactionVO.payment, CreditCardTransactionEvent.AUTHORIZATION, true, responseMap)

        responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])

        return responseMap
    }

    private Map buildSimulationCaptureResponse(CreditCardAuthorizationInfo creditCardAuthorizationInfo) {
        Map responseMap = [:]

        responseMap.put("success", true)
        responseMap.put("transactionIdentifier", creditCardAuthorizationInfo.transactionIdentifier)
        responseMap.put("amountInCents", creditCardAuthorizationInfo.amountInCents)
        responseMap.put("transactionReference", creditCardAuthorizationInfo.transactionReference)
        responseMap.put("acquirer", CreditCardAcquirer.ADYEN)
        responseMap.put("gateway", CreditCardGateway.ADYEN)
        responseMap.put("customerToken", creditCardAuthorizationInfo.payment.customerAccount?.id)

        return responseMap
    }

    private Long buildCustomerToken(BillingInfo billingInfo, Payment payment, Installment installment) {
        CustomerAccount customerAccount
        if (billingInfo) {
            customerAccount = billingInfo.customerAccount
        } else {
            customerAccount = payment?.customerAccount ?: installment.customerAccount
        }

        return customerAccount.id
    }
}
