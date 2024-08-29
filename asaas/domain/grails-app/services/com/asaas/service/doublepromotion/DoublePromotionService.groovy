package com.asaas.service.doublepromotion

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerstage.CustomerStage
import com.asaas.domain.payment.Payment
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.productpromotion.DoublePromotionModalWizardStepName
import com.asaas.productpromotion.DoublePromotionV2ModalVO
import com.asaas.productpromotion.DoublePromotionV2VO
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class DoublePromotionService {

    private static final Integer MINIMUM_NUMBER_OF_MONTHS_AFTER_ACCOUNT_CREATION = 4
    private static final Integer ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_IN_DAYS = 30
    private static final Date ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_START_DATE = CustomDateUtils.fromString("26/06/2024")
    private static final Date ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_END_DATE = CustomDateUtils.sumDays(ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_START_DATE, ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_IN_DAYS)

    def abTestService
    def asyncActionService
    def customerEventCacheService
    def grailsApplication
    def hubspotEventService
    def productPromotionService
    def promotionalCodeService

    public void drawDoublePromotionV2AbTestIfPossible(Customer customer) {
        if (new Date() > ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_END_DATE) return
        if (AbTestUtils.wasDrawnDoublePromotionV2AbTest(customer)) return
        if (!isEligibleToDraw(customer)) return

        String variantValue = abTestService.chooseVariant(grailsApplication.config.asaas.abtests.doublePromotionV2.name, customer, AbTestPlatform.ALL)
        if (variantValue == grailsApplication.config.asaas.abtests.variantB) hubspotEventService.trackCustomerFirstLoggedInDoublePromotionV2(customer)
    }

    public Boolean canSeeDoublePromotionV2(Customer customer) {
        if (!AbTestUtils.hasDoublePromotionV2VariantB(customer)) return false

        Date activationDate = ProductPromotion.getActivatedDoublePromotion(customer.id, [column: "dateCreated"]).get()
        if (activationDate) {
            Date expirationDate = CustomDateUtils.sumDays(activationDate, ProductPromotion.DAYS_TO_EXPIRE_DOUBLE_PROMOTION_V2_AFTER_ACTIVATION)
            if (expirationDate < new Date()) return false
        }

        if (!activationDate && !isInDoublePromotionV2EligiblePeriod()) return false

        return true
    }

    public void activateDoublePromotionV2(Customer customer) {
        if (!canActivateDoublePromotionV2(customer)) throw new BusinessException("Cliente não elegível para ativar a promoção dobradinha.")

        if (hasActivatedDoublePromotionV2(customer.id)) {
            AsaasLogger.error("DoublePromotionService.activateDoublePromotionV2 >> Cliente já possui promoção dobradinha ativa. CustomerId: [${customer.id}].")
            return
        }

        productPromotionService.createDoublePromotionV2(customer)
    }

    public Boolean hasValidDoublePromotionActivationV2(Customer customer) {
        if (!AbTestUtils.hasDoublePromotionV2VariantB(customer)) return false

        Date activationDate = ProductPromotion.getActivatedDoublePromotion(customer.id, [column: "dateCreated"]).get()
        if (!activationDate) return false

        Date expirationDate = CustomDateUtils.sumDays(activationDate, ProductPromotion.DAYS_TO_EXPIRE_DOUBLE_PROMOTION_V2_AFTER_ACTIVATION)
        return expirationDate > new Date()
    }

    public DoublePromotionV2VO buildDoublePromotionV2VO(Customer customer) {
        Date activationDate = ProductPromotion.getActivatedDoublePromotion(customer.id, [column: "dateCreated"]).get()
        Boolean hasActivatedDoublePromotionV2 = activationDate.asBoolean()
        Date doublePromotionV2ExpirationDate = hasActivatedDoublePromotionV2 ? CustomDateUtils.sumDays(activationDate, ProductPromotion.DAYS_TO_EXPIRE_DOUBLE_PROMOTION_V2_AFTER_ACTIVATION) : ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_END_DATE

        return new DoublePromotionV2VO(
            hasActivatedDoublePromotionV2,
            doublePromotionV2ExpirationDate,
            ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_END_DATE,
            ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_IN_DAYS
        )
    }

    public DoublePromotionV2ModalVO buildDoublePromotionV2ModalVO(DoublePromotionModalWizardStepName modalStepName, Boolean isMobile) {
        return new DoublePromotionV2ModalVO(
            ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_END_DATE,
            ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_IN_DAYS,
            modalStepName,
            isMobile
        )
    }

    public Boolean isInDoublePromotionV2EligiblePeriod() {
        Date today = new Date().clearTime()
        if (ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_START_DATE > today || ELIGIBLE_PERIOD_DOUBLE_PROMOTION_V2_END_DATE < today) return false

        return true
    }

    public Boolean hasActivatedDoublePromotionV2(Long customerId) {
        return ProductPromotion.getActivatedDoublePromotion(customerId, [exists: true]).get().asBoolean()
    }

    public Boolean hasPaymentAfterActivation(Customer customer) {
        Date activationDate = ProductPromotion.getActivatedDoublePromotion(customer.id, [column: "dateCreated"]).get()
        if (!activationDate) return false

        return Payment.query([exists: true, providerId: customer.id, "dateCreated[ge]": activationDate, billingType: BillingType.MUNDIPAGG_CIELO]).get().asBoolean()
    }

    public void saveFreePixOrBankSlipPaymentFromDoublePromotionV2(Long customerId) {
        Boolean hasAsyncActionPending = asyncActionService.hasAsyncActionPendingWithSameParameters([customerId: customerId], AsyncActionType.FREE_PIX_OR_BANK_SLIP_PAYMENT_FROM_DOUBLE_PROMOTION_V2)
        if (!hasAsyncActionPending) asyncActionService.save(AsyncActionType.FREE_PIX_OR_BANK_SLIP_PAYMENT_FROM_DOUBLE_PROMOTION_V2, [customerId: customerId])
    }

    public List<Map> listPendingFreePixOrBankSlipPaymentFromDoublePromotionV2(Integer max) {
        return asyncActionService.listPending(AsyncActionType.FREE_PIX_OR_BANK_SLIP_PAYMENT_FROM_DOUBLE_PROMOTION_V2, max)
    }

    public void givePixOrBankSlipFreePaymentFromDoublePromotionV2() {
        final Integer flushEvery = 50
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = listPendingFreePixOrBankSlipPaymentFromDoublePromotionV2(maxPendingItems)

        Utils.forEachWithFlushSession(asyncActionDataList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                promotionalCodeService.createOrUpdatePromotionalCodeFreePixOrBankSlipPaymentFromDoublePromotionV2(asyncActionData.customerId)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "DoublePromotionService.givePixOrBankSlipFreePaymentFromDoublePromotionV2 >> Erro ao dar promoção ao cliente [${asyncActionData.customerId}].",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        })
    }

    private Boolean canActivateDoublePromotionV2(Customer customer) {
        if (!isInDoublePromotionV2EligiblePeriod()) return false
        if (!AbTestUtils.hasDoublePromotionV2VariantB(customer)) return false

        return true
    }

    private Boolean isEligibleToDraw(Customer customer) {
        if (!abTestService.canDrawAbTestFollowingGrowthRules(customer)) return false

        Boolean hasMinimumPeriodAfterAccountCreated = CustomDateUtils.sumMonths(new Date(), MINIMUM_NUMBER_OF_MONTHS_AFTER_ACCOUNT_CREATION * -1) > customer.dateCreated
        if (!hasMinimumPeriodAfterAccountCreated) return false

        if (!CustomerStage.hasCashInReceived(customer.id)) return false

        if (!customerEventCacheService.hasEventCreatedPayments(customer)) return false
        if (hasCreatedCardPaymentInLastThreeMonths(customer)) return false

        return true
    }

    private Boolean hasCreatedCardPaymentInLastThreeMonths(Customer customer) {
        Date threeMonthsAgo = CustomDateUtils.sumMonths(new Date(), -3)
        return Payment.query([exists: true, customer: customer, "dateCreated[ge]": threeMonthsAgo, billingTypeList: [BillingType.MUNDIPAGG_CIELO, BillingType.DEBIT_CARD]]).get().asBoolean()
    }
}
