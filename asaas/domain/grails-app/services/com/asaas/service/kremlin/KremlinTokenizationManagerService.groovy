package com.asaas.service.kremlin

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.integration.kremlin.adapter.TokenizationInfoAdapter
import com.asaas.integration.kremlin.adapter.TokenizedCreditCardInfoAdapter
import com.asaas.integration.kremlin.api.KremlinManager
import com.asaas.integration.kremlin.dto.tokenization.KremlinDeleteTokenizedCreditCardRequestDTO
import com.asaas.integration.kremlin.dto.tokenization.KremlinDeleteTokenizedCreditCardResponseDTO
import com.asaas.integration.kremlin.dto.tokenization.KremlinGetTokenizedCreditCardRequestDTO
import com.asaas.integration.kremlin.dto.tokenization.KremlinGetTokenizedCreditCardResponseDTO
import com.asaas.integration.kremlin.dto.tokenization.KremlinTokenizeCreditCardRequestDTO
import com.asaas.integration.kremlin.dto.tokenization.KremlinTokenizeCreditCardResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.StringUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class KremlinTokenizationManagerService {

    def creditCardTransactionLogService
    def crypterService

    public TokenizationInfoAdapter tokenize(CreditCard creditCard, CustomerAccount customerAccount, Payment payment, Boolean isFallback) {
        Map encryptedInfoMap = crypterService.encrypt(buildCreditCardString(creditCard), null)
        String billingInfoPublicId = UUID.randomUUID()

        KremlinTokenizeCreditCardRequestDTO kremlinTokenizeCreditCardRequestDTO = new KremlinTokenizeCreditCardRequestDTO(encryptedInfoMap.encryptedString, encryptedInfoMap.iv, billingInfoPublicId, buildCreditCardFullInfoHash(creditCard, billingInfoPublicId))

        KremlinManager kremlinManager = new KremlinManager()
        kremlinManager.post("/api/v1/cards", kremlinTokenizeCreditCardRequestDTO.properties)

        Long creditCardTransactionLogId = saveCreditCardTransactionLog(CreditCardTransactionEvent.TOKENIZE, customerAccount.provider, customerAccount, null, payment, kremlinManager.isSuccessful(), kremlinManager.statusCode, isFallback)

        KremlinTokenizeCreditCardResponseDTO kremlinTokenizeCreditCardResponseDTO = GsonBuilderUtils.buildClassFromJson((kremlinManager.responseBody as JSON).toString(), KremlinTokenizeCreditCardResponseDTO)

        if (!kremlinManager.isSuccessful()) AsaasLogger.warn("KremlinTokenizationService.tokenize >>> Erro ao tokenizar utilizando o KREMLIN [Erros: ${kremlinTokenizeCreditCardResponseDTO?.errorList?.message?.join(" / ")}].")

        return new TokenizationInfoAdapter(kremlinTokenizeCreditCardResponseDTO, customerAccount, billingInfoPublicId, creditCardTransactionLogId)
    }

    public TokenizedCreditCardInfoAdapter getCreditCardInfo(BillingInfo billingInfo) {
        KremlinGetTokenizedCreditCardRequestDTO kremlinGetTokenizedCreditCardRequestDTO = new KremlinGetTokenizedCreditCardRequestDTO(billingInfo.creditCardInfo.buildToken(), billingInfo.publicId)

        KremlinManager kremlinManager = new KremlinManager()
        kremlinManager.get("/api/v1/cards", kremlinGetTokenizedCreditCardRequestDTO.properties)

        KremlinGetTokenizedCreditCardResponseDTO kremlinGetTokenizedCreditCardResponseDTO = GsonBuilderUtils.buildClassFromJson((kremlinManager.responseBody as JSON).toString(), KremlinGetTokenizedCreditCardResponseDTO)

        if (!kremlinManager.isSuccessful()) {
            AsaasLogger.error("KremlinTokenizationService.getCreditCardInfo >>> Erro ao buscar as informações do cartão no KREMLIN [Erros: ${kremlinGetTokenizedCreditCardResponseDTO?.errorList?.message?.join(" / ")}].")
            return new TokenizedCreditCardInfoAdapter(false, null)
        }

        String decryptedCreditCardInfo = crypterService.decrypt(kremlinGetTokenizedCreditCardResponseDTO.encryptedCardData, kremlinGetTokenizedCreditCardResponseDTO.iv64, null)

        CreditCard creditCard = buildCreditCardInfoFromDecryptedCreditCardString(decryptedCreditCardInfo)

        Boolean hasErrorInCreditCardInfo = hasErrorInCreditCardInfo(creditCard, billingInfo.publicId, kremlinGetTokenizedCreditCardResponseDTO.creditCardInfoHash)

        return new TokenizedCreditCardInfoAdapter(!hasErrorInCreditCardInfo, hasErrorInCreditCardInfo ? null : creditCard)
    }

    public String deleteTokenizedCreditCard(String token, String billingInfoPublicId) {
        KremlinDeleteTokenizedCreditCardRequestDTO kremlinDeleteTokenizedCreditCardRequestDTO = new KremlinDeleteTokenizedCreditCardRequestDTO(token, billingInfoPublicId)

        KremlinManager kremlinManager = new KremlinManager()
        kremlinManager.delete("/api/v1/cards", kremlinDeleteTokenizedCreditCardRequestDTO.properties)

        KremlinDeleteTokenizedCreditCardResponseDTO kremlinDeleteTokenizedCreditCardResponseDTO = GsonBuilderUtils.buildClassFromJson((kremlinManager.responseBody as JSON).toString(), KremlinDeleteTokenizedCreditCardResponseDTO)

        if (!kremlinManager.isSuccessful()) {
            AsaasLogger.error("KremlinTokenizationService.deleteTokenizedCreditCard >>> Erro ao deletar o cartão tokenizado no KREMLIN [Erros: ${kremlinDeleteTokenizedCreditCardResponseDTO?.errorList?.message?.join(" / ")}].")
            return null
        }

        return kremlinDeleteTokenizedCreditCardResponseDTO.id
    }

    private String buildCreditCardString(CreditCard creditCard) {
        List<String> creditCardInfoList = []
        creditCardInfoList.add(creditCard.holderName)
        creditCardInfoList.add(creditCard.number)
        creditCardInfoList.add(creditCard.expiryMonth)
        creditCardInfoList.add(creditCard.expiryYear)
        creditCardInfoList.add(creditCard.ccv)

        return creditCardInfoList.join("-")
    }

    private String buildCreditCardFullInfoHash(CreditCard creditCard, String billingInfoPublicId) {
        String secretAndCreditCardInfo = AsaasApplicationHolder.config.asaas.cardHash.secret
        secretAndCreditCardInfo += StringUtils.removeWhitespaces(billingInfoPublicId)
        secretAndCreditCardInfo += StringUtils.removeWhitespaces(creditCard.holderName)
        secretAndCreditCardInfo += StringUtils.removeWhitespaces(creditCard.number)
        secretAndCreditCardInfo += StringUtils.removeWhitespaces(creditCard.expiryMonth)
        secretAndCreditCardInfo += StringUtils.removeWhitespaces(creditCard.expiryYear)
        secretAndCreditCardInfo += StringUtils.removeWhitespaces(creditCard.ccv)

        return secretAndCreditCardInfo.encodeAsSHA256()
    }

    private Long saveCreditCardTransactionLog(CreditCardTransactionEvent creditCardTransactionEvent, Customer customer, CustomerAccount customerAccount, BillingInfo billingInfo, Payment payment, Boolean success, Integer requestHttpStatusCode, Boolean isFallback) {
        Map gatewayInfo = [:]
        gatewayInfo.httpStatus = requestHttpStatusCode
        gatewayInfo.amountInCents = 0
        gatewayInfo.gateway = CreditCardGateway.KREMLIN

        if (billingInfo) gatewayInfo.billingInfoId = billingInfo.id

        gatewayInfo.fallback = isFallback

        return creditCardTransactionLogService.save(customer, customerAccount, payment, creditCardTransactionEvent, success, gatewayInfo)
    }

    private CreditCard buildCreditCardInfoFromDecryptedCreditCardString(String decryptedCreditCardInfo) {
        String[] creditCardInfoList = decryptedCreditCardInfo.split("-")

        Map creditCardMap = [:]
        creditCardMap.holderName = creditCardInfoList[0]
        creditCardMap.number = creditCardInfoList[1]
        creditCardMap.expiryMonth = creditCardInfoList[2]
        creditCardMap.expiryYear = creditCardInfoList[3]
        creditCardMap.ccv = creditCardInfoList[4]

        return CreditCard.build(creditCardMap)
    }

    private Boolean hasErrorInCreditCardInfo(CreditCard creditCard, String billingInfoPublicId, String encryptedCardData) {
        if (encryptedCardData != buildCreditCardFullInfoHash(creditCard, billingInfoPublicId)) {
            AsaasLogger.error("KremlinTokenizationService.hasErrorInCreditCardInfo >>> Os dados recebidos do cartão são inválidos. [billingInfoPublicId: ${billingInfoPublicId}]")
            return true
        }

        return false
    }
}
