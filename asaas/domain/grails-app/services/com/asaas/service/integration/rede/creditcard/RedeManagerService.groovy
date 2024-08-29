package com.asaas.service.integration.rede.creditcard

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.creditcard.CreditCardTransactionVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.integration.rede.creditcard.api.RedeManager
import com.asaas.integration.rede.creditcard.dto.authorize.AuthorizeDTO
import com.asaas.integration.rede.creditcard.dto.authorize.AuthorizeResponseDTO
import com.asaas.integration.rede.creditcard.dto.capture.CaptureDTO
import com.asaas.integration.rede.creditcard.dto.capture.CaptureResponseDTO
import com.asaas.integration.rede.creditcard.dto.capturelater.CaptureLaterResponseDTO
import com.asaas.integration.rede.creditcard.dto.refund.RefundDTO
import com.asaas.integration.rede.creditcard.dto.refund.RefundResponseDTO
import com.asaas.integration.rede.creditcard.dto.zerodollar.ZeroDollarDTO
import com.asaas.integration.rede.creditcard.dto.zerodollar.ZeroDollarResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class RedeManagerService {

    private static final String CAPTURE_OR_AUTHORIZE_TRANSACTION_SUCCESS_RETURN_CODE = "00"

    private static final String AUTHORIZE_THREE_D_SECURE_TRANSACTION_SUCCESS_RETURN_CODE = "220"

    private static final String DEFAULT_BRAND_RETURN_CODE_WHEN_NOT_AVAILABLE = "NA"

    def creditCardTransactionLogService

    public Map capture(Installment installment, Payment payment, CreditCard creditCard, Boolean isFallback) {
        Customer customer = installment?.getProvider() ?: payment.provider

        CaptureDTO captureDTO = new CaptureDTO(installment, payment, creditCard, customer)

        RedeManager redeManager = new RedeManager()
        redeManager.post("/v1/transactions", captureDTO.toMap())

        CaptureResponseDTO captureResponseDTO = GsonBuilderUtils.buildClassFromJson((redeManager.responseBody as JSON).toString(), CaptureResponseDTO)

        Map result = [
            success: redeManager.isSuccessful() && (captureResponseDTO?.returnCode == RedeManagerService.CAPTURE_OR_AUTHORIZE_TRANSACTION_SUCCESS_RETURN_CODE),
            message: captureResponseDTO?.brand?.returnCode == RedeManagerService.DEFAULT_BRAND_RETURN_CODE_WHEN_NOT_AVAILABLE ? captureResponseDTO?.returnMessage : captureResponseDTO?.brand?.returnMessage,
            returnCode: captureResponseDTO?.brand?.returnCode,
            transactionReference: captureResponseDTO?.reference,
            transactionIdentifier: captureResponseDTO?.tid,
            instantBuyKey: null,
            amountInCents: captureResponseDTO?.amount,
            acquirer: CreditCardAcquirer.REDE,
            gateway: CreditCardGateway.REDE,
            customerToken: payment.customerAccount.id,
            acquirerReturnCode: captureResponseDTO?.brand?.returnCode,
            orderKey: captureResponseDTO?.nsu,
            uniqueSequentialNumber: captureResponseDTO?.nsu,
            authorizationCode: captureResponseDTO?.brand?.authorizationCode
        ]

        Map transactionLogInfo = [
            amount: captureDTO.amount,
            customer: customer,
            customerAccount: payment.customerAccount,
            payment: payment,
            creditCard: creditCard,
            billingInfo: creditCard.billingInfo,
            acquirerReturnCode: captureResponseDTO?.brand?.returnCode,
            softDescriptor: captureDTO.softDescriptor
        ]

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.CAPTURE, result, redeManager.statusCode, redeManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        if (!result.success) generateAlertLogIfNecessary(redeManager.hasAcceptableHttpStatus(), result.message, creditCardTransactionLogId)

        return result
    }

    public Map capture(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        RedeManager redeManager = new RedeManager()
        redeManager.put("/v1/transactions/${creditCardAuthorizationInfo.transactionIdentifier}", [:])

        CaptureLaterResponseDTO captureLaterResponseDTO = GsonBuilderUtils.buildClassFromJson((redeManager.responseBody as JSON).toString(), CaptureLaterResponseDTO)

        Payment payment = creditCardAuthorizationInfo.payment

        Map result = [
            success: redeManager.isSuccessful() && (captureLaterResponseDTO?.returnCode == RedeManagerService.CAPTURE_OR_AUTHORIZE_TRANSACTION_SUCCESS_RETURN_CODE),
            message: captureLaterResponseDTO?.brand?.returnCode == RedeManagerService.DEFAULT_BRAND_RETURN_CODE_WHEN_NOT_AVAILABLE ? captureLaterResponseDTO?.returnMessage : captureLaterResponseDTO?.brand?.returnMessage,
            returnCode: captureLaterResponseDTO?.brand?.returnCode,
            transactionReference: captureLaterResponseDTO?.reference,
            transactionIdentifier: captureLaterResponseDTO?.tid,
            instantBuyKey: null,
            amountInCents: creditCardAuthorizationInfo.amountInCents,
            acquirer: CreditCardAcquirer.REDE,
            gateway: CreditCardGateway.REDE,
            customerToken: payment.customerAccount.id,
            acquirerReturnCode: captureLaterResponseDTO?.brand?.returnCode,
            orderKey: captureLaterResponseDTO?.nsu,
            uniqueSequentialNumber: captureLaterResponseDTO?.nsu,
            authorizationCode: captureLaterResponseDTO?.authorizationCode
        ]

        Map transactionLogInfo = [
            amount: creditCardAuthorizationInfo.amountInCents,
            customer: payment.provider,
            customerAccount: payment.customerAccount,
            payment: payment,
            acquirerReturnCode: captureLaterResponseDTO?.brand?.returnCode
        ]

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.CAPTURE, result, redeManager.statusCode, redeManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        if (!result.success) generateAlertLogIfNecessary(redeManager.hasAcceptableHttpStatus(), result.message, creditCardTransactionLogId)

        return result
    }

    public Boolean refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, Long amountInCents) {
        RefundDTO refundDTO = new RefundDTO(amountInCents)

        RedeManager redeManager = new RedeManager()
        redeManager.post("/v1/transactions/${transactionIdentifier}/refunds", refundDTO.properties)

        RefundResponseDTO refundResponseDTO = GsonBuilderUtils.buildClassFromJson((redeManager.responseBody as JSON).toString(), RefundResponseDTO)

        Map transactionLogInfo = [
            customer: customer,
            customerAccount: customerAccount,
            amount: amountInCents
        ]

        saveCreditCardTransactionLog(CreditCardTransactionEvent.REFUND, [transactionIdentifier: transactionIdentifier], redeManager.statusCode, redeManager.httpRequestManager.responseBody, transactionLogInfo, false)

        if (!redeManager.hasAcceptableHttpStatus()) {
            AsaasLogger.error("RedeManagerService.refund >>> Erro ao estornar a transação: ${refundResponseDTO?.returnMessage}")
            return false
        }

        final List<String> refundDoneCodeList = ["355", "359", "360"]
        final List<String> refundDoneMessageList = ["Refund successful.", "Refund request has been successful.", "Transaction already cancelled."]

        if (refundDoneCodeList.contains(refundResponseDTO?.returnCode) && refundDoneMessageList.contains(refundResponseDTO?.returnMessage)) {
            return true
        }

        AsaasLogger.error("RedeManagerService.refund >>> Erro ao estornar a transação. Código de retorno diferente do esperado: ${redeManager.responseBody}")
        return false
    }

    public Map zeroDollarAuth(CustomerAccount customerAccount, CreditCard creditCard, Boolean isFallback) {
        ZeroDollarDTO zeroDollarDTO = new ZeroDollarDTO(customerAccount.provider, creditCard)

        RedeManager redeManager = new RedeManager()
        redeManager.post("/v1/transactions", zeroDollarDTO.toMap())

        ZeroDollarResponseDTO zeroDollarResponseDTO = GsonBuilderUtils.buildClassFromJson((redeManager.responseBody as JSON).toString(), ZeroDollarResponseDTO)

        final String transactionSuccessReturnCode = "174"

        Map result = [
            success: redeManager.isSuccessful() && (zeroDollarResponseDTO?.returnCode == transactionSuccessReturnCode),
            message: zeroDollarResponseDTO?.brand?.returnCode == RedeManagerService.DEFAULT_BRAND_RETURN_CODE_WHEN_NOT_AVAILABLE ? zeroDollarResponseDTO?.returnMessage : zeroDollarResponseDTO?.brand?.returnMessage,
            returnCode: zeroDollarResponseDTO?.brand?.returnCode,
            acquirerReturnCode: zeroDollarResponseDTO?.brand?.returnCode,
            acquirer: CreditCardAcquirer.REDE,
            gateway: CreditCardGateway.REDE
        ]

        Map transactionLogInfo = [
            amount: 0,
            customer: customerAccount.provider,
            customerAccount: customerAccount,
            creditCard: creditCard,
            acquirerReturnCode: zeroDollarResponseDTO?.brand?.returnCode,
            billingInfo: creditCard.billingInfo
        ]

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.AUTHORIZATION, result, redeManager.statusCode, redeManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        if (!result.success) generateAlertLogIfNecessary(redeManager.hasAcceptableHttpStatus(), result.message, creditCardTransactionLogId)

        return result
    }

    public Map authorize(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        AuthorizeDTO authorizeDTO = new AuthorizeDTO(creditCardTransactionVO)

        RedeManager redeManager = new RedeManager()
        redeManager.post("/v1/transactions", authorizeDTO.toMap())

        AuthorizeResponseDTO authorizeResponseDTO = GsonBuilderUtils.buildClassFromJson((redeManager.responseBody as JSON).toString(), AuthorizeResponseDTO)

        List<String> authorizationSuccessReturnCodeList = [RedeManagerService.CAPTURE_OR_AUTHORIZE_TRANSACTION_SUCCESS_RETURN_CODE]
        if (creditCardTransactionVO.withThreeDSecure) authorizationSuccessReturnCodeList.add(RedeManagerService.AUTHORIZE_THREE_D_SECURE_TRANSACTION_SUCCESS_RETURN_CODE)

        Map result = [
            success: redeManager.isSuccessful() && authorizationSuccessReturnCodeList.contains(authorizeResponseDTO?.returnCode),
            message: (authorizeResponseDTO?.brand?.returnCode && authorizeResponseDTO.brand.returnCode != RedeManagerService.DEFAULT_BRAND_RETURN_CODE_WHEN_NOT_AVAILABLE) ? authorizeResponseDTO?.brand?.returnMessage : authorizeResponseDTO?.returnMessage,
            returnCode: authorizeResponseDTO?.brand?.returnCode,
            transactionReference: authorizeDTO?.reference,
            transactionIdentifier: authorizeResponseDTO?.tid,
            instantBuyKey: null,
            amountInCents: authorizeDTO?.amount,
            acquirer: CreditCardAcquirer.REDE,
            gateway: CreditCardGateway.REDE,
            customerToken: creditCardTransactionVO.customerAccount.id,
            acquirerReturnCode: authorizeResponseDTO?.brand?.returnCode,
            authorizationCode: authorizeResponseDTO?.brand?.authorizationCode
        ]

        if (creditCardTransactionVO.withThreeDSecure) {
            result.isThreeDSecureAuthorizationWithChallenge = result.transactionIdentifier == null

            if (result.isThreeDSecureAuthorizationWithChallenge) {
                result.threeDSecureIssuerUrl = authorizeResponseDTO?.threeDSecure?.url
            } else {
                result.threeDSecureCaptureUrl = authorizeDTO?.urls?.first()?.url
            }
        }

        Map transactionLogInfo = [
            amount: authorizeDTO.amount,
            customer: creditCardTransactionVO.customer,
            customerAccount: creditCardTransactionVO.customerAccount,
            payment: creditCardTransactionVO.payment,
            creditCard: creditCardTransactionVO.creditCard,
            billingInfo: creditCardTransactionVO.creditCard.billingInfo,
            acquirerReturnCode: authorizeResponseDTO?.brand?.returnCode,
            softDescriptor: authorizeDTO.softDescriptor
        ]

        CreditCardTransactionEvent event = creditCardTransactionVO.withThreeDSecure ? CreditCardTransactionEvent.AUTHORIZATION_3DS : CreditCardTransactionEvent.AUTHORIZATION

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(event, result, redeManager.statusCode, redeManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        if (!result.success) generateAlertLogIfNecessary(redeManager.hasAcceptableHttpStatus(), result.message, creditCardTransactionLogId)

        return result
    }

    private Long saveCreditCardTransactionLog(CreditCardTransactionEvent creditCardTransactionEvent, Map captureResultMap, Integer requestHttpStatusCode, String rawJsonResponse, Map transactionLogInfo, Boolean isFallback) {
        Map gatewayInfo = [:]
        gatewayInfo.message = captureResultMap.message
        gatewayInfo.transactionIdentifier = captureResultMap.transactionIdentifier
        gatewayInfo.httpStatus = requestHttpStatusCode
        gatewayInfo.apiResponseJson = rawJsonResponse
        gatewayInfo.amountInCents = transactionLogInfo.amount
        gatewayInfo.gateway = CreditCardGateway.REDE
        gatewayInfo.acquirerReturnCode = transactionLogInfo.acquirerReturnCode
        gatewayInfo.softDescriptor = transactionLogInfo.softDescriptor
        gatewayInfo.fallback = isFallback

        if (transactionLogInfo.billingInfo) {
            gatewayInfo.billingInfoId = transactionLogInfo.billingInfo.id
            gatewayInfo.creditCardBin = transactionLogInfo.billingInfo.creditCardInfo.bin
        } else if (transactionLogInfo.creditCard) {
            gatewayInfo.creditCardBin = transactionLogInfo.creditCard.buildBin()
        }

        return creditCardTransactionLogService.save(transactionLogInfo.customer, transactionLogInfo.customerAccount, transactionLogInfo.payment, creditCardTransactionEvent, captureResultMap.success, gatewayInfo)
    }

    private void generateAlertLogIfNecessary(Boolean hasAcceptableHttpStatus, String message, Long creditCardTransactionLogId) {
        String logMessage

        if (!hasAcceptableHttpStatus) {
            logMessage = "erro nao mapeado"
        } else if (message in ["Communication failure. Try again.", "Please, retry this transaction.", "Error. Retry transaction"]) {
            logMessage = "erro de timeout entre rede e bandeira"
        } else if (message?.toLowerCase()?.contains("contact rede")) {
            logMessage = "erro pedindo contato com a rede"
        }

        if (logMessage) AsaasLogger.warn("RedeManagerService >>> Erro ao transacionar na rede | ${logMessage} | ${message} | ${creditCardTransactionLogId}")
    }
}
