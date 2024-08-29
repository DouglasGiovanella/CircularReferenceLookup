package com.asaas.service.onboarding

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingRequestAdapter
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ThirdPartyDocumentationOnboardingService {

    def abTestService
    def asyncActionService
    def customerProofOfLifeMigrationService
    def grailsApplication
    def thirdPartyDocumentationOnboardingManagerService
    def urlShortenerService

    public void processInvalidateLast() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.INVALIDATE_LAST_THIRD_PARTY_DOCUMENTATION_ONBOARDING, maxItemsPerCycle)
        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                thirdPartyDocumentationOnboardingManagerService.invalidateLast(asyncActionData.customerId)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ThirdPartyDocumentationOnboardingService.processInvalidateLast >> Erro ao invalidar último onboarding externo. AsyncActionId: ${asyncActionData.asyncActionId} | CustomerId: ${asyncActionData.customerId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void invalidateLastThirdPartyDocumentationOnboarding(Long customerId) {
        Map asyncActionData = [customerId: customerId]
        AsyncActionType asyncActionType = AsyncActionType.INVALIDATE_LAST_THIRD_PARTY_DOCUMENTATION_ONBOARDING

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public String requestThirdPartyDocumentationOnboardingShortenedUrl(ThirdPartyDocumentationOnboardingRequestAdapter requestAdapter) {
        ThirdPartyDocumentationOnboardingAdapter onboardingAdapter = request(requestAdapter)

        if (!onboardingAdapter) throw new BusinessException("Link para envio de documentos indisponivel, tente novamente em alguns minutos.")

        Map params = [url: onboardingAdapter.url]
        return urlShortenerService.createShortenedUrl("thirdPartyOnboarding", "redirectToThirdPartyDocumentationOnboardingUrl", onboardingAdapter.externalOnboardingId.toString(), params, true)
    }

    private ThirdPartyDocumentationOnboardingAdapter request(ThirdPartyDocumentationOnboardingRequestAdapter requestAdapter) {
        customerProofOfLifeMigrationService.migrateToSelfieFromCustomerIfPossible(requestAdapter.customer)

        requestAdapter.abTestNameKey = drawAbTestAndGetNameKeyIfPossible(requestAdapter)
        ThirdPartyDocumentationOnboardingAdapter onboardingAdapter = thirdPartyDocumentationOnboardingManagerService.request(requestAdapter)
        if (!onboardingAdapter) {
            AsaasLogger.warn("ThirdPartyDocumentationOnboardingService.request >> Cliente não possui dados de onboarding externo. customerId [${requestAdapter.customer.id}]")
            return null
        }

        return onboardingAdapter
    }

    private String drawAbTestAndGetNameKeyIfPossible(ThirdPartyDocumentationOnboardingRequestAdapter requestAdapter) {
        Customer customer = requestAdapter.customer

        final Date dateToStartDraw = CustomDateUtils.fromString("27/08/2024 16:00:00", "dd/MM/yyyy HH:mm:ss")
        if (customer.dateCreated.before(dateToStartDraw)) return null

        if (!requestAdapter.isWeb) return null

        final String thirdPartyDocumentationOnboardingTextAdjustmentsAbTestNameKey = "thirdPartyDocumentationOnboardingTextAdjustments"
        String thirdPartyDocumentationOnboardingTextAdjustmentsAbTestName = grailsApplication.config.asaas.abtests[thirdPartyDocumentationOnboardingTextAdjustmentsAbTestNameKey].name
        String variant = abTestService.chooseVariant(thirdPartyDocumentationOnboardingTextAdjustmentsAbTestName, requestAdapter.customer, AbTestPlatform.WEB)
        if (variant != grailsApplication.config.asaas.abtests.variantB) return null

        return thirdPartyDocumentationOnboardingTextAdjustmentsAbTestNameKey
    }
}
