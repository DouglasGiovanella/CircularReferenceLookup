package com.asaas.service.integration.heimdall

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class HeimdallExternalOnboardingFunnelManagerService {

    public void createInFirstStep(Long externalOnboardingId) {
        if (!AsaasEnvironment.isProduction()) return

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/externalOnboardings/${externalOnboardingId}/funnels/createInFirstStep", null)

        if (!heimdallManager.isSuccessful()) {
            AsaasLogger.error("HeimdallExternalOnboardingFunnelManagerService.createInFirstStep >> ExternalOnboardingId: [$externalOnboardingId], StatusCode: [${heimdallManager.statusCode}], Message: [${heimdallManager.responseBody.message}]")
        }
    }
}
