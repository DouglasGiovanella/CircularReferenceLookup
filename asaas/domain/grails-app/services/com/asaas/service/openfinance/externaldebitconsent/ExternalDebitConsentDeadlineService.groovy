package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentInfo
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ExternalDebitConsentDeadlineService {

    def externalDebitConsentService

    public void expireDebitConsentThatReachedAuthorizationDateLimit() {
        List<Long> externalDebitConsentIdList = ExternalDebitConsent.reachedAuthorizationDateLimit([column: "id", ignoreCustomer: true]).list(max: 500)

        for (Long consentId : externalDebitConsentIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ExternalDebitConsent externalDebitConsent = ExternalDebitConsent.get(consentId)
                externalDebitConsentService.expireByAuthorizationTimeExceeded(externalDebitConsent)
            }, [logErrorMessage: "ExpireExternalDebitConsentService.expireDebitConsentThatReachedAuthorizationDateLimit >> Falha ao expirar consentimento [${consentId}]"])
        }
    }

    public void expireDebitConsentThatReachedConsumptionDateLimit() {
        List<Long> externalDebitConsentIdList = ExternalDebitConsent.reachedConsumptionDateLimit([column: "id", ignoreCustomer: true]).list(max: 500)

        for (Long consentId : externalDebitConsentIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ExternalDebitConsent externalDebitConsent = ExternalDebitConsent.get(consentId)
                externalDebitConsentService.expireByConsumptionTimeExceeded(externalDebitConsent)
            }, [logErrorMessage: "ExpireExternalDebitConsentService.expireDebitConsentThatReachedConsumptionDateLimit >> Falha ao expirar consentimento [${consentId}]"])
        }
    }

    public void consumeDebitConsentThatReachedExpirationDateLimit() {
        List<Long> externalDebitConsentIdList = ExternalAutomaticDebitConsentInfo.query([column: "externalDebitConsent.id", "expirationDate[lt]": new Date(), "externalDebitConsentStatus[in]": ExternalDebitConsentStatus.getStatusesCanBeConsumedByReachedExpirationDateLimit()]).list(max: 500)

        for (Long externalDebitConsentId : externalDebitConsentIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ExternalDebitConsent externalDebitConsent = ExternalDebitConsent.get(externalDebitConsentId)
                externalDebitConsentService.consume(externalDebitConsent)
            })
        }
    }
}
