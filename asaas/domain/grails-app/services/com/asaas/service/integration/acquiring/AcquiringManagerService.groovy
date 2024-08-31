package com.asaas.service.integration.acquiring

import com.asaas.cardtransaction.CardTransactionResponseAdapter
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.creditcard.CreditCardTransactionVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.integration.acquiring.api.AcquiringManager
import com.asaas.integration.acquiring.dto.AcquiringBaseResponseDTO
import com.asaas.integration.acquiring.dto.acquirerfee.AcquiringAcquirerFeeSaveRequestDTO
import com.asaas.integration.acquiring.dto.authorize.AcquiringAuthorizeRequestDTO
import com.asaas.integration.acquiring.dto.authorize.AcquiringAuthorizeResponseDTO
import com.asaas.integration.acquiring.dto.capture.AcquiringCaptureRequestDTO
import com.asaas.integration.acquiring.dto.capture.AcquiringCaptureResponseDTO
import com.asaas.integration.acquiring.dto.config.AcquiringConfigRequestDTO
import com.asaas.integration.acquiring.dto.refund.AcquiringRefundRequestDTO
import com.asaas.integration.acquiring.vo.config.AcquiringConfigVO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class AcquiringManagerService {

    def creditCardTransactionLogService

    public CardTransactionResponseAdapter authorize(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        if (!AcquiringManager.isEnabled()) {
            return new CardTransactionResponseAdapter(new MockJsonUtils("acquiring/AcquiringManagerService/authorize.json").buildMock(AcquiringAuthorizeResponseDTO), creditCardTransactionVO, true)
        }

        AcquiringAuthorizeRequestDTO acquiringAuthorizeRequestDTO = new AcquiringAuthorizeRequestDTO(creditCardTransactionVO)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.post("/api/v1/transactions/authorize", acquiringAuthorizeRequestDTO.properties)

        AcquiringAuthorizeResponseDTO acquiringAuthorizeResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringAuthorizeResponseDTO)

        CardTransactionResponseAdapter cardTransactionResponseAdapter = new CardTransactionResponseAdapter(acquiringAuthorizeResponseDTO, creditCardTransactionVO, acquiringManager.isSuccessful())

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.AUTHORIZATION, cardTransactionResponseAdapter, acquiringManager, isFallback)
        cardTransactionResponseAdapter.creditCardTransactionLogIdList.add(creditCardTransactionLogId)

        return cardTransactionResponseAdapter
    }

    public CardTransactionResponseAdapter capture(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        if (!AcquiringManager.isEnabled()) {
            return new CardTransactionResponseAdapter(new MockJsonUtils("acquiring/AcquiringManagerService/capture.json").buildMock(AcquiringCaptureResponseDTO), creditCardAuthorizationInfo, true)
        }

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.put("/api/v1/transactions/${creditCardAuthorizationInfo.transactionIdentifier}/capture", [:])

        AcquiringCaptureResponseDTO acquiringCaptureResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringCaptureResponseDTO)

        CardTransactionResponseAdapter cardTransactionResponseAdapter = new CardTransactionResponseAdapter(acquiringCaptureResponseDTO, creditCardAuthorizationInfo, acquiringManager.isSuccessful())

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.CAPTURE, cardTransactionResponseAdapter, acquiringManager, isFallback)
        cardTransactionResponseAdapter.creditCardTransactionLogIdList.add(creditCardTransactionLogId)

        return cardTransactionResponseAdapter
    }

    public CardTransactionResponseAdapter capture(CreditCardTransactionVO creditCardTransactionVO, Boolean isFallback) {
        if (!AcquiringManager.isEnabled()) {
            return new CardTransactionResponseAdapter(new MockJsonUtils("acquiring/AcquiringManagerService/capture.json").buildMock(AcquiringCaptureResponseDTO), creditCardTransactionVO, true)
        }

        AcquiringCaptureRequestDTO acquiringCaptureRequestDTO = new AcquiringCaptureRequestDTO(creditCardTransactionVO)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.post("/api/v1/transactions/capture", acquiringCaptureRequestDTO.properties)

        AcquiringCaptureResponseDTO acquiringCaptureResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringCaptureResponseDTO)

        CardTransactionResponseAdapter cardTransactionResponseAdapter = new CardTransactionResponseAdapter(acquiringCaptureResponseDTO, creditCardTransactionVO, acquiringManager.isSuccessful())

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.CAPTURE, cardTransactionResponseAdapter, acquiringManager, isFallback)
        cardTransactionResponseAdapter.creditCardTransactionLogIdList.add(creditCardTransactionLogId)

        return cardTransactionResponseAdapter
    }

    public CardTransactionResponseAdapter refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, Long amountInCents) {
        if (!AcquiringManager.isEnabled()) return new CardTransactionResponseAdapter(customer, customerAccount, new AcquiringBaseResponseDTO(success: true), transactionIdentifier, true)

        AcquiringRefundRequestDTO acquiringRefundRequestDTO = new AcquiringRefundRequestDTO(amountInCents)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.put("/api/v1/transactions/${transactionIdentifier}/refund", acquiringRefundRequestDTO.properties)

        AcquiringBaseResponseDTO acquiringBaseResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringBaseResponseDTO)

        CardTransactionResponseAdapter cardTransactionResponseAdapter = new CardTransactionResponseAdapter(customer, customerAccount, acquiringBaseResponseDTO, transactionIdentifier, acquiringManager.isSuccessful())

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.REFUND, cardTransactionResponseAdapter, acquiringManager, false)
        cardTransactionResponseAdapter.creditCardTransactionLogIdList.add(creditCardTransactionLogId)

        return cardTransactionResponseAdapter
    }

    public Boolean configure(AcquiringConfigVO acquiringConfigVO) {
        AcquiringConfigRequestDTO acquiringConfigRequestDTO = new AcquiringConfigRequestDTO(acquiringConfigVO)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.post("/api/v1/configs", acquiringConfigRequestDTO.properties)

        AcquiringBaseResponseDTO acquiringBaseResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringBaseResponseDTO)

        return acquiringManager.isSuccessful() && acquiringBaseResponseDTO.success
    }

    public Boolean saveAcquirerFee(BigDecimal fee, Boolean prioritize, CreditCardBrand brand, CreditCardAcquirer acquirer, Integer installmentCount, String mcc) {
        AcquiringAcquirerFeeSaveRequestDTO acquiringAcquirerFeeSaveRequestDTO = new AcquiringAcquirerFeeSaveRequestDTO(fee, prioritize, brand, acquirer, installmentCount, mcc)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.post("/api/v1/transactions/acquirerFees", acquiringAcquirerFeeSaveRequestDTO.properties)

        AcquiringBaseResponseDTO acquiringBaseResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringBaseResponseDTO)

        return acquiringManager.isSuccessful() && acquiringBaseResponseDTO.success
    }

    private Long saveCreditCardTransactionLog(CreditCardTransactionEvent creditCardTransactionEvent, CardTransactionResponseAdapter cardTransactionResponseAdapter, AcquiringManager acquiringManager, Boolean isFallback) {
        Map gatewayInfo = [:]
        gatewayInfo.message = cardTransactionResponseAdapter.message
        gatewayInfo.transactionIdentifier = cardTransactionResponseAdapter.transactionIdentifier
        gatewayInfo.httpStatus = acquiringManager.statusCode
        gatewayInfo.apiResponseJson = acquiringManager.httpRequestManager.responseBody
        gatewayInfo.amountInCents = cardTransactionResponseAdapter.amountInCents
        gatewayInfo.gateway = cardTransactionResponseAdapter.gateway
        gatewayInfo.acquirerReturnCode = cardTransactionResponseAdapter.acquirerReturnCode
        gatewayInfo.softDescriptor = cardTransactionResponseAdapter.softDescriptor
        gatewayInfo.fallback = isFallback

        if (cardTransactionResponseAdapter.billingInfo) {
            gatewayInfo.billingInfoId = cardTransactionResponseAdapter.billingInfo.id
            gatewayInfo.creditCardBin = cardTransactionResponseAdapter.billingInfo.creditCardInfo.bin
        } else if (cardTransactionResponseAdapter.creditCard) {
            gatewayInfo.billingInfoId = cardTransactionResponseAdapter.creditCard.billingInfo?.id
            gatewayInfo.creditCardBin = cardTransactionResponseAdapter.creditCard.buildBin()
        }

        return creditCardTransactionLogService.save(cardTransactionResponseAdapter.customer, cardTransactionResponseAdapter.customerAccount, cardTransactionResponseAdapter.payment, creditCardTransactionEvent, cardTransactionResponseAdapter.success, gatewayInfo)
    }
}
