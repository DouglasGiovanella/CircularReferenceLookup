package com.asaas.service.adyen

import com.asaas.debitcard.DebitCard
import com.asaas.debitcard.DebitCardAcquirer
import com.asaas.debitcard.DebitCardBrand
import com.asaas.debitcard.DebitCardTransactionEvent
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.DebitCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.integration.adyen.debitcard.builders.RefundRequestBuilder
import com.asaas.integration.adyen.debitcard.api.ApiRequestManager
import com.asaas.integration.adyen.debitcard.builders.AuthoriseRequestBuilder
import com.asaas.integration.adyen.debitcard.builders.Authorise3dRequestBuilder
import com.asaas.integration.adyen.debitcard.builders.CaptureRequestBuilder

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

import grails.transaction.Transactional

@Transactional
class AdyenDebitCardService {

	static final String AUTHORIZED_RESULT_CODE = "Authorised"

	static final String CAPTURED_RESULT_CODE = "[capture-received]"

    static final String REDIRECT_SHOPPER_RESULT_CODE = "RedirectShopper"

	def debitCardTransactionLogService

	public Map authorize(Payment payment, DebitCard debitCard, BigDecimal transactionValue) {
		if (!DebitCardBrand.supportedBrands.contains(debitCard.getBrand())) throw new BusinessException("Bandeira do cartão de débito não suportada")

		AuthoriseRequestBuilder authoriseRequestBuilder = new AuthoriseRequestBuilder()
		authoriseRequestBuilder.payment = payment
		authoriseRequestBuilder.debitCard = debitCard
		authoriseRequestBuilder.transactionValue = transactionValue
        authoriseRequestBuilder.customer = payment.provider

		ServletWebRequest request = RequestContextHolder.getRequestAttributes()
		authoriseRequestBuilder.userAgent = request.getHeader("User-Agent")
		authoriseRequestBuilder.acceptHeader = request.getHeader("Accept")

		authoriseRequestBuilder.execute()

		ApiRequestManager apiRequestManager = new ApiRequestManager(authoriseRequestBuilder.requestMap)
		apiRequestManager.authorize()

		Boolean success = !apiRequestManager.error && [AdyenDebitCardService.AUTHORIZED_RESULT_CODE, AdyenDebitCardService.REDIRECT_SHOPPER_RESULT_CODE].contains(apiRequestManager.responseMap.resultCode)

        String transactionIdentifier = apiRequestManager.responseMap?.pspReference

		if (apiRequestManager.responseMap) debitCardTransactionLogService.save(payment, payment.provider, payment.customerAccount, DebitCardTransactionEvent.AUTHORIZATION, success, [message: apiRequestManager.responseMap.resultCode, transactionIdentifier: transactionIdentifier])

		if (!success) return [success: false]

		Map responseMap = [:]
		responseMap.put("success", true)
		responseMap.put("transactionIdentifier", transactionIdentifier)
		responseMap.put("amountInCents", authoriseRequestBuilder.requestMap.amount.value)
		responseMap.put("transactionReference", authoriseRequestBuilder.requestMap.reference)
		responseMap.put("acquirer", DebitCardAcquirer.ADYEN)
		responseMap.put("issuerUrl", apiRequestManager.responseMap.issuerUrl)
		responseMap.put("md", apiRequestManager.responseMap.md)
		responseMap.put("paRequest", apiRequestManager.responseMap.paRequest)
        responseMap.put("redirectShopper", apiRequestManager.responseMap.resultCode == AdyenDebitCardService.REDIRECT_SHOPPER_RESULT_CODE)

		return responseMap
	}

    public Map authorize3d(Payment payment, String md, String paResponse, String shopperIp) {
		Authorise3dRequestBuilder authorise3dRequestBuilder = new Authorise3dRequestBuilder()
		authorise3dRequestBuilder.payment = payment
		authorise3dRequestBuilder.md = md
		authorise3dRequestBuilder.paResponse = paResponse
        authorise3dRequestBuilder.shopperIp = shopperIp

		authorise3dRequestBuilder.execute()

		ApiRequestManager apiRequestManager = new ApiRequestManager(authorise3dRequestBuilder.requestMap)
		apiRequestManager.authorize3d()

		Boolean success = !apiRequestManager.error && apiRequestManager.responseMap.resultCode == AUTHORIZED_RESULT_CODE

        String transactionIdentifier = apiRequestManager.responseMap?.pspReference

        if (apiRequestManager.responseMap) debitCardTransactionLogService.save(payment, payment.provider, payment.customerAccount, DebitCardTransactionEvent.AUTHORIZATION_3D, success, [message: apiRequestManager.responseMap.resultCode, transactionIdentifier: transactionIdentifier])

		if (!success) return [success: false]

		Map responseMap = [:]
		responseMap.put("success", true)
		responseMap.put("transactionIdentifier", transactionIdentifier)
		responseMap.put("acquirer", DebitCardAcquirer.ADYEN)

		return responseMap
	}

	public Boolean capturePreviouslyAuthorized(DebitCardAuthorizationInfo authorizationInfo, BigDecimal transactionValue) {
		CaptureRequestBuilder captureRequestBuilder = new CaptureRequestBuilder()
		captureRequestBuilder.payment = authorizationInfo.payment
		captureRequestBuilder.originalReference = authorizationInfo.transactionIdentifier
		captureRequestBuilder.transactionValue = transactionValue

		captureRequestBuilder.execute()

		ApiRequestManager apiRequestManager = new ApiRequestManager(captureRequestBuilder.requestMap)
		apiRequestManager.capture()

		Boolean success = !apiRequestManager.error && apiRequestManager.responseMap.response == CAPTURED_RESULT_CODE

		debitCardTransactionLogService.save(authorizationInfo.payment, authorizationInfo.payment.provider, authorizationInfo.payment.customerAccount, DebitCardTransactionEvent.CAPTURE, success, [transactionIdentifier: authorizationInfo.transactionIdentifier])

		return success
	}

    public Map refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier) {
        if (!AsaasConfig.getInstance().useMundiPaggProduction) return [success: true]

        RefundRequestBuilder refundRequestBuilder = new RefundRequestBuilder(transactionIdentifier)
        refundRequestBuilder.execute()

        ApiRequestManager apiRequestManager = new ApiRequestManager(refundRequestBuilder.requestMap)
        apiRequestManager.refund()

        Boolean success = (!apiRequestManager.error && apiRequestManager.responseMap.response == "[cancelOrRefund-received]")

        debitCardTransactionLogService.save(null, customer, customerAccount, DebitCardTransactionEvent.REFUND, success, [transactionIdentifier: transactionIdentifier])

        return [success: success]
    }
}
