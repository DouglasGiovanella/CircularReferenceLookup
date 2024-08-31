package com.asaas.service.integration.heimdall

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.thirdpartydocumentationonboarding.ThirdPartyDocumentationOnboardingDTO
import com.asaas.log.AsaasLogger
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingRequestAdapter
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingAdapter
import com.asaas.thirdpartydocumentationonboarding.dto.HeimdallThirdPartyDocumentationOnboardingRequestDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class ThirdPartyDocumentationOnboardingManagerService {

    public ThirdPartyDocumentationOnboardingAdapter request(ThirdPartyDocumentationOnboardingRequestAdapter requestAdapter) {
        if (!AsaasEnvironment.isProduction()) {
            ThirdPartyDocumentationOnboardingDTO onboardingDTO = new MockJsonUtils("heimdall/ThirdPartyDocumentationOnboardingManagerService/requestOnboardingData.json").buildMock(ThirdPartyDocumentationOnboardingDTO)
            return new ThirdPartyDocumentationOnboardingAdapter(onboardingDTO)
        }

        HeimdallThirdPartyDocumentationOnboardingRequestDTO requestDTO = new HeimdallThirdPartyDocumentationOnboardingRequestDTO(requestAdapter)

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/externalOnboarding/account/${requestAdapter.customer.id}", requestDTO)

        if (!heimdallManager.isSuccessful()) {
            AsaasLogger.error("Heimdall retornou um status diferente de sucesso ao requisitar dados para onboarding terceirizado")
            return null
        }

        ThirdPartyDocumentationOnboardingDTO onboardingDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), ThirdPartyDocumentationOnboardingDTO)

        return new ThirdPartyDocumentationOnboardingAdapter(onboardingDTO)
    }

    public ThirdPartyDocumentationOnboardingAdapter getLastExternalLink(Long accountId) {
        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/externalOnboarding/getLastExternalLink/$accountId", null)

        if (!heimdallManager.isSuccessful()) {
            AsaasLogger.error("Heimdall retornou um status diferente de sucesso ao buscar último onboarding tercerizado. AccountId: [${accountId}]")
            return null
        }

        ThirdPartyDocumentationOnboardingDTO onboardingDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), ThirdPartyDocumentationOnboardingDTO)

        return new ThirdPartyDocumentationOnboardingAdapter(onboardingDTO)
    }

    public ThirdPartyDocumentationOnboardingAdapter enableThirdPartyDocumentationOnboardingToReprocess(Long customerId, Long thirdPartyOnboardingId) {
        Map params = [accountId: customerId, externalOnboardingId: thirdPartyOnboardingId]

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/externalOnboarding/enableToReprocessReady", params)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                throw new RuntimeException("ThirdPartyOnboarding não localizado CustomerID [${customerId}] ThirdPartyOnboardingID [${thirdPartyOnboardingId}]")
            }

            throw new RuntimeException(heimdallManager.responseBody.message)
        }

        ThirdPartyDocumentationOnboardingDTO onboardingDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), ThirdPartyDocumentationOnboardingDTO)
        return new ThirdPartyDocumentationOnboardingAdapter(onboardingDTO)
    }

    public void invalidateLast(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return

        Map params = [accountId: customerId]

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/externalOnboarding/invalidateLast", params)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("ThirdPartyDocumentationOnboardingManagerService.invalidateLast >> Último onboarding externo não localizado CustomerID [${customerId}]")
                return
            }

            throw new RuntimeException(heimdallManager.responseBody.message)
        }
    }
}
