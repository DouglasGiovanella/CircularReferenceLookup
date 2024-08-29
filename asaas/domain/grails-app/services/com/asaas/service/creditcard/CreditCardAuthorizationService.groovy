package com.asaas.service.creditcard

import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoCardType
import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoVO
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardTransactionDeviceInfoVO
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.creditcard.HolderInfo
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.exception.UnsupportedCreditCardBrandException
import com.asaas.featureflag.FeatureFlagName
import com.asaas.integration.kremlin.adapter.TokenizationInfoAdapter
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class CreditCardAuthorizationService {

    private static final String CUSTOMER_BLOCKED_BY_REDE_RETURN_CODE = "N04"

    def adyenCreditCardService
    def billingInfoService
    def cardTransactionCapturedRawInfoService
    def cieloCreditCardService
    def acquiringCreditCardService
    def creditCardAcquirerFeeService
    def cyberSourceCreditCardService
    def featureFlagCacheService
    def mundiPaggRESTService
    def kremlinCreditCardService
    def redeCreditCardService

    public Map authorize(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, CreditCardTransactionDeviceInfoVO deviceInfo, Boolean withThreeDSecure) {
        if (!isAbleToPreAuth(billingInfo)) throw new UnsupportedOperationException("Operação de autorização não suportada.")

        Map authorizeResponse = [success: false]
        Boolean canTokenizeOnKremlin = !billingInfo && AsaasConfig.getInstance().creditCardWithKremlinEnabled
        Customer customer = payment?.provider ?: installment.getProvider()
        List<Long> creditCardTransactionLogIdList = []

        final Boolean isCardTokenizedOnKremlin = billingInfo?.creditCardInfo?.gateway?.isKremlin()
        final Boolean shouldUseKremlinForAcquiringToken = billingInfo?.creditCardInfo?.gateway?.isAcquiring() && !featureFlagCacheService.isEnabled(FeatureFlagName.CREDIT_CARD_GATEWAY_ACQUIRING)

        if (!billingInfo) creditCard.buildBrand()

        CreditCardBrand brand = billingInfo?.creditCardInfo?.brand ?: creditCard.brand
        CreditCardGateway creditCardGateway = withThreeDSecure ? getGatewayForThreeDSecure() : getCreditCardGateway(billingInfo, brand, installment, customer, false, false)

        CreditCardAcquirer acquirer
        if (creditCardGateway.isAcquiring()) acquirer = getAcquirerForAcquiring(payment.provider, billingInfo, brand, installment)

        if ((isCardTokenizedOnKremlin && !creditCardGateway.isAcquiring()) || shouldUseKremlinForAcquiringToken) {
            creditCard = getCreditCardOnKremlin(billingInfo)

            if (!creditCard) return authorizeResponse

            billingInfo = null
        }

        if (creditCard) creditCard.gateway = creditCardGateway

        TokenizationInfoAdapter kremlinTokenizationInfoAdapter
        if (canTokenizeOnKremlin && (creditCard.gateway.isRede() || creditCard.gateway.isAdyen())) {
            kremlinTokenizationInfoAdapter = kremlinCreditCardService.tokenize(payment.customerAccount, creditCard, payment, false)

            creditCardTransactionLogIdList.add(kremlinTokenizationInfoAdapter.creditCardTransactionLogId)

            if (!kremlinTokenizationInfoAdapter.tokenized) {
                AsaasLogger.warn("CreditCardAuthorizationService.authorize >>> Não foi possível autorizar o cobrança ${payment.id} pois não foi possível tokenizar no KREMLIN.")

                authorizeResponse.creditCardTransactionLogIdList = creditCardTransactionLogIdList

                return authorizeResponse
            }
        }

        authorizeResponse = processAuthorize(creditCardGateway, acquirer, installment, payment, creditCard, billingInfo, holderInfo, originInterface, deviceInfo, withThreeDSecure, false)
        warnIfCustomerBlockedAtAcquirer(authorizeResponse, customer.id)

        if (kremlinTokenizationInfoAdapter) authorizeResponse = processKremlinTokenizationInfo(kremlinTokenizationInfoAdapter, authorizeResponse)

        if (authorizeResponse.creditCardTransactionLogIdList) creditCardTransactionLogIdList.addAll(authorizeResponse.creditCardTransactionLogIdList)
        authorizeResponse.creditCardTransactionLogIdList = creditCardTransactionLogIdList

        return authorizeResponse
    }

    public Map captureAuthorizedPayment(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean withThreeDSecure) {
        if (!creditCardAuthorizationInfo) throw new BusinessException("Não foi encontrada autorização prévia para a cobrança.")

        Payment payment = creditCardAuthorizationInfo.payment

        if (payment.status.isReceivedOrConfirmed()) throw new BusinessException("Cobrança já confirmada.")
        if (payment.deleted) throw new BusinessException("A cobrança está cancelada.")
        if (!withThreeDSecure && !payment.status.isAuthorized()) throw new BusinessException("A cobrança não está autorizada.")

        Map captureResponse = processCaptureForPreviousAuthorization(creditCardAuthorizationInfo.gateway, creditCardAuthorizationInfo, false)

        if (captureResponse.success) {
            CardTransactionCapturedRawInfoVO cardTransactionCapturedRawInfoVO = new CardTransactionCapturedRawInfoVO(payment, captureResponse)
            cardTransactionCapturedRawInfoService.saveWithNewTransaction(CardTransactionCapturedRawInfoCardType.CREDIT, cardTransactionCapturedRawInfoVO)
        }

        return captureResponse
    }

    public Map capture(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, Boolean enableFallback, Boolean isFallback) {
        Map captureResponse = [success: false]
        Customer customer = payment?.provider ?: installment.getProvider()
        List<Long> creditCardTransactionLogIdList = []

        if (creditCard) {
            captureResponse = captureWithCreditCardInfo(installment, payment, creditCard, customer, holderInfo, originInterface, creditCardTransactionLogIdList, isFallback)
        } else {
            final Boolean shouldForceCieloBillingInfoFallbackUse = billingInfo.creditCardInfo.gateway.isCieloApplicable() && billingInfo.fallbackBillingInfo && !billingInfo.fallbackBillingInfo.creditCardInfo.gateway.isCieloApplicable()

            if (!shouldForceCieloBillingInfoFallbackUse) captureResponse = captureWithTokenizedCreditCard(installment, payment, billingInfo, customer, holderInfo, originInterface, creditCardTransactionLogIdList, isFallback)
        }

        return processCaptureResponse(captureResponse, customer, installment, payment, creditCard, billingInfo, holderInfo, originInterface, creditCardTransactionLogIdList, enableFallback)
    }

    private CreditCardGateway selectFallbackGateway(CreditCardGateway gateway, CreditCardBrand brand) {
        CreditCardGateway fallbackCreditCardGateway

        switch (gateway) {
            case CreditCardGateway.CIELO:
                fallbackCreditCardGateway = CreditCardGateway.ADYEN
                break
            case CreditCardGateway.ADYEN:
                fallbackCreditCardGateway = CreditCardGateway.REDE
                break
            case CreditCardGateway.CYBERSOURCE:
                fallbackCreditCardGateway = CreditCardGateway.ADYEN
                break
            case CreditCardGateway.REDE:
                fallbackCreditCardGateway = CreditCardGateway.ADYEN
                break
            default:
                fallbackCreditCardGateway = CreditCardGateway.ADYEN
        }

        if (fallbackCreditCardGateway) {
            if (fallbackCreditCardGateway.isAdyen() && !brand.isAdyenApplicable()) {
                fallbackCreditCardGateway = null
            } else if (fallbackCreditCardGateway.isCielo() && !brand.isCieloApplicable()) {
                fallbackCreditCardGateway = null
            } else if (fallbackCreditCardGateway.isRede() && !brand.isRedeApplicable()) {
                fallbackCreditCardGateway = null
            }
        }

        return fallbackCreditCardGateway
    }

    private Boolean isAbleToPreAuth(BillingInfo billingInfo) {
        if (!billingInfo) return true
        if (billingInfo.creditCardInfo.gateway.isKremlin()) return true
        if (billingInfo.creditCardInfo.gateway.isCielo()) return true
        if (billingInfo.creditCardInfo.gateway.isAcquiring()) return true

        if (billingInfo.creditCardInfo.gateway.isAdyen()) {
            if (billingInfo.customerAccount.provider.hasMcc(EconomicActivity.PHARMACY_OR_DRUG_STORE_MCC)) {
                AsaasLogger.warn("CreditCardAuthorizationService.isAbleToPreAuth >>> Transação não processada pois é um token da ADYEN de cliente de farmácia [billingInfoId: ${billingInfo.id} - customerId: ${billingInfo.customerAccount.provider.id}].")
            } else {
                return true
            }
        }

        return false
    }

    private CreditCardGateway getForcedGateway(BillingInfo billingInfo, Customer customer, CreditCardBrand brand) {
        final Boolean isExternalToken = billingInfo && !billingInfo.creditCardInfo.gateway.isKremlin() && !billingInfo.creditCardInfo.gateway.isAcquiring()

        if (!hasForcedGateway(customer)) {
            if (customer.hasMcc(EconomicActivity.PHARMACY_OR_DRUG_STORE_MCC)) {
                if (!isExternalToken && !brand.isRedeApplicable()) throw new UnsupportedCreditCardBrandException("Bandeira [${brand}] não suportada na REDE.")

                return CreditCardGateway.REDE
            }

            return null
        }

        if (CustomerParameter.getValue(customer, CustomerParameterName.FORCE_REDE_AS_ACQUIRER_ON_CAPTURE)) {
            return getRedeAsForcedGateway(brand, isExternalToken)
        } else if (CustomerParameter.getValue(customer, CustomerParameterName.FORCE_ADYEN_AS_ACQUIRER_ON_CAPTURE)) {
            if (!isExternalToken && !brand.isAdyenApplicable()) throw new UnsupportedCreditCardBrandException("Bandeira [${brand}] não suportada na ADYEN.")

            return CreditCardGateway.ADYEN
        } else if (CustomerParameter.getValue(customer, CustomerParameterName.FORCE_ACQUIRING_AS_GATEWAY)) {
            if (featureFlagCacheService.isEnabled(FeatureFlagName.CREDIT_CARD_GATEWAY_ACQUIRING)) {
                if (!isExternalToken && !brand.isAcquiringApplicable()) throw new UnsupportedCreditCardBrandException("Bandeira [${brand}] não suportada no Acquiring.")

                return CreditCardGateway.ACQUIRING
            } else {
                return getRedeAsForcedGateway(brand, isExternalToken)
            }
        }

        return null
    }

    private CreditCard getCreditCardOnKremlin(BillingInfo billingInfo) {
        CreditCard creditCard = kremlinCreditCardService.getCreditCardInfo(billingInfo)

        if (creditCard) {
            creditCard.billingInfo = billingInfo
            creditCard.buildBrand()
            return creditCard
        } else {
            AsaasLogger.error("CreditCardAuthorizationService.getCreditCardOnKremlin >>> Não foi possível consultar as informações do cartão no KREMLIN [billingInfoId: ${billingInfo.id}]")
            return null
        }
    }

    private CreditCardGateway getCreditCardGateway(BillingInfo billingInfo, CreditCardBrand brand, Installment installment, Customer customer, Boolean bypassAcquiringUse, Boolean bypassGatewaySelectionFromFee) {
        final Boolean isAcquiringEnabled = featureFlagCacheService.isEnabled(FeatureFlagName.CREDIT_CARD_GATEWAY_ACQUIRING)

        if (!bypassAcquiringUse && isAcquiringEnabled) return CreditCardGateway.ACQUIRING

        CreditCardGateway forcedGateway = getForcedGateway(billingInfo, customer, brand)

        final Boolean isKremlinToken = billingInfo?.creditCardInfo?.gateway?.isKremlin()
        final Boolean isAcquiringToken = billingInfo?.creditCardInfo?.gateway?.isAcquiring()

        final Boolean isExternalToken = billingInfo && !isKremlinToken && !isAcquiringToken
        final Boolean shouldUseBillingInfoGateway = billingInfo && ((isExternalToken && !forcedGateway?.isAcquiring()) || (!isKremlinToken && isAcquiringToken && isAcquiringEnabled))

        CreditCardGateway gateway
        if (shouldUseBillingInfoGateway) gateway = billingInfo.creditCardInfo.gateway
        if (!gateway && forcedGateway) gateway = forcedGateway
        if (!gateway && !bypassGatewaySelectionFromFee) gateway = creditCardAcquirerFeeService.selectGateway(brand, customer, installment?.getRemainingPaymentsCount())

        return gateway
    }

    private Map processCapture(CreditCardGateway creditCardGateway, CreditCardAcquirer acquirer, Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, Boolean isFallback) {
        switch (creditCardGateway) {
            case CreditCardGateway.CIELO:
                return cieloCreditCardService.capture(installment, payment, creditCard, billingInfo, isFallback)
            case CreditCardGateway.ADYEN:
                return adyenCreditCardService.capture(installment, payment, creditCard, billingInfo, isFallback)
            case CreditCardGateway.CYBERSOURCE:
                return cyberSourceCreditCardService.capture(installment, payment, creditCard, billingInfo, isFallback)
            case CreditCardGateway.REDE:
                return redeCreditCardService.capture(installment, payment, creditCard, isFallback)
            case CreditCardGateway.ACQUIRING :
                return acquiringCreditCardService.capture(acquirer, installment, payment, creditCard, billingInfo, holderInfo, originInterface, isFallback).properties
            default:
                return mundiPaggRESTService.capture(installment, payment, creditCard, billingInfo, isFallback)
        }
    }

    private Boolean hasForcedGateway(Customer customer) {
        return CustomerParameterName.listForceAcquirerOnCapture().collect { CustomerParameterName parameterName -> CustomerParameter.getValue(customer, parameterName) }.any()
    }

    private Map captureWithCreditCardInfo(Installment installment, Payment payment, CreditCard creditCard, Customer customer, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, List<Long> creditCardTransactionLogIdList, Boolean isFallback) {
        Map captureResponse = [success: false]

        CreditCardGateway mainGateway = creditCard.gateway
        creditCard.gateway = (isFallback && creditCard.gateway) ? selectFallbackGateway(creditCard.gateway, creditCard.brand) : getCreditCardGateway(null, creditCard.brand, installment, customer, false, false)

        CreditCardAcquirer acquirer
        if (creditCard.gateway.isAcquiring()) acquirer = getAcquirerForAcquiring(customer, null, creditCard.brand, installment)

        final Boolean creditCardWithKremlinEnabled = AsaasConfig.getInstance().creditCardWithKremlinEnabled

        TokenizationInfoAdapter kremlinTokenizationInfoAdapter
        if (creditCardWithKremlinEnabled && !creditCard.billingInfo && (creditCard.gateway.isRede() || creditCard.gateway.isAdyen())) {
            kremlinTokenizationInfoAdapter = kremlinCreditCardService.tokenize(payment.customerAccount, creditCard, payment, isFallback)

            creditCardTransactionLogIdList.add(kremlinTokenizationInfoAdapter.creditCardTransactionLogId)

            if (!kremlinTokenizationInfoAdapter.tokenized) {
                AsaasLogger.warn("CreditCardAuthorizationService.capture >>> Não foi possível capturar o cobrança ${payment.id} pois não foi possível tokenizar no KREMLIN.")
                captureResponse.forceFallbackEnabled = !isFallback
                captureResponse.notAvailable = true
                captureResponse.gateway = creditCard.gateway

                return captureResponse
            }
        }

        captureResponse = processCapture(creditCard.gateway, acquirer, installment, payment, creditCard, null, holderInfo, originInterface, isFallback)
        if (kremlinTokenizationInfoAdapter) captureResponse = processKremlinTokenizationInfo(kremlinTokenizationInfoAdapter, captureResponse)

        if (captureResponse.creditCardTransactionLogIdList) creditCardTransactionLogIdList.addAll(captureResponse.creditCardTransactionLogIdList)

        return captureResponse
    }

    private Map captureWithTokenizedCreditCard(Installment installment, Payment payment, BillingInfo billingInfo, Customer customer, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, List<Long> creditCardTransactionLogIdList, Boolean isFallback) {
        Map captureResponse = [success: false]

        billingInfoService.invalidateTokenIfNecessary(billingInfo)

        final Boolean isCardTokenizedOnKremlin = billingInfo.creditCardInfo.gateway.isKremlin()
        final Boolean shouldUseKremlinForAcquiringToken = billingInfo.creditCardInfo.gateway.isAcquiring() && !featureFlagCacheService.isEnabled(FeatureFlagName.CREDIT_CARD_GATEWAY_ACQUIRING)
        CreditCardGateway creditCardGateway = getCreditCardGateway(billingInfo, billingInfo.creditCardInfo.brand, installment, customer, false, false)

        CreditCardAcquirer acquirer
        if (creditCardGateway.isAcquiring()) acquirer = getAcquirerForAcquiring(customer, billingInfo, billingInfo.creditCardInfo.brand, installment)

        if ((isCardTokenizedOnKremlin && !creditCardGateway.isAcquiring()) || shouldUseKremlinForAcquiringToken) {
            CreditCard creditCard = getCreditCardOnKremlin(billingInfo)

            if (creditCard) {
                captureResponse = captureWithCreditCardInfo(installment, payment, creditCard, customer, holderInfo, originInterface, creditCardTransactionLogIdList, isFallback)
            } else {
                captureResponse.notAvailable = true
            }
        } else {
            if ((creditCardGateway.isAdyen() || acquirer?.isAdyen()) && customer.hasMcc(EconomicActivity.PHARMACY_OR_DRUG_STORE_MCC)) {
                AsaasLogger.warn("CreditCardAuthorizationService.captureWithTokenizedCreditCard >>> Transação não processada pois é um token da ADYEN de cliente de farmácia [paymentId: ${payment.id} - billingInfoId: ${billingInfo.id}].")
            } else {
                captureResponse = processCapture(creditCardGateway, acquirer, installment, payment, null, billingInfo, holderInfo, originInterface, isFallback)

                if (captureResponse.creditCardTransactionLogIdList) creditCardTransactionLogIdList.addAll(captureResponse.creditCardTransactionLogIdList)
            }
        }

        if (isFallback) captureResponse.fallbackBillingInfoHasBeenUsed = true

        return captureResponse
    }

    private Map processCaptureResponse(Map captureResponse, Customer customer, Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, List<Long> creditCardTransactionLogIdList, Boolean enableFallback) {
        warnIfCustomerBlockedAtAcquirer(captureResponse, customer.id)

        if (canExecuteFallback(customer, creditCard, billingInfo, enableFallback, captureResponse)) {
            Boolean hasCreditCardInfoToFallback = true

            final Boolean shouldGetCreditCardOnKremlin = billingInfo?.creditCardInfo?.gateway?.isKremlin() || (billingInfo?.creditCardInfo?.gateway?.isAcquiring() && !featureFlagCacheService.isEnabled(FeatureFlagName.CREDIT_CARD_GATEWAY_ACQUIRING))
            if (shouldGetCreditCardOnKremlin) {
                creditCard = getCreditCardOnKremlin(billingInfo)

                if (creditCard) {
                    creditCard.gateway = captureResponse.gateway
                } else {
                    hasCreditCardInfoToFallback = false
                }
            }

            if (hasCreditCardInfoToFallback) {
                Map fallbackResponse = capture(installment, payment, creditCard, billingInfo?.fallbackBillingInfo, holderInfo, originInterface, false, true)

                if (!fallbackResponse.notAvailable) {
                    captureResponse = fallbackResponse
                }

                if (fallbackResponse.creditCardTransactionLogIdList) creditCardTransactionLogIdList.addAll(fallbackResponse.creditCardTransactionLogIdList)
            }
        }

        captureResponse.creditCardTransactionLogIdList = creditCardTransactionLogIdList

        if (captureResponse.success) {
            CardTransactionCapturedRawInfoVO cardTransactionCapturedRawInfoVO = new CardTransactionCapturedRawInfoVO(payment, captureResponse)
            cardTransactionCapturedRawInfoService.saveWithNewTransaction(CardTransactionCapturedRawInfoCardType.CREDIT, cardTransactionCapturedRawInfoVO)
        }

        return captureResponse
    }

    private Boolean canExecuteFallback(Customer customer, CreditCard creditCard, BillingInfo billingInfo, Boolean enableFallback, Map captureResponse) {
        if (captureResponse.success) return false
        if (!captureResponse.forceFallbackEnabled && !enableFallback) return false
        if (captureResponse.blockedOnBlackList) return false

        final Boolean isCardInfo = creditCard || billingInfo?.creditCardInfo?.gateway?.isKremlin() || billingInfo?.creditCardInfo?.gateway?.isAcquiring()
        if (captureResponse.gateway?.isAcquiring() && isCardInfo) return false

        CreditCardBrand creditCardBrand = creditCard ? creditCard.brand : billingInfo.creditCardInfo.brand

        final CreditCardGateway creditCardInfoFallbackGateway = isCardInfo ? selectFallbackGateway(captureResponse.gateway, creditCardBrand) : null
        final Boolean hasCreditCardInfoToFallback = (isCardInfo && creditCardInfoFallbackGateway) || (billingInfo?.fallbackBillingInfo && !billingInfo?.fallbackBillingInfo?.creditCardInfo?.gateway?.isCieloApplicable())
        if (!hasCreditCardInfoToFallback) return false

        String creditCardBin = creditCard ? creditCard.buildBin() : billingInfo.creditCardInfo.bin
        if (!CreditCard.shouldExecuteFallback(creditCardBrand, creditCardBin, captureResponse.acquirerReturnCode.toString())) return false

        if (creditCard && hasForcedGateway(customer)) return false
        if (creditCard && creditCardInfoFallbackGateway.isAdyen() && customer.hasMcc(EconomicActivity.PHARMACY_OR_DRUG_STORE_MCC)) return false

        return true
    }

    private Map processAuthorize(CreditCardGateway creditCardGateway, CreditCardAcquirer acquirer, Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, CreditCardTransactionDeviceInfoVO deviceInfo, Boolean withThreeDSecure, Boolean isFallback) {
        switch (creditCardGateway) {
            case CreditCardGateway.CIELO:
                return cieloCreditCardService.authorize(installment, payment, creditCard, billingInfo, isFallback)
            case CreditCardGateway.ADYEN:
                return adyenCreditCardService.authorize(installment, payment, creditCard, billingInfo, isFallback)
            case CreditCardGateway.REDE:
                return redeCreditCardService.authorize(installment, payment, creditCard, deviceInfo, withThreeDSecure, isFallback)
            case CreditCardGateway.ACQUIRING:
                return acquiringCreditCardService.authorize(acquirer, installment, payment, creditCard, billingInfo, holderInfo, originInterface, isFallback).properties
            default:
                throw new RuntimeException("Sem pré-autorização configurada para o gateway ${creditCardGateway.name()}.")
        }
    }

    private Map processCaptureForPreviousAuthorization(CreditCardGateway creditCardGateway, CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        switch (creditCardGateway) {
            case CreditCardGateway.CIELO:
                return cieloCreditCardService.capture(creditCardAuthorizationInfo, isFallback)
            case CreditCardGateway.ADYEN:
                return adyenCreditCardService.capturePreviouslyAuthorized(creditCardAuthorizationInfo, true, null, false)
            case CreditCardGateway.REDE:
                return redeCreditCardService.capture(creditCardAuthorizationInfo, isFallback)
            case CreditCardGateway.ACQUIRING:
                return acquiringCreditCardService.capture(creditCardAuthorizationInfo, isFallback).properties
            default:
                throw new RuntimeException("Sem pré-autorização configurada para o gateway ${creditCardGateway.name()}.")
        }
    }

    private Map processKremlinTokenizationInfo(TokenizationInfoAdapter kremlinTokenizationInfoAdapter, Map transactionResponse) {
        if (transactionResponse.success) {
            transactionResponse.tokenizationGateway = CreditCardGateway.KREMLIN
            transactionResponse.instantBuyKey = kremlinTokenizationInfoAdapter.token
            transactionResponse.billingInfoPublicId = kremlinTokenizationInfoAdapter.billingInfoPublicId
        } else {
            kremlinCreditCardService.deleteTokenizedCreditCard(kremlinTokenizationInfoAdapter.token, kremlinTokenizationInfoAdapter.billingInfoPublicId)
        }

        return transactionResponse
    }

    private void warnIfCustomerBlockedAtAcquirer(Map captureResponse, Long customerId) {
        if (captureResponse.success) return
        if (!captureResponse.acquirer?.isRede()) return
        if (captureResponse.acquirerReturnCode != CUSTOMER_BLOCKED_BY_REDE_RETURN_CODE) return

        AsaasLogger.warn("CreditCardAuthorizationService.warnIfCustomerBlockedAtAcquirer() -> Transação com cartão de crédido negada. Cliente bloqueado na REDE. [customerId: ${customerId}]")
    }

    private CreditCardAcquirer getAcquirerForAcquiring(Customer customer, BillingInfo billingInfo, CreditCardBrand brand, Installment installment) {
        if (billingInfo && !billingInfo.creditCardInfo.gateway.isAcquiring() && !billingInfo.creditCardInfo.gateway.isKremlin()) return getAcquirerForGateway(billingInfo.creditCardInfo.gateway)

        CreditCardAcquirer acquirer

        String acquirerAsString = CustomerParameter.getStringValue(customer, CustomerParameterName.ACQUIRER_FOR_ACQUIRING)
        if (acquirerAsString) acquirer = CreditCardAcquirer.parse(acquirerAsString)

        if (!acquirer && customer.hasMcc(EconomicActivity.PHARMACY_OR_DRUG_STORE_MCC)) {
            if (!brand.isRedeApplicable()) throw new UnsupportedCreditCardBrandException("Bandeira [${brand}] não suportada na REDE.")
            acquirer = CreditCardAcquirer.REDE
        }

        if (!acquirer) {
            CreditCardGateway gateway = getCreditCardGateway(billingInfo, brand, installment, customer, true, true)
            if (gateway && !gateway.isAcquiring()) acquirer = getAcquirerForGateway(gateway)
        }

        if (acquirer && !acquirer?.isAcquiringApplicable()) throw new RuntimeException("Adquirente ${acquirer} não suportada no Acquiring.")

        return acquirer
    }

    private CreditCardAcquirer getAcquirerForGateway(CreditCardGateway gateway) {
        switch (gateway) {
            case CreditCardGateway.ADYEN:
                return CreditCardAcquirer.ADYEN
            case CreditCardGateway.REDE:
                return CreditCardAcquirer.REDE
            case CreditCardGateway.CIELO:
            case CreditCardGateway.MUNDIPAGG:
            case CreditCardGateway.CYBERSOURCE:
                return CreditCardAcquirer.CIELO
        }

        throw new RuntimeException("Adquirente não identificada para o gateway ${gateway}.")
    }

    private CreditCardGateway getRedeAsForcedGateway(CreditCardBrand brand, Boolean isExternalToken) {
        if (!isExternalToken && !brand.isRedeApplicable()) throw new UnsupportedCreditCardBrandException("Bandeira [${brand}] não suportada na REDE.")

        return CreditCardGateway.REDE
    }

    private CreditCardGateway getGatewayForThreeDSecure() {
        return CreditCardGateway.REDE
    }
}
