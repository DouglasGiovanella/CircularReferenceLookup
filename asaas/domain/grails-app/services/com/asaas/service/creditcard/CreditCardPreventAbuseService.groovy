package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardPreventAbuseVO
import com.asaas.creditcard.CreditCardTransactionAttemptType
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.creditcard.HolderInfo
import com.asaas.customer.CustomerCreditCardAbusePreventionParameterName
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.billinginfo.CreditCardInfoCde
import com.asaas.domain.creditcard.CreditCardBlackList
import com.asaas.domain.creditcard.CreditCardTransactionAttempt
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerCreditCardAbusePreventionParameter
import com.asaas.domain.customer.CustomerRegisterStatus
import com.asaas.environment.AsaasEnvironment
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

import java.util.regex.Pattern

@Transactional
class CreditCardPreventAbuseService {

    def customerCreditCardAbusePreventionParameterService

    public CreditCardPreventAbuseVO validate(Customer customer, CustomerAccount customerAccount, CreditCard creditCard, BillingInfo billingInfo, CreditCardTransactionAttemptType creditCardTransactionAttemptType, String remoteIp, String payerRemoteIp, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, BigDecimal value) {
        CreditCardPreventAbuseVO creditCardPreventAbuseVO = new CreditCardPreventAbuseVO(creditCard, billingInfo)

        if (!AsaasEnvironment.isProduction()) return creditCardPreventAbuseVO

        validateIfCieloTokenWasInvalidated(billingInfo, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfCreditCardBinDisabled(customer, creditCard, billingInfo, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfCreditCardOnBlackList(creditCard, billingInfo, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfExceedCieloMaxAttemptForSameTokenizedCardAndValue(customer, billingInfo, creditCardPreventAbuseVO, creditCardTransactionAttemptType, value)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        if (creditCardTransactionAttemptType.isTokenizedCreditCard() && AsaasEnvironment.isJobServer()) return creditCardPreventAbuseVO

        if (customer.isAsaasProvider()) return creditCardPreventAbuseVO

        validatePayerInfo(customer, customerAccount, creditCard, creditCardPreventAbuseVO, creditCardTransactionAttemptType)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateHolderInfo(customer, holderInfo, creditCardPreventAbuseVO, creditCardTransactionAttemptType, originInterface)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        if (customer.isAsaasMoneyProvider()) return creditCardPreventAbuseVO

        validateIfCustomerCreatedForCreditCardValidation(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfTheCriticalBlockLimitHasBeenExceeded(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        if (!creditCardTransactionAttemptType.isTokenization()) {
            validateIfExceededAuthorizedTransactionsLimitForSameCard(customer, creditCardPreventAbuseVO)
            if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO
        }

        if (creditCardTransactionAttemptType.isTokenization()) {
            validateIfExceededSameCardAuthorizedTokenizationTransactionsLimit(customer, creditCard, creditCardPreventAbuseVO)
            if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO
        }

        if (originInterface?.isApi()) {
            validateIfExceededMinimumApprovalPercentageLimit(customer, creditCardPreventAbuseVO)
            if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO
        }

        if (!creditCardTransactionAttemptType.isTokenizedCreditCard()) {
            validateIfPayerDistinctIpExceeded(customer, creditCardPreventAbuseVO, payerRemoteIp)
            if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO
        }

        validateIfTransactionsLimitForSameCreditCardExceeded(customer, creditCard, creditCardPreventAbuseVO, creditCardTransactionAttemptType)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateTransactionsSameCreditCardInTwentyFourHoursExceeded(customer, creditCard, creditCardPreventAbuseVO, creditCardTransactionAttemptType)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateTransactionsExactlySameCreditCardNotAuthorized(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfExceededDeniedAttemptInMinutes(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateCustomerNaturalPersonWithoutGeneralApproval(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateAttemptFromPaymentCampaignInvoice(customer, originInterface, creditCardPreventAbuseVO, remoteIp, payerRemoteIp)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        if (originInterface?.isInvoice()) {
            validateIfDistinctCardsLimitForSamePayerIpOnInvoiceExceeded(customer, creditCardPreventAbuseVO, payerRemoteIp)
            if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO
        }

        return creditCardPreventAbuseVO
    }

    private Date buildStartDate(Integer secondsCount) {
        return CustomDateUtils.sumSeconds(new Date(), (secondsCount * -1))
    }

    private CreditCardPreventAbuseVO validateIfCieloTokenWasInvalidated(BillingInfo billingInfo, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        if (!billingInfo) return creditCardPreventAbuseVO
        if (!billingInfo.creditCardInfo.gateway.isCieloApplicable()) return creditCardPreventAbuseVO
        if (!billingInfo.creditCardInfo.buildToken().startsWith(CreditCardInfoCde.TEXT_TO_CONCAT_ON_TOKEN_INVALIDATION)) return creditCardPreventAbuseVO

        creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Erro NQMUC0XXX1 - Cartão inválido.")

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfCreditCardBinDisabled(Customer customer, CreditCard creditCard, BillingInfo billingInfo, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        String creditCardBinDisabledToTransactionList = CustomerCreditCardAbusePreventionParameter.getStringValue(customer, CustomerCreditCardAbusePreventionParameterName.CREDIT_CARD_BIN_DISABLED_TO_TRANSACTION_LIST)

        if (creditCardBinDisabledToTransactionList) {
            String creditCardBin = creditCard ? creditCard.buildBin() : billingInfo.creditCardInfo.bin

            if (creditCardBinDisabledToTransactionList.tokenize(",").contains(creditCardBin)) {
                creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Transação não permitida pois o cliente não possui permissão para transacionar este BIN de cartão")
            }
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfCreditCardOnBlackList(CreditCard creditCard, BillingInfo billingInfo, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Long creditCardBlackListId

        if (creditCard) {
            creditCardBlackListId = CreditCardBlackList.existsWithCreditCardFullInfo(creditCard)
        } else {
            creditCardBlackListId = CreditCardBlackList.query([exists: true, token: billingInfo.creditCardInfo.buildToken()]).get()
        }

        if (creditCardBlackListId) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "O número do cartão está na blacklist.")
            creditCardPreventAbuseVO.customerErrorMessage = "Cartão com restrição"
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validatePayerInfo(Customer customer, CustomerAccount customerAccount, CreditCard creditCard, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType) {
        validateIfExceededDistinctCardsLimitForSamePayer(customer, customerAccount, creditCard, creditCardPreventAbuseVO, creditCardTransactionAttemptType)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfTransactionsSamePayerExceeded(customer, customerAccount, creditCardPreventAbuseVO, creditCardTransactionAttemptType)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededDistinctCardsLimitForSamePayer(Customer customer, CustomerAccount customerAccount, CreditCard creditCard, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType) {
        Integer distinctCardForSamePayerCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_FOR_SAME_PAYER_COUNT)
        Integer distinctCardForSamePayerPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_FOR_SAME_PAYER_PERIOD)
        Date startDate = buildStartDate(distinctCardForSamePayerPeriodParam)
        String cardIdentificationHash

        Map queryParams = [customer: customer, 'dateCreated[ge]': startDate, customerAccountId: customerAccount.id, attemptType: creditCardTransactionAttemptType]

        if (creditCardTransactionAttemptType.isTokenizedCreditCard()) {
            queryParams.distinct = "creditCardFullInfoHash"
            cardIdentificationHash = creditCardPreventAbuseVO.creditCardFullInfoHash
        } else {
            queryParams.distinct = "creditCardHash"
            cardIdentificationHash = creditCard.buildHash()
        }

        List<String> usedCreditCardHashList = CreditCardTransactionAttempt.query(queryParams).list()

        Boolean exceededDistinctCardsForSamePayer = usedCreditCardHashList.size() > distinctCardForSamePayerCountParam
        Boolean exceededNowDistinctCardsForSamePayer = (usedCreditCardHashList.size() == distinctCardForSamePayerCountParam && !usedCreditCardHashList.contains(cardIdentificationHash))

        if (exceededDistinctCardsForSamePayer || exceededNowDistinctCardsForSamePayer) {
            Boolean isCriticalBlockForSamePayerAttempt = usedCreditCardHashList.size() == distinctCardForSamePayerCountParam

            creditCardPreventAbuseVO.blockCreditCardTransaction(isCriticalBlockForSamePayerAttempt, "Excedeu o limite de cartões distintos para o mesmo pagador no período.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfTransactionsSamePayerExceeded(Customer customer, CustomerAccount customerAccount, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType) {
        Integer samePayerAttemptCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PAYER_ATTEMPT_COUNT)
        Integer samePayerAttemptPeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PAYER_ATTEMPT_PERIOD)
        Date startDate = buildStartDate(samePayerAttemptPeriod)

        Integer attemptCount = CreditCardTransactionAttempt.query([customer: customer, 'dateCreated[ge]': startDate, customerAccountId: customerAccount.id, attemptType: creditCardTransactionAttemptType]).count()

        if (attemptCount > samePayerAttemptCount) {
            Boolean isCriticalBlock = attemptCount == (samePayerAttemptCount + 1)

            creditCardPreventAbuseVO.blockCreditCardTransaction(isCriticalBlock, "Excedeu o limite de transações para o mesmo pagador no período.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateHolderInfo(Customer customer, HolderInfo holderInfo, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardTransactionOriginInterface originInterface) {
        validateIfDistinctHolderEmailForSameCardExceeded(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        if (!holderInfo) return creditCardPreventAbuseVO

        validateIfExceededSameHolderCpfCnpj(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, holderInfo)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfExceededSameHolderEmail(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, holderInfo)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfExceededSameHolderName(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, holderInfo)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        if (originInterface?.isApi()) {
            validateIfHolderEmailPatternExceededMinimumOccurrencesLimit(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, holderInfo)
            if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfDistinctHolderEmailForSameCardExceeded(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer distinctHolderEmailForSameCardPeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_HOLDER_EMAIL_FOR_SAME_CARD_PERIOD)
        Date startDate = buildStartDate(distinctHolderEmailForSameCardPeriod)

        Integer distinctHolderEmailForSameCardCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_HOLDER_EMAIL_FOR_SAME_CARD_COUNT)

        Integer distinctHolderEmailCount = CreditCardTransactionAttempt.query([countDistinct: "holderEmail", "holderEmail[isNotNull]": true, "dateCreated[ge]": startDate, creditCardFullInfoHash: creditCardPreventAbuseVO.creditCardFullInfoHash]).get()

        if (distinctHolderEmailCount >= distinctHolderEmailForSameCardCount) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de e-mails distintos para o mesmo cartão.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededSameHolderCpfCnpj(Customer customer, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardPreventAbuseVO creditCardPreventAbuseVO, HolderInfo holderInfo) {
        if (!holderInfo.cpfCnpj) return creditCardPreventAbuseVO

        Integer sameHolderCpfCnpjCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_HOLDER_CPF_CNPJ_COUNT)
        Integer sameHolderCpfCnpjPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_HOLDER_CPF_CNPJ_PERIOD)
        Date startDate = buildStartDate(sameHolderCpfCnpjPeriodParam)

        Integer creditCardTransactionAttempts = CreditCardTransactionAttempt.query([customer: customer, attemptType: creditCardTransactionAttemptType, holderCpfCnpj: holderInfo.cpfCnpj, 'dateCreated[ge]': startDate]).count()

        if (creditCardTransactionAttempts >= sameHolderCpfCnpjCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações para o portador do cartão de mesmo CPF/CNPJ.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededSameHolderEmail(Customer customer, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardPreventAbuseVO creditCardPreventAbuseVO, HolderInfo holderInfo) {
        if (!holderInfo.email) return creditCardPreventAbuseVO

        Integer sameHolderEmailCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_HOLDER_EMAIL_COUNT)
        Integer sameHolderEmailPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_HOLDER_EMAIL_PERIOD)
        Date startDate = buildStartDate(sameHolderEmailPeriodParam)

        Integer creditCardTransactionAttempts = CreditCardTransactionAttempt.query([customer: customer, attemptType: creditCardTransactionAttemptType, holderEmail: holderInfo.email, 'dateCreated[ge]': startDate]).count()

        if (creditCardTransactionAttempts >= sameHolderEmailCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações para o portador do cartão de mesmo e-mail.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededSameHolderName(Customer customer, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardPreventAbuseVO creditCardPreventAbuseVO, HolderInfo holderInfo) {
        if (!holderInfo.name) return creditCardPreventAbuseVO

        Integer sameHolderNameCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_HOLDER_NAME_COUNT)
        Integer sameHolderNamePeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_HOLDER_NAME_PERIOD)
        Date startDate = buildStartDate(sameHolderNamePeriodParam)

        Integer creditCardTransactionAttempts = CreditCardTransactionAttempt.query([customer: customer, attemptType: creditCardTransactionAttemptType, holderName: holderInfo.name, 'dateCreated[ge]': startDate]).count()

        if (creditCardTransactionAttempts >= sameHolderNameCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações para o portador do cartão de mesmo nome.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfHolderEmailPatternExceededMinimumOccurrencesLimit(Customer customer, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardPreventAbuseVO creditCardPreventAbuseVO, HolderInfo holderInfo) {
        if (!holderInfo?.email) return creditCardPreventAbuseVO
        if (!hasPatternOnHolderEmail(holderInfo.email)) return creditCardPreventAbuseVO

        Integer samePatternHolderEmailCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PATTERN_HOLDER_EMAIL_COUNT)
        Integer samePatternHolderEmailPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PATTERN_HOLDER_EMAIL_PERIOD)
        Integer samePatternHolderEmailMinimumOccurrencesParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PATTERN_HOLDER_EMAIL_MINIMUM_OCCURRENCES)

        Date startDate = buildStartDate(samePatternHolderEmailPeriodParam)

        List<String> attemptEmailList = CreditCardTransactionAttempt.query([column: "holderEmail", customer: customer, attemptType: creditCardTransactionAttemptType, "holderEmail[isNotNull]": true, 'dateCreated[ge]': startDate, sort: "dateCreated"]).list(max: samePatternHolderEmailCountParam)

        if (attemptEmailList.size() < samePatternHolderEmailCountParam) return creditCardPreventAbuseVO

        Integer creditCardTransactionAttempts = attemptEmailList.count( { hasPatternOnHolderEmail(it) } )

        if (creditCardTransactionAttempts >= samePatternHolderEmailMinimumOccurrencesParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de vezes que o e-mail do pagador repete o padrão.")
        }

        return creditCardPreventAbuseVO
    }

    private Boolean hasPatternOnHolderEmail(String email) {
        return Pattern.compile("[0-9]{3,}@", Pattern.MULTILINE).matcher(email).find()
    }

    private CreditCardPreventAbuseVO validateIfCustomerCreatedForCreditCardValidation(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer distinctCardPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_FOR_JUST_CREATED_ACCOUNT_PERIOD)
        Date startDate = buildStartDate(distinctCardPeriodParam)

        if (customer.dateCreated > startDate && !customer.cpfCnpj) {
            Integer distinctCardCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_FOR_JUST_CREATED_ACCOUNT_COUNT)
            Integer usedCreditCardHashDistinctCount = CreditCardTransactionAttempt.query([countDistinct: "creditCardHash", customer: customer]).get()

            if (usedCreditCardHashDistinctCount >= distinctCardCountParam) {
                creditCardPreventAbuseVO.blockCustomerToUseCreditCardTransaction(false, "Cadastro criado para validação de cartões.")
            }
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfTheCriticalBlockLimitHasBeenExceeded(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        return creditCardPreventAbuseVO

        if (customer.isCreditCardTransactionBlocked()) return creditCardPreventAbuseVO

        Integer blockedTransactionCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.BLOCKED_TRANSACTION_COUNT)
        Integer blockedTransactionPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.BLOCKED_TRANSACTION_PERIOD)
        Date startDate = buildStartDate(blockedTransactionPeriodParam)

        Integer blockedTransactions = CreditCardTransactionAttempt.query([customer: customer, 'dateCreated[ge]': startDate, criticalBlock: true]).count()

        if (blockedTransactions > blockedTransactionCountParam) {
            creditCardPreventAbuseVO.blockCustomerToUseCreditCardTransaction(true, "Excedeu o limite de bloqueios críticos no período.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededAuthorizedTransactionsLimitForSameCard(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer sameCardPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_AUTHORIZED_PERIOD)
        Date startDate = buildStartDate(sameCardPeriodParam)

        Integer sameCardCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_AUTHORIZED_COUNT)

        Integer totalAttemptCount = CreditCardTransactionAttempt.query([authorized: true, "dateCreated[ge]": startDate, creditCardFullInfoHash: creditCardPreventAbuseVO.creditCardFullInfoHash, "attemptType[in]": [CreditCardTransactionAttemptType.CREDIT_CARD, CreditCardTransactionAttemptType.TOKENIZED_CREDIT_CARD]]).count()

        if (totalAttemptCount >= sameCardCount) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações autorizadas para o mesmo cartão.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededSameCardAuthorizedTokenizationTransactionsLimit(Customer customer, CreditCard creditCard, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer sameCardAttemptCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_TOKENIZED_COUNT)
        Integer sameCardAttemptPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_TOKENIZED_PERIOD)
        Date startDate = buildStartDate(sameCardAttemptPeriodParam)

        Integer sameCardAttempt = CreditCardTransactionAttempt.query([attemptType: CreditCardTransactionAttemptType.TOKENIZATION, authorized: true, customer: customer, 'dateCreated[ge]': startDate, creditCardFullInfoHash: creditCardPreventAbuseVO.creditCardFullInfoHash]).count()

        if (sameCardAttempt >= sameCardAttemptCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de tokenizações autorizadas para o mesmo cartão.")
            creditCardPreventAbuseVO.customerErrorMessage = "Cartão ${creditCard.buildHash()} já tokenizado"
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededMinimumApprovalPercentageLimit(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer approvalRatePeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.APPROVAL_RATE_PERIOD)

        if (!approvalRatePeriodParam) return creditCardPreventAbuseVO

        Integer approvalRateTransactionsCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.APPROVAL_RATE_TRANSACTIONS_COUNT)
        Date startDate = buildStartDate(approvalRatePeriodParam)

        Integer totalAttemptCount = CreditCardTransactionAttempt.query([blocked: false, customer: customer, 'dateCreated[ge]': startDate]).count()

        if (totalAttemptCount > approvalRateTransactionsCountParam) {
            Integer approvalRateMinimumValueParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.APPROVAL_RATE_MINIMUM_VALUE)

            Integer authorizedAttemptCount = CreditCardTransactionAttempt.query([authorized: true, customer: customer, 'attemptType[notIn]': [CreditCardTransactionAttemptType.TOKENIZATION], 'dateCreated[ge]': startDate]).count()

            BigDecimal approvedRatio = (authorizedAttemptCount / totalAttemptCount) * 100

            if (approvedRatio < approvalRateMinimumValueParam) {
                creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o percentual de transações não autorizadas no período.")
            }
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfPayerDistinctIpExceeded(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO, String payerRemoteIp) {
        if (!payerRemoteIp) return creditCardPreventAbuseVO

        Integer sameIpSecondsInterval = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PAYER_IP_PERIOD)
        if (!sameIpSecondsInterval) return creditCardPreventAbuseVO

        Integer distinctIpCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_PAYER_IP_COUNT)
        Date startDate = buildStartDate(sameIpSecondsInterval)

        Long attemptsWithSameIpCount = CreditCardTransactionAttempt.query(["dateCreated[ge]": startDate, payerRemoteIp: payerRemoteIp]).count()

        if (attemptsWithSameIpCount >= distinctIpCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de IPs distintos do pagador no período.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfTransactionsLimitForSameCreditCardExceeded(Customer customer, CreditCard creditCard, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType) {
        Integer sameCardAttemptCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_ATTEMPT_COUNT)
        Integer sameCardAttemptPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_ATTEMPT_PERIOD)
        Date startDate = buildStartDate(sameCardAttemptPeriodParam)

        Map queryParams = [customer: customer, attemptType: creditCardTransactionAttemptType, "dateCreated[ge]": startDate]
        if (creditCardTransactionAttemptType.isTokenizedCreditCard()) {
            queryParams.creditCardFullInfoHash = creditCardPreventAbuseVO.creditCardFullInfoHash
        } else {
            queryParams.creditCardHash = creditCard.buildHash()
        }

        Integer sameCardAttempt = CreditCardTransactionAttempt.query(queryParams).count()

        if (sameCardAttempt > sameCardAttemptCountParam) {
            Boolean isCriticalBlockForSameCardAttempt = sameCardAttempt == (sameCardAttemptCountParam + 1)

            creditCardPreventAbuseVO.blockCreditCardTransaction(isCriticalBlockForSameCardAttempt, "Excedeu o limite de transações para o mesmo cartão no período.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateTransactionsSameCreditCardInTwentyFourHoursExceeded(Customer customer, CreditCard creditCard, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType) {
        Date startDate = CustomDateUtils.sumHours(new Date(), -24)

        Integer sameCardAttemptTwentyFourHourCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_ATTEMPT_LAST_24_HOUR_COUNT)

        Map queryParams = [customer: customer, attemptType: creditCardTransactionAttemptType, "dateCreated[ge]": startDate]
        if (creditCardTransactionAttemptType.isTokenizedCreditCard()) {
            queryParams.creditCardFullInfoHash = creditCardPreventAbuseVO.creditCardFullInfoHash
        } else {
            queryParams.creditCardHash = creditCard.buildHash()
        }

        Integer sameCardAttemptCountInLastTwentyFourHours = CreditCardTransactionAttempt.query(queryParams).count()

        if (sameCardAttemptCountInLastTwentyFourHours > sameCardAttemptTwentyFourHourCount) {
            Boolean isCriticalBlockForSameCreditCardInTwentyFourHour = sameCardAttemptCountInLastTwentyFourHours == (sameCardAttemptTwentyFourHourCount + 1)

            creditCardPreventAbuseVO.blockCreditCardTransaction(isCriticalBlockForSameCreditCardInTwentyFourHour, "Excedeu o limite de transações para o mesmo cartão em menos de 24 horas.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateTransactionsExactlySameCreditCardNotAuthorized(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer sameCardAttemptCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.EXACTLY_SAME_CARD_ATTEMPT_NOT_AUTHORIZED_COUNT)
        Integer sameCardAttemptPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.EXACTLY_SAME_CARD_ATTEMPT_NOT_AUTHORIZED_PERIOD)
        Date startDate = buildStartDate(sameCardAttemptPeriodParam)

        Integer sameCardAttempt = CreditCardTransactionAttempt.query([customer: customer, 'dateCreated[ge]': startDate, authorized: false, creditCardFullInfoHash: creditCardPreventAbuseVO.creditCardFullInfoHash]).count()

        if (sameCardAttempt >= sameCardAttemptCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de reprovações para o mesmo cartão (incluindo expiracao e ccv) no período.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededDeniedAttemptInMinutes(Customer customer, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Boolean bypassAttemptsDeniedExceeded = CustomerCreditCardAbusePreventionParameter.getValue(customer, CustomerCreditCardAbusePreventionParameterName.BYPASS_ATTEMPTS_DENIED_EXCEEDED)
        if (bypassAttemptsDeniedExceeded) return creditCardPreventAbuseVO

        validateIfAttemptsDeniedNumberExceededInMinutes(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, 24, 2)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfAttemptsDeniedNumberExceededInMinutes(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, 40, 4)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfAttemptsDeniedNumberExceededInMinutes(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, 60, 6)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfAttemptsDeniedNumberExceededInMinutes(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, 75, 10)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfAttemptsDeniedNumberExceededInMinutes(customer, creditCardTransactionAttemptType, creditCardPreventAbuseVO, 120, 20)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfAttemptsDeniedNumberExceededInMinutes(Customer customer, CreditCardTransactionAttemptType creditCardTransactionAttemptType, CreditCardPreventAbuseVO creditCardPreventAbuseVO, Integer numberAttempts, Integer minutes) {
        Date startDate = CustomDateUtils.sumMinutes(new Date(), minutes * -1)

        Integer creditCardTransactionAttempts = CreditCardTransactionAttempt.query([customer: customer, blocked: false, authorized: false, 'dateCreated[ge]': startDate, attemptType: creditCardTransactionAttemptType]).count()

        if (creditCardTransactionAttempts >= numberAttempts) {
            creditCardPreventAbuseVO.blockCustomerToUseCreditCardTransaction(false, "Aumento significativo no volume: Excedidas ${numberAttempts} transações negadas em ${minutes} minutos.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateCustomerNaturalPersonWithoutGeneralApproval(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        if (!customer.isNaturalPerson()) return creditCardPreventAbuseVO
        if (customer.hadGeneralApproval()) return creditCardPreventAbuseVO

        validateIfCustomerExceededLimitTimeWithoutSendDocuments(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfCustomerExceededLimitTimeWithoutGeneralApproval(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfCustomerWithoutGeneralApprovalExceededAttemptsLimit(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfCustomerWithoutGeneralApprovalExceededDistinctCardsLimit(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfCustomerExceededLimitTimeWithoutSendDocuments(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Status documentStatus = CustomerRegisterStatus.query([customer: customer, column: "documentStatus"]).get()
        if (documentStatus && !documentStatus.isPending()) return creditCardPreventAbuseVO

        Integer accountCreatedWithoutSendDocumentsPeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.ACCOUNT_CREATED_WITHOUT_SEND_DOCUMENTS_PERIOD)
        Date dateLimitWithoutSendDocuments = CustomDateUtils.sumSeconds(customer.dateCreated, accountCreatedWithoutSendDocumentsPeriod)

        if (new Date() > dateLimitWithoutSendDocuments) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Cliente excede o período sem envio dos documentos.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfCustomerExceededLimitTimeWithoutGeneralApproval(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer accountCreatedWithoutGeneralApprovalPeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.ACCOUNT_CREATED_WITHOUT_GENERAL_APPROVAL_PERIOD)
        Date dateLimitWithoutGeneralApproval = CustomDateUtils.sumSeconds(customer.dateCreated, accountCreatedWithoutGeneralApprovalPeriod)

        if (new Date() > dateLimitWithoutGeneralApproval) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Cliente excede o período sem aprovação geral após o cadastro.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfCustomerWithoutGeneralApprovalExceededAttemptsLimit(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer accountWithoutGeneralApprovalAttemptLimitPeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.ACCOUNT_WITHOUT_GENERAL_APPROVAL_ATTEMPT_LIMIT_PERIOD)
        Date startDate = buildStartDate(accountWithoutGeneralApprovalAttemptLimitPeriod)

        Integer accountWithoutGeneralApprovalAttemptLimitCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.ACCOUNT_WITHOUT_GENERAL_APPROVAL_ATTEMPT_LIMIT_COUNT)
        Long attemptCount = CreditCardTransactionAttempt.query([customer: customer, "dateCreated[ge]": startDate]).count()

        if (attemptCount > accountWithoutGeneralApprovalAttemptLimitCount) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Mais de ${accountWithoutGeneralApprovalAttemptLimitCount} transações no período sem aprovação geral do cadastro.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfCustomerWithoutGeneralApprovalExceededDistinctCardsLimit(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer accountWithoutGeneralApprovalDistinctCardLimitPeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.ACCOUNT_WITHOUT_GENERAL_APPROVAL_DISTINCT_CARD_LIMIT_PERIOD)
        Date startDate = buildStartDate(accountWithoutGeneralApprovalDistinctCardLimitPeriod)

        Integer accountWithoutGeneralApprovalDistinctCardLimitCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.ACCOUNT_WITHOUT_GENERAL_APPROVAL_DISTINCT_CARD_LIMIT_COUNT)
        Integer attemptCount = CreditCardTransactionAttempt.query([countDistinct: "creditCardHash", customer: customer, "dateCreated[ge]": startDate]).get()

        if (attemptCount > accountWithoutGeneralApprovalDistinctCardLimitCount) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Mais de ${accountWithoutGeneralApprovalDistinctCardLimitCount} cartões diferentes utilizados no período sem aprovação geral do cadastro.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateAttemptFromPaymentCampaignInvoice(Customer customer, CreditCardTransactionOriginInterface originInterface, CreditCardPreventAbuseVO creditCardPreventAbuseVO, String remoteIp, String payerRemoteIp) {
        if (!originInterface?.isInvoicePaymentCampaign()) return creditCardPreventAbuseVO

        validateIfExceededSameCardAndCustomerWithPaymentCampaignInvoiceForAuthorizedTransactions(customer, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfExceededSameCardAndPayerIpWithPaymentCampaignInvoiceForAuthorizedTransactions(customer, payerRemoteIp, creditCardPreventAbuseVO)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        validateIfDistinctCardsLimitForSameIpOnPaymentCampaignExceeded(customer, creditCardPreventAbuseVO, remoteIp)
        if (creditCardPreventAbuseVO.blockCreditCardTransaction) return creditCardPreventAbuseVO

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededSameCardAndCustomerWithPaymentCampaignInvoiceForAuthorizedTransactions(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        Integer sameCardAndCustomerWithPaymentCampaignInvoicePeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_AND_CUSTOMER_AUTHORIZED_WITH_PAYMENT_CAMPAIGN_INVOICE_PERIOD)
        Date startDate = buildStartDate(sameCardAndCustomerWithPaymentCampaignInvoicePeriodParam)

        Integer sameCardAndCustomerWithPaymentCampaignInvoiceCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_AND_CUSTOMER_AUTHORIZED_WITH_PAYMENT_CAMPAIGN_INVOICE_COUNT)

        Integer totalAttemptCount = CreditCardTransactionAttempt.query([authorized: true, "dateCreated[ge]": startDate, customer: customer, creditCardFullInfoHash: creditCardPreventAbuseVO.creditCardFullInfoHash, origin: CreditCardTransactionOriginInterface.INVOICE_PAYMENT_CAMPAIGN]).count()

        if (totalAttemptCount >= sameCardAndCustomerWithPaymentCampaignInvoiceCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações para mesmo cartão e mesmo cliente no link de pagamento.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceededSameCardAndPayerIpWithPaymentCampaignInvoiceForAuthorizedTransactions(Customer customer, String payerRemoteIp, CreditCardPreventAbuseVO creditCardPreventAbuseVO) {
        if (!payerRemoteIp) return creditCardPreventAbuseVO

        Integer sameCardAndPayerIpWithPaymentCampaignInvoicePeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_AND_PAYER_IP_AUTHORIZED_WITH_PAYMENT_CAMPAIGN_INVOICE_PERIOD)
        Date startDate = buildStartDate(sameCardAndPayerIpWithPaymentCampaignInvoicePeriodParam)

        Integer sameCardAndPayerIpWithPaymentCampaignInvoiceCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_AND_PAYER_IP_AUTHORIZED_WITH_PAYMENT_CAMPAIGN_INVOICE_COUNT)

        Integer totalAttemptCount = CreditCardTransactionAttempt.query([authorized: true, "dateCreated[ge]": startDate, payerRemoteIp: payerRemoteIp, creditCardFullInfoHash: creditCardPreventAbuseVO.creditCardFullInfoHash, origin: CreditCardTransactionOriginInterface.INVOICE_PAYMENT_CAMPAIGN]).count()

        if (totalAttemptCount >= sameCardAndPayerIpWithPaymentCampaignInvoiceCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações para o mesmo cartão e mesmo IP do pagador no link de pagamento.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfDistinctCardsLimitForSameIpOnPaymentCampaignExceeded(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO, String remoteIp) {
        Integer distinctCardViaInvoicePaymentCampaignPeriodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_VIA_INVOICE_PAYMENT_CAMPAIGN_WITH_SAME_IP_PERIOD)
        Date startDate = buildStartDate(distinctCardViaInvoicePaymentCampaignPeriodParam)

        Integer distinctCardViaInvoicePaymentCampaignCountParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_VIA_INVOICE_PAYMENT_CAMPAIGN_WITH_SAME_IP_COUNT)

        Integer usedCreditCardHashDistinctCount = CreditCardTransactionAttempt.query([countDistinct: "creditCardHash", 'dateCreated[ge]': startDate, origin: CreditCardTransactionOriginInterface.INVOICE_PAYMENT_CAMPAIGN, remoteIp: remoteIp]).get()

        if (usedCreditCardHashDistinctCount >= distinctCardViaInvoicePaymentCampaignCountParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de cartões distintos no período, via link de pagamento.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfExceedCieloMaxAttemptForSameTokenizedCardAndValue(Customer customer, BillingInfo billingInfo, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType, BigDecimal value) {
        if (!creditCardTransactionAttemptType.isTokenizedCreditCard()) return creditCardPreventAbuseVO
        if (!billingInfo.creditCardInfo.gateway.isCieloApplicable()) return creditCardPreventAbuseVO
        if (!value) return creditCardPreventAbuseVO

        Integer cieloMaxAttemptForSameTokenizedCardAndValueCount = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.CIELO_MAX_ATTEMPT_FOR_SAME_TOKENIZED_CARD_AND_VALUE_COUNT)

        Integer cieloMaxAttemptForSameTokenizedCardAndValuePeriod = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.CIELO_MAX_ATTEMPT_FOR_SAME_TOKENIZED_CARD_AND_VALUE_PERIOD)
        Date startDate = buildStartDate(cieloMaxAttemptForSameTokenizedCardAndValuePeriod)

        Map queryParams = [:]
        queryParams."dateCreated[ge]" = startDate
        queryParams.authorized = false
        queryParams.blocked = false
        queryParams.creditCardHash = CreditCard.buildObfuscatedNumberHash(billingInfo.creditCardInfo.bin, billingInfo.creditCardInfo.lastDigits)
        queryParams.creditCardFullInfoHash = creditCardPreventAbuseVO.creditCardFullInfoHash
        queryParams.value = value
        Integer attemptsCount = CreditCardTransactionAttempt.query(queryParams).count()

        if (attemptsCount >= cieloMaxAttemptForSameTokenizedCardAndValueCount) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de transações na CIELO para o mesmo cartão tokenizado e valor.")
        }

        return creditCardPreventAbuseVO
    }

    private CreditCardPreventAbuseVO validateIfDistinctCardsLimitForSamePayerIpOnInvoiceExceeded(Customer customer, CreditCardPreventAbuseVO creditCardPreventAbuseVO, String payerRemoteIp) {
        if (!payerRemoteIp) return creditCardPreventAbuseVO

        Integer countParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_VIA_INVOICE_WITH_SAME_PAYER_IP_COUNT)
        Integer periodParam = customerCreditCardAbusePreventionParameterService.getParameterIntegerValue(customer, CustomerCreditCardAbusePreventionParameterName.DISTINCT_CARD_VIA_INVOICE_WITH_SAME_PAYER_IP_PERIOD)
        Date startDate = buildStartDate(periodParam)

        Integer usedCreditCardHashDistinctCount = CreditCardTransactionAttempt.query([countDistinct: "creditCardHash", 'dateCreated[ge]': startDate, origin: CreditCardTransactionOriginInterface.INVOICE, payerRemoteIp: payerRemoteIp]).get()

        if (usedCreditCardHashDistinctCount >= countParam) {
            creditCardPreventAbuseVO.blockCreditCardTransaction(false, "Excedeu o limite de cartões distintos no período, via fatura.")
        }

        return creditCardPreventAbuseVO
    }
}
