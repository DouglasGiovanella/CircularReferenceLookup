package com.asaas.service.api

import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiCustomerAlertNotificationParser
import com.asaas.api.ApiCustomerRegisterStatusParser
import com.asaas.api.ApiInternalOnboardingParser
import com.asaas.asaascard.AsaasCardBrand
import com.asaas.asaascard.AsaasCardStatus
import com.asaas.asaascard.AsaasCardType
import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAlertNotification
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import com.asaas.utils.AbTestUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiHomeService extends ApiBaseService {

    def accountActivationRequestService
    def apiAsaasCardService
    def apiNetPromoterScoreService
    def apiResponseBuilderService
    def criticalActionNotificationService
    def customerAcquisitionChannelService
    def dashboardStatisticsService
    def doublePromotionService
    def onboardingService
    def productPromotionService
    def userPasswordExpirationScheduleService

    def show(params) {
        Customer customer = getProviderInstance(params)
        User currentUser = UserUtils.getCurrentUser()

        Map responseMap = [:]

        if (currentUser.hasFinancialModulePermission()) {
            Boolean shouldRequestAccountActivation = accountActivationRequestService.getCurrentActivationStep(customer).asBoolean()

            responseMap.accountBalance = FinancialTransaction.getCustomerBalance(customer.id)
            responseMap.shouldRequestAccountActivation = shouldRequestAccountActivation

            responseMap.criticalActionInfo = ApiCriticalActionParser.buildExtraDataItem(customer)
            responseMap.pendingCriticalActionInfo = buildPendingCriticalActionInfo(customer)

            responseMap.internalOnboardingStep = ApiInternalOnboardingParser.buildStep(customer, shouldRequestAccountActivation)?.toString()
            responseMap.registerStatus = ApiCustomerRegisterStatusParser.buildResponseItem(customer)
            responseMap.netPromoterScore = apiNetPromoterScoreService.buildNetPromoterScoreResponseItem(customer)

            responseMap.isCustomerAcquisitionChannelAnswerMandatory = customerAcquisitionChannelService.isAnswerMandatory(customer)

            responseMap.notificationsNotDisplayedCount = CustomerAlertNotification.notDisplayed([customer: customer, column: "id", "alertType[in]": ApiCustomerAlertNotificationParser.listAppSupportedTypes()]).count()

            responseMap.asaasCardCreditId = AsaasCard.query([customer: customer, column: "id", type: AsaasCardType.CREDIT, "status[in]": [AsaasCardStatus.BLOCKED, AsaasCardStatus.ACTIVE]]).get()

            responseMap.asaasCardPromotionBoxInfo = apiAsaasCardService.buildAsaasCardPromotionBoxInfoMap(customer)

            AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.findLatest(customer)
            responseMap.hasAuthorizationDeviceUpdateRequestInAnalysis = authorizationDeviceUpdateRequest && authorizationDeviceUpdateRequest.status.isAwaitingApprovalOrRejected()

            responseMap.hasEloCardNotActivated = AsaasCard.awaitingActivation([customer: customer, exists: true, brand: AsaasCardBrand.ELO]).get().asBoolean()

            responseMap.isEligibleForMigrateAsaasCardToCredit = productPromotionService.isEligibleForMigrateAsaasCardToCredit(customer)
            responseMap.isEligibleForRequestAsaasCreditCard = productPromotionService.isEligibleForRequestAsaasCreditCard(customer)
        }

        if (AbTestUtils.hasAllowPaymentCreationWithoutDocumentSentVariantB(customer)) {
            responseMap.hasOnboardingCompleted = true
        } else {
            responseMap.hasOnboardingCompleted = !onboardingService.hasAnyMandatoryStepPending(customer)
        }

        Map expirationPasswordModalData = userPasswordExpirationScheduleService.getExpirationPasswordAlertData(currentUser, Utils.toBoolean(params.hasDisplayedFirstPasswordExpirationAlert), Utils.toBoolean(params.hasDisplayedSecondPasswordExpirationAlert))
        responseMap.passwordExpiration = [
            shouldDisplayFirstAlert: expirationPasswordModalData.shouldShowFirstAlert,
            shouldDisplaySecondAlert: expirationPasswordModalData.shouldShowSecondAlert,
            remainingDaysForExpiration: expirationPasswordModalData.daysToExpirePassword
        ]

        if (!Utils.toBoolean(params.suppressStatistics)) {
            Calendar startDate = Calendar.getInstance()
            startDate.add(Calendar.MONTH, -1)

            responseMap.dashboardStatistics = dashboardStatisticsService.getStatistics([providerId: customer.id, startDate: startDate.getTime(), finishDate: new Date()])
        }

        responseMap.promotionInfo = buildPromotionInfo(customer)

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    private Map buildPromotionInfo(Customer customer) {
        Map promotionConfigMap = [:]
        promotionConfigMap.canSeeDoublePromotionV2 = doublePromotionService.canSeeDoublePromotionV2(customer)
        promotionConfigMap.hasActivatedDoublePromotionV2 = doublePromotionService.hasActivatedDoublePromotionV2(customer.id)

        return promotionConfigMap
    }

    private Map buildPendingCriticalActionInfo(Customer customer) {
        List<CriticalAction> criticalActions = CriticalAction.pendingOrAwaitingAuthorization([customer: customer, order: "desc"]).list(max: 50, readOnly: true)
        if (!criticalActions) return null

        Map criticalActionsGroupedByType = criticalActions.groupBy { it.type }
        Boolean hasOnlyOneCriticalActionType = criticalActionsGroupedByType.size() == 1 || criticalActions.every { it.type.isPixTransactionCheckout() }

        Map fields = [:]

        if (hasOnlyOneCriticalActionType) {
            fields.type = criticalActions.first().type.toString()
            fields.totalCount = criticalActionsGroupedByType.values().collect { it.size() }.sum()
        } else {
            fields.totalCount = criticalActionsGroupedByType.values().flatten().size()
        }

        List<CriticalAction> checkoutCriticalActions = criticalActions.findAll { it.type.isCheckoutTransaction() }
        fields.totalValue = criticalActionNotificationService.buildCheckoutValue(checkoutCriticalActions)
        fields.hasMore = criticalActions.totalCount > criticalActions.size()

        return fields
    }
}
