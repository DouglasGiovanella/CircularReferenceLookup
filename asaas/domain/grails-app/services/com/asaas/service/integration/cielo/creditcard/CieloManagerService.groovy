package com.asaas.service.integration.cielo.creditcard

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.creditcard.CreditCardTransactionVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.integration.cielo.creditcard.adapter.CardBinInfoAdapter
import com.asaas.integration.cielo.creditcard.adapter.TransactionInfoAdapter
import com.asaas.integration.cielo.creditcard.api.CieloManager
import com.asaas.integration.cielo.creditcard.dto.capturelater.CaptureLaterResponseDTO
import com.asaas.integration.cielo.creditcard.dto.refund.RefundRequestDTO
import com.asaas.integration.cielo.dto.authorize.AuthorizeDTO
import com.asaas.integration.cielo.dto.authorize.AuthorizeResponseDTO
import com.asaas.integration.cielo.dto.capture.CaptureDTO
import com.asaas.integration.cielo.dto.capture.CaptureResponseDTO
import com.asaas.integration.cielo.dto.cardBinInfo.CardBinResponseDTO
import com.asaas.integration.cielo.dto.tokenize.TokenizeDTO
import com.asaas.integration.cielo.dto.tokenize.TokenizeResponseDTO
import com.asaas.integration.cielo.dto.zeroDollarAuth.ZeroDollarAuthDTO
import com.asaas.integration.cielo.dto.zeroDollarAuth.ZeroDollarAuthResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CieloManagerService {

    def creditCardTransactionLogService

    public Map capture(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        CaptureDTO captureDTO = new CaptureDTO(creditCardTransactionVO)

        CieloManager cieloManager = new CieloManager()
        cieloManager.post("/1/sales", captureDTO.properties)

        CaptureResponseDTO captureResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), CaptureResponseDTO)

        final Integer transactionCapturedStatus = 2

        Map result = [
            success: cieloManager.isSuccessful() && (captureResponseDTO?.Payment?.Status == transactionCapturedStatus),
            message: captureResponseDTO?.Payment?.ReturnMessage,
            returnCode: captureResponseDTO?.Payment?.ReturnCode,
            transactionIdentifier: captureResponseDTO?.Payment?.Tid,
            instantBuyKey: captureResponseDTO?.Payment?.CreditCard?.CardToken,
            amountInCents: captureResponseDTO?.Payment?.CapturedAmount,
            transactionReference: captureResponseDTO?.Payment?.PaymentId,
            acquirer: CreditCardAcquirer.CIELO,
            gateway: CreditCardGateway.CIELO,
            customerToken: creditCardTransactionVO.customerAccount.id,
            acquirerReturnCode: captureResponseDTO?.Payment?.ReturnCode,
            uniqueSequentialNumber: captureResponseDTO?.Payment?.ProofOfSale,
            authorizationCode: captureResponseDTO?.Payment?.AuthorizationCode
        ]

        Map transactionLogInfo = [
            amount: captureDTO?.Payment?.Amount,
            customer: creditCardTransactionVO.customer,
            customerAccount: creditCardTransactionVO.customerAccount,
            payment: creditCardTransactionVO.payment,
            creditCard: creditCardTransactionVO.creditCard,
            acquirerReturnCode: captureResponseDTO?.Payment?.ReturnCode,
            softDescriptor: captureDTO.payment.SoftDescriptor
        ]

        if (creditCardTransactionVO.billingInfo) {
            transactionLogInfo.billingInfo = creditCardTransactionVO.billingInfo
        } else {
            transactionLogInfo.billingInfo = creditCardTransactionVO.creditCard.billingInfo
        }

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.CAPTURE, result, cieloManager.statusCode, cieloManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        return result
    }

    public Map capture(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        CieloManager cieloManager = new CieloManager()
        cieloManager.put("/1/sales/${creditCardAuthorizationInfo.transactionReference}/capture", [:])

        CaptureLaterResponseDTO captureLaterResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), CaptureLaterResponseDTO)

        final Integer transactionCapturedStatus = 2

        Payment payment = creditCardAuthorizationInfo.payment

        Map result = [
            success: cieloManager.isSuccessful() && (captureLaterResponseDTO?.Status == transactionCapturedStatus),
            message: captureLaterResponseDTO?.ReturnMessage,
            returnCode: captureLaterResponseDTO?.ReturnCode,
            transactionIdentifier: captureLaterResponseDTO?.Tid,
            amountInCents: creditCardAuthorizationInfo.amountInCents,
            transactionReference: creditCardAuthorizationInfo.transactionReference,
            acquirer: CreditCardAcquirer.CIELO,
            gateway: CreditCardGateway.CIELO,
            customerToken: payment.customerAccount.id,
            acquirerReturnCode: captureLaterResponseDTO?.ReturnCode,
            uniqueSequentialNumber: captureLaterResponseDTO?.ProofOfSale,
            authorizationCode: captureLaterResponseDTO?.AuthorizationCode
        ]

        Map transactionLogInfo = [
            amount: creditCardAuthorizationInfo.amountInCents,
            customer: payment.provider,
            customerAccount: payment.customerAccount,
            payment: payment,
            acquirerReturnCode: captureLaterResponseDTO?.ReturnCode
        ]

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.CAPTURE, result, cieloManager.statusCode, cieloManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        return result
    }

    public Boolean refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, String transactionReference, Long amountInCents) {
        RefundRequestDTO refundRequestDTO = new RefundRequestDTO(amountInCents)

        CieloManager cieloManager = new CieloManager()
        cieloManager.put("/1/sales/${transactionReference}/void", refundRequestDTO.properties)

        Map transactionLogInfo = [
            customer: customer,
            customerAccount: customerAccount,
            amount: amountInCents
        ]

        saveCreditCardTransactionLog(CreditCardTransactionEvent.REFUND, [transactionIdentifier: transactionIdentifier, transactionReference: transactionReference], cieloManager.statusCode, cieloManager.httpRequestManager.responseBody, transactionLogInfo, false)

        if (!cieloManager.isSuccessful()) {
            AsaasLogger.error("CieloManagerService -> erro no estorno de transação: ${cieloManager.getErrorMessage()}")
            return false
        }

        final List<Integer> refundDoneStatusList = [10, 11, 312]
        final List<String> refundDoneMessageList = ["Successful", "Transaction not available to refund"]

        if (refundDoneStatusList.contains(cieloManager.responseBody.Status) && refundDoneMessageList.contains(cieloManager.responseBody.ReasonMessage)) {
            return true
        }

        AsaasLogger.error("CieloManagerService -> erro no estorno de transação. Código de retorno diferente do esperado: ${cieloManager.responseBody}")
        return false
    }

    public Map tokenizeWithZeroDollarAuth(CreditCard creditCard, CustomerAccount customerAccount, Boolean isFallback) {
        ZeroDollarAuthDTO zeroDollarAuthDTO = new ZeroDollarAuthDTO(creditCard)

        CieloManager cieloManager = new CieloManager()
        cieloManager.post("/1/zeroauth", zeroDollarAuthDTO.properties)

        ZeroDollarAuthResponseDTO zeroDollarAuthResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), ZeroDollarAuthResponseDTO)

        Map result = [
            success: cieloManager.isSuccessful() && Utils.toBoolean(zeroDollarAuthResponseDTO?.Valid),
            message: zeroDollarAuthResponseDTO?.ReturnMessage,
            returnCode: zeroDollarAuthResponseDTO?.ReturnCode,
            acquirerReturnCode: zeroDollarAuthResponseDTO?.ReturnCode,
            acquirer: CreditCardAcquirer.CIELO,
            gateway: CreditCardGateway.CIELO
        ]

        Map transactionLogInfo = [
            amount: 0,
            customer: customerAccount.provider,
            customerAccount: customerAccount,
            acquirerReturnCode: zeroDollarAuthResponseDTO?.ReturnCode,
            creditCard: creditCard
        ]

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.AUTHORIZATION, result, cieloManager.statusCode, cieloManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        if (!result.success) return result

        return tokenize(creditCard, customerAccount, result, isFallback)
    }

    public Map authorize(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        AuthorizeDTO authorizeDTO = new AuthorizeDTO(creditCardTransactionVO)

        CieloManager cieloManager = new CieloManager()
        cieloManager.post("/1/sales", authorizeDTO.properties)

        AuthorizeResponseDTO authorizeResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), AuthorizeResponseDTO)

        final Integer transactionAuthorizedStatus = 1

        Map result = [
            success: cieloManager.isSuccessful() && (authorizeResponseDTO?.Payment?.Status == transactionAuthorizedStatus),
            message: authorizeResponseDTO?.Payment?.ReturnMessage,
            returnCode: authorizeResponseDTO?.Payment?.ReturnCode,
            transactionIdentifier: authorizeResponseDTO?.Payment?.Tid,
            instantBuyKey: authorizeResponseDTO?.Payment?.CreditCard?.CardToken,
            amountInCents: authorizeResponseDTO?.Payment?.Amount,
            transactionReference: authorizeResponseDTO?.Payment?.PaymentId,
            acquirer: CreditCardAcquirer.CIELO,
            gateway: CreditCardGateway.CIELO,
            customerToken: creditCardTransactionVO.customerAccount.id,
            acquirerReturnCode: authorizeResponseDTO?.Payment?.ReturnCode,
            authorizationCode: authorizeResponseDTO?.Payment?.AuthorizationCode
        ]

        Map transactionLogInfo = [
            amount: authorizeDTO?.Payment?.Amount,
            customer: creditCardTransactionVO.customer,
            customerAccount: creditCardTransactionVO.customerAccount,
            payment: creditCardTransactionVO.payment,
            creditCard: creditCardTransactionVO.creditCard,
            acquirerReturnCode: authorizeResponseDTO?.Payment?.ReturnCode,
            softDescriptor: authorizeDTO.payment.SoftDescriptor
        ]

        if (creditCardTransactionVO.billingInfo) {
            transactionLogInfo.billingInfo = creditCardTransactionVO.billingInfo
        } else {
            transactionLogInfo.billingInfo = creditCardTransactionVO.creditCard.billingInfo
        }

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.AUTHORIZATION, result, cieloManager.statusCode, cieloManager.httpRequestManager.responseBody, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList = [creditCardTransactionLogId]

        return result
    }

    public TransactionInfoAdapter getTransactionInfo(String transactionReference) {
        CieloManager cieloManager = new CieloManager()
        cieloManager.get("/1/sales/${transactionReference}", [:])

        CaptureResponseDTO captureResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), CaptureResponseDTO)

        return new TransactionInfoAdapter(captureResponseDTO, cieloManager.isSuccessful())
    }

    public CreditCardBrand getBrand(String bin) {
        CardBinInfoAdapter cardBinAdapter = getCardBinInfo(bin)
        return cardBinAdapter ? cardBinAdapter.brand : CreditCardBrand.UNKNOWN
    }

    public CardBinInfoAdapter getCardBinInfo(String bin) {
        CieloManager cieloManager = new CieloManager()
        cieloManager.get("/1/cardBin/${bin}", [:])

        if (!cieloManager.isSuccessful()) return null

        CardBinResponseDTO cardBinResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), CardBinResponseDTO)

        return new CardBinInfoAdapter(cardBinResponseDTO)
    }

    private Map tokenize(CreditCard creditCard, CustomerAccount customerAccount, Map zeroDollarAuthResult, Boolean isFallback) {
        TokenizeDTO tokenizeDTO = new TokenizeDTO(creditCard)

        CieloManager cieloManager = new CieloManager()
        cieloManager.post("/1/card", tokenizeDTO.properties)

        TokenizeResponseDTO tokenizeResponseDTO = GsonBuilderUtils.buildClassFromJson((cieloManager.responseBody as JSON).toString(), TokenizeResponseDTO)

        Map result = zeroDollarAuthResult + [success: cieloManager.isSuccessful(), instantBuyKey: tokenizeResponseDTO?.CardToken]

        if (!result.instantBuyKey) result.success = false

        Map transactionLogInfo = [
            amount: 0,
            customer: customerAccount.provider,
            customerAccount: customerAccount,
            creditCard: creditCard
        ]

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.TOKENIZE, result, cieloManager.statusCode, null, transactionLogInfo, isFallback)
        result.creditCardTransactionLogIdList.add(creditCardTransactionLogId)

        return result
    }

    private Long saveCreditCardTransactionLog(CreditCardTransactionEvent creditCardTransactionEvent, Map captureResultMap, Integer requestHttpStatusCode, String rawJsonResponse, Map transactionLogInfo, Boolean isFallback) {
        Map gatewayInfo = [:]
        gatewayInfo.message = captureResultMap.message
        gatewayInfo.transactionIdentifier = captureResultMap.transactionIdentifier
        gatewayInfo.httpStatus = requestHttpStatusCode
        gatewayInfo.apiResponseJson = rawJsonResponse
        gatewayInfo.amountInCents = transactionLogInfo.amount
        gatewayInfo.gateway = CreditCardGateway.CIELO
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
}
