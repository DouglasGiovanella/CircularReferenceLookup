package com.asaas.service.cybersource

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.creditcard.CreditCardUtils
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.cybersource.CyberSourceManager
import com.asaas.integration.cybersource.dto.AuthorizationDTO
import com.asaas.integration.cybersource.dto.RefundDTO
import com.asaas.integration.cybersource.dto.TokenizationDTO
import com.asaas.integration.cybersource.enums.CybersourceTransactionStatus
import com.asaas.utils.MoneyUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CyberSourceCreditCardService {

    def creditCardTransactionLogService
    def creditCardBlackListService
    def cyberSourceTransactionRefundService
    def billingInfoService

    public Map capture(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
        if (!AsaasConfig.getInstance().useMundiPaggProduction) {
            return buildSimulationAuthorizationResponse(payment, installment ? installment.getRemainingValue() : payment.value, isFallback)
        }

        BillingInfo billingInfoToBeUsed
        List<Long> tokenizationTransactionLogIdList

        if (creditCard) {
            CustomerAccount customerAccount = payment?.customerAccount ?: installment.payments[0].customerAccount

            Map tokenizationResultMap = tokenize(creditCard, customerAccount, true, isFallback)

            if (tokenizationResultMap.success) {
                creditCard.token = tokenizationResultMap.instantBuyKey
                creditCard.gateway = CreditCardGateway.CYBERSOURCE
                creditCard.customerToken = tokenizationResultMap.customerToken
                String billingInfoPublicId = UUID.randomUUID()

                billingInfoToBeUsed = billingInfoService.save(BillingType.MUNDIPAGG_CIELO, customerAccount, creditCard, null, billingInfoPublicId)

                tokenizationTransactionLogIdList = tokenizationResultMap.creditCardTransactionLogIdList

                Thread.sleep(2000)
            } else {
                return [success: false, messsage: tokenizationResultMap.errorMessage, creditCardTransactionLogIdList: tokenizationResultMap.creditCardTransactionLogIdList]
            }
        } else {
            billingInfoToBeUsed = billingInfo
        }

        Map captureResponseMap = captureWithBillingInfo(installment, payment, billingInfoToBeUsed, creditCard?.ccv, isFallback)
        if (tokenizationTransactionLogIdList && captureResponseMap.creditCardTransactionLogIdList) captureResponseMap.creditCardTransactionLogIdList.addAll(0, tokenizationTransactionLogIdList)

        return captureResponseMap
    }

    public Map refundWithAuthorizationInfo (Customer customer, CustomerAccount customerAccount, String transactionIdentifier) {
        CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.query([transactionIdentifier: transactionIdentifier]).get()

        if (!creditCardAuthorizationInfo) return [success: false]

        return refund(customer, customerAccount, creditCardAuthorizationInfo.requestKey, creditCardAuthorizationInfo.amountInCents, creditCardAuthorizationInfo.transactionReference, creditCardAuthorizationInfo.transactionIdentifier, creditCardAuthorizationInfo.payment)
    }

    public Map refund(Customer customer, CustomerAccount customerAccount, String requestKey, Long amountInCents, String transactionReference, String transactionIdentifier, Payment payment) {
        if (!AsaasEnvironment.isProduction()) return [success: true]

        CybersourceTransactionStatus cybersourceTransactionStatus = queryTransactionStatus(customer, customerAccount, requestKey, amountInCents, transactionIdentifier, payment)

        if (cybersourceTransactionStatus in [CybersourceTransactionStatus.VOIDED, CybersourceTransactionStatus.REFUNDED]) {
            return [success: true]
        }

        Map resultMap = refundTransaction(requestKey, amountInCents, transactionReference)

        Map gatewayInfo = buildGatewayInfo(resultMap.statusCode, resultMap.responseJson, amountInCents, transactionIdentifier)
        gatewayInfo.message = resultMap.errorMessage

        creditCardTransactionLogService.save(customer, customerAccount, payment, CreditCardTransactionEvent.REFUND, resultMap.success, gatewayInfo)

        return [success: resultMap.success]
    }

    public Map tokenize(CreditCard creditCard, CustomerAccount customerAccount, Boolean bypassValidate, Boolean isFallback) {
        if (!AsaasEnvironment.isProduction()) {
            return mockTokenize(customerAccount)
        }

        if (!bypassValidate && !validateCreditCard(creditCard, customerAccount)) {
            return [success: false, errorMessage: Utils.getMessageProperty("creditCard.number.error.simulate"), creditCardTransactionLogIdList: null]
        }

        String tokenizationPath = "/tms/v1/paymentinstruments"

        TokenizationDTO tokenizationDTO = new TokenizationDTO(creditCard, customerAccount)

        Map tokenizationDataMap = tokenizationDTO.buildDataMap()

        CyberSourceManager cybersourceManager = new CyberSourceManager()
        cybersourceManager.post(tokenizationPath, tokenizationDataMap)

        Long creditCardTransactionLogId = saveTokenizationLog(cybersourceManager, customerAccount, creditCard.buildBin(), isFallback)

        if (cybersourceManager.isSuccessful()) {
            return [success: true, instantBuyKey: cybersourceManager.getResponseBody()?.id, customerToken: customerAccount.id, creditCardTransactionLogIdList: [creditCardTransactionLogId]]
        }

        return [success: false, errorMessage: "Transação não autorizada.", creditCardTransactionLogIdList: [creditCardTransactionLogId]]
    }

    private Boolean validateCreditCard(CreditCard creditCard, CustomerAccount customerAccount) {
        if (!AsaasEnvironment.isProduction()) {
            return true
        }

        CreditCardBrand creditCardBrand = CreditCardUtils.getCreditCardBrand(creditCard.number)

        BigDecimal transactionValue
        if (creditCardBrand in CreditCardBrand.supportsZeroDollarAuth()) {
            transactionValue = 0
        } else {
            transactionValue = 0.01
        }

        Map authorizationMap = processAuthorization(customerAccount.provider, customerAccount, creditCard, null, transactionValue, null, null, true, null, false)
        if (authorizationMap.success && transactionValue > 0) cyberSourceTransactionRefundService.save(customerAccount.provider, customerAccount, authorizationMap)

        return authorizationMap.success
    }

    private Map captureWithBillingInfo(Installment installment, Payment payment, BillingInfo billingInfo, String ccv, Boolean isFallback) {
        Customer customer = installment?.provider ?: payment.provider
        CustomerAccount customerAccount = installment?.customerAccount ?: payment.customerAccount
        BigDecimal totalAmount = installment ? installment.getRemainingValue() : payment.value

        return processAuthorization(customer, customerAccount, null, billingInfo, totalAmount, installment, payment, false, ccv, isFallback)
    }

    private Map processAuthorization(Customer customer, CustomerAccount customerAccount, CreditCard creditCard, BillingInfo billingInfo, BigDecimal totalAmount, Installment installment, Payment payment, Boolean isCreditCardValidation, String ccv, Boolean isFallback) {
        String capturePath = "/pts/v2/payments/"

        String softDescriptor
        if (installment) {
            softDescriptor = installment.payments[0].buildSoftDescriptorText()
        } else if (payment) {
            softDescriptor = payment.buildSoftDescriptorText()
        } else {
            softDescriptor = CreditCardUtils.buildSoftDescriptorText(customer)
        }

        AuthorizationDTO authorization = new AuthorizationDTO(customer, customerAccount, creditCard, billingInfo, totalAmount, softDescriptor, installment, payment, ccv)

        Map autorizationDataMap = authorization.buildDataMap()

        CyberSourceManager cyberSourceManager = new CyberSourceManager()
        cyberSourceManager.post(capturePath, autorizationDataMap)

        Map result = buildAuthorizationResponseMap(cyberSourceManager, customerAccount, creditCard, billingInfo, totalAmount, installment, payment, isCreditCardValidation, softDescriptor, isFallback)

        if (!result.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, billingInfo, result.message, result.returnCode)
            result.blockedOnBlackList = blockedOnBlackList
        }

        return result
    }

    private Map buildAuthorizationResponseMap(CyberSourceManager cyberSourceManager, CustomerAccount customerAccount, CreditCard creditCard, BillingInfo billingInfo, BigDecimal totalAmount, Installment installment, Payment payment, Boolean isCreditCardValidation, String softDescriptor, Boolean isFallback) {
        CybersourceTransactionStatus responseStatus = CybersourceTransactionStatus.convert(cyberSourceManager.getResponseBody()?.status)

        String requestKey = cyberSourceManager.getResponseBody()?.id
        String transactionKey = cyberSourceManager.getResponseBody()?.reconciliationId
        String transactionIdentifier = cyberSourceManager.getResponseBody()?.processorInformation?.transactionId
        String transactionReference = cyberSourceManager.getResponseBody()?.clientReferenceInformation?.code
        String acquirerReturnCode = cyberSourceManager.getResponseBody()?.processorInformation?.responseCode

        Long amountInCents = MoneyUtils.valueInCents(totalAmount)

        Map responseMap = [:]

        String errorMessage

        if (responseStatus?.isAuthorized()) {
            CreditCardAuthorizationInfo authorizationInfo
            if (!isCreditCardValidation) {
                Map creditCardAuthorizationInfoMap = [installment: installment, payment: payment, transactionReference: transactionReference, transactionIdentifier: transactionIdentifier, transactionKey: transactionKey, requestKey: requestKey, amountInCents: amountInCents]

                if (billingInfo) {
                    creditCardAuthorizationInfoMap.billingInfoId = billingInfo.id
                    creditCardAuthorizationInfoMap.creditCardBin = billingInfo.creditCardInfo.bin
                } else {
                    creditCardAuthorizationInfoMap.billingInfoId = creditCard.billingInfo ? creditCard.billingInfo.id : null
                    creditCardAuthorizationInfoMap.creditCardBin = creditCard.buildBin()
                }

                creditCardAuthorizationInfoMap.gateway = CreditCardGateway.CYBERSOURCE

                authorizationInfo = new CreditCardAuthorizationInfo(creditCardAuthorizationInfoMap)
                authorizationInfo.save(failOnError: true)
            }

            responseMap.success = true
            responseMap.message = responseStatus.toString()
            responseMap.transactionIdentifier = transactionIdentifier
            responseMap.instantBuyKey = billingInfo?.creditCardInfo?.buildToken()
            responseMap.amountInCents = amountInCents
            responseMap.transactionReference = transactionReference
            responseMap.acquirer = CreditCardAcquirer.CIELO
            responseMap.gateway = CreditCardGateway.CYBERSOURCE
            responseMap.customerToken = customerAccount.id
            responseMap.authorizationInfo = authorizationInfo
            responseMap.requestKey = requestKey
            responseMap.returnCode = acquirerReturnCode
            responseMap.acquirerReturnCode = acquirerReturnCode
        } else {
            errorMessage = parseErrorMessage(cyberSourceManager)

            responseMap.success = false
            responseMap.returnCode = acquirerReturnCode
            responseMap.acquirerReturnCode = acquirerReturnCode
            responseMap.message = "${cyberSourceManager.getResponseBody()?.status} : ${errorMessage}"
        }

        Map gatewayInfo = buildGatewayInfo(cyberSourceManager.statusCode, cyberSourceManager.responseJson, amountInCents, transactionIdentifier)
        gatewayInfo.message = errorMessage

        if (billingInfo) {
            gatewayInfo.billingInfoId = billingInfo.id
            gatewayInfo.creditCardBin = billingInfo.creditCardInfo.bin
        } else {
            gatewayInfo.billingInfoId = creditCard.billingInfo ? creditCard.billingInfo.id : null
            gatewayInfo.creditCardBin = creditCard.buildBin()
        }

        gatewayInfo.softDescriptor = softDescriptor
        gatewayInfo.fallback = isFallback

        CreditCardTransactionEvent transactionEvent = (totalAmount > 0) ? CreditCardTransactionEvent.CAPTURE : CreditCardTransactionEvent.AUTHORIZATION
        Long creditCardTransactionLogId = creditCardTransactionLogService.save(customerAccount.provider, customerAccount, payment, transactionEvent, responseMap.success, gatewayInfo)

        responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])

        return responseMap
    }

    private CybersourceTransactionStatus queryTransactionStatus(Customer customer, CustomerAccount customerAccount, String requestKey, Long amountInCents, String transactionIdentifier, Payment payment) {
        if (!AsaasEnvironment.isProduction()) {
            return mockQueryTransactionStatus()
        }
        String transactionDetailPath = "/tss/v2/transactions/${requestKey}"

        CyberSourceManager cybersourceManager = new CyberSourceManager()
        cybersourceManager.get(transactionDetailPath, null)

        Map gatewayInfo = buildGatewayInfo(cybersourceManager.statusCode, cybersourceManager.responseJson, amountInCents, transactionIdentifier)

        if (!cybersourceManager.isSuccessful()) {
            gatewayInfo.message = parseErrorMessage(cybersourceManager)
        }

        creditCardTransactionLogService.save(customer, customerAccount, payment, CreditCardTransactionEvent.QUERY, (cybersourceManager.statusCode == 200), gatewayInfo)

        return CybersourceTransactionStatus.convert(cybersourceManager.getResponseBody()?.applicationInformation?.status)
    }

    private Map refundTransaction(String requestKey, Long amountInCents, String transactionReference) {
        if (!AsaasEnvironment.isProduction()) {
            return mockRefundTransaction()
        }
        String refundPath = "/pts/v2/payments/${requestKey}/refunds"

        BigDecimal value = amountInCents / 100

        RefundDTO refundDTO = new RefundDTO(transactionReference, value)

        Map refundDataMap = refundDTO.buildDataMap()

        CyberSourceManager cybersourceManager = new CyberSourceManager()
        cybersourceManager.post(refundPath, refundDataMap)

        String errorMessage
        if (!cybersourceManager.isSuccessful()) {
            errorMessage = parseErrorMessage(cybersourceManager)
        }

        return [success: cybersourceManager.isSuccessful(), responseJson: cybersourceManager.responseJson, statusCode: cybersourceManager.statusCode, errorMessage: errorMessage]
    }

    private Map buildGatewayInfo(Integer statusCode, String responseJson, Long amountInCents, String transactionIdentifier) {
        Map gatewayInfo = [:]
        gatewayInfo.httpStatus = statusCode
        gatewayInfo.apiResponseJson = responseJson
        gatewayInfo.amountInCents = amountInCents
        gatewayInfo.transactionIdentifier = transactionIdentifier
        gatewayInfo.gateway = CreditCardGateway.CYBERSOURCE

        return gatewayInfo
    }

    private Long saveTokenizationLog(CyberSourceManager cybersourceManager, CustomerAccount customerAccount, String creditCardBin, Boolean isFallback) {
        String errorMessage
        if (!cybersourceManager.isSuccessful()) {
            errorMessage = parseErrorMessage(cybersourceManager)
        }

        Map tokenizationInfoMap = [:]
        tokenizationInfoMap.event = CreditCardTransactionEvent.TOKENIZE
        tokenizationInfoMap.responseJson = cybersourceManager.responseJson
        tokenizationInfoMap.httpStatus = cybersourceManager.statusCode
        tokenizationInfoMap.providerId = customerAccount.provider.id
        tokenizationInfoMap.customerAccountId = customerAccount.id
        tokenizationInfoMap.gateway = CreditCardGateway.CYBERSOURCE
        tokenizationInfoMap.creditCardBin = creditCardBin
        tokenizationInfoMap.message = errorMessage
        tokenizationInfoMap.fallback = isFallback

        return creditCardTransactionLogService.save(tokenizationInfoMap)
    }

    private String parseErrorMessage(CyberSourceManager cyberSourceManager) {
        String errorMessage

        if (cyberSourceManager.getResponseBody()?.errorInformation) {
            errorMessage = "${cyberSourceManager.getResponseBody()?.errorInformation?.reason} : ${cyberSourceManager.getResponseBody()?.errorInformation?.message}"
        } else if (cyberSourceManager.getResponseBody()?.reason) {
            errorMessage = "${cyberSourceManager.getResponseBody()?.reason} : ${cyberSourceManager.getResponseBody()?.message}"
        }

        return errorMessage
    }

    private Map buildSimulationAuthorizationResponse(Payment payment, Double value, Boolean isFallback) {
        Map responseMap = [:]
        Boolean isErrorSimulate = false

        if (isErrorSimulate) {

            Boolean isBlackListSimulate = false
            String refuseMessage = isBlackListSimulate ? "INSUFFICIENT_FUND : Decline - Insufficient funds in the account." : "Cartão sem saldo"

            CreditCard creditCard = new CreditCard([holderName: "FAUSTO SILVA",
                                                    number: "5555306219370001",
                                                    expiryMonth: "12",
                                                    expiryYear: "28",
                                                    ccv: "999",
                                                    lastDigits: "0001",
                                                    brand: CreditCardBrand.VISA,
                                                    gateway: CreditCardGateway.CYBERSOURCE])

            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, null, refuseMessage, null)

            responseMap.put("blockedOnBlackList", blockedOnBlackList)
            responseMap.put("success", false)
            responseMap.put("message", refuseMessage)
            responseMap.put("errorMessage", refuseMessage)
            responseMap.put("gateway", CreditCardGateway.CYBERSOURCE)
        } else {
            responseMap.put("success", true)
            responseMap.put("transactionIdentifier", UUID.randomUUID().toString())
            responseMap.put("instantBuyKey", UUID.randomUUID().toString())
            responseMap.put("amountInCents", (value * 100).round())
            responseMap.put("transactionReference", UUID.randomUUID().toString())
            responseMap.put("acquirer", CreditCardAcquirer.CIELO)
            responseMap.put("gateway", CreditCardGateway.CYBERSOURCE)
            responseMap.put("customerToken", payment.customerAccount?.id)
        }

        responseMap.put("fallback", isFallback)

        Long creditCardTransactionLogId = creditCardTransactionLogService.save(payment.provider, payment.customerAccount, payment, CreditCardTransactionEvent.CAPTURE, responseMap.success, responseMap)

        responseMap.put("creditCardTransactionLogIdList", [creditCardTransactionLogId])

        return responseMap
    }

    private CybersourceTransactionStatus mockQueryTransactionStatus() {
        return CybersourceTransactionStatus.TRANSMITTED
    }

    private Map mockRefundTransaction() {
        return [success: true, responseJson: "", statusCode: 200, errorMessage: null]
    }

    private Map mockTokenize(CustomerAccount customerAccount) {
        return [success: true, instantBuyKey: UUID.randomUUID().toString(), customerToken: customerAccount.id, creditCardTransactionLogIdList: null]
    }

}
