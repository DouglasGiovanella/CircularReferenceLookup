package com.asaas.service.featureflag

import com.asaas.domain.featureflag.FeatureFlag
import com.asaas.featureflag.FeatureFlagName

import grails.transaction.Transactional

@Transactional
class FeatureFlagService {

    def featureFlagCacheService

    public FeatureFlag save(FeatureFlagName name, Boolean enabled) {
        featureFlagCacheService.evictIsEnabled(name)

        FeatureFlag featureFlag = FeatureFlag.query([name: name]).get()
        if (!featureFlag) {
            featureFlag = new FeatureFlag()
            featureFlag.name = name
        }
        featureFlag.enabled = enabled
        featureFlag.save(failOnError: true)

        return featureFlag
    }

    public Boolean isNotificationDispatcherOutboxEnabled() {
        return featureFlagCacheService.isEnabled(FeatureFlagName.NOTIFICATION_DISPATCHER_OUTBOX)
    }

    public Boolean isNotificationRequestExternalProcessingEnabled() {
        return featureFlagCacheService.isEnabled(FeatureFlagName.NOTIFICATION_REQUEST_EXTERNAL_PROCESSING)
    }

    public Boolean isNotificationDispatcherSyncNewCustomerEnabled() {
        return featureFlagCacheService.isEnabled(FeatureFlagName.NOTIFICATION_DISPATCHER_SYNC_NEW_CUSTOMER)
    }

    public Boolean isJobExecutionHistoryLogEnabled() {
        return featureFlagCacheService.isEnabled(FeatureFlagName.JOB_EXECUTION_HISTORY_LOG)
    }

    public Boolean isServerCheckInJobTriggerLogEnabled() {
        return featureFlagCacheService.isEnabled(FeatureFlagName.SERVER_CHECK_IN_JOB_TRIGGER_LOG)
    }

    public Boolean isReceivableHubOutboxEnabled() {
        return featureFlagCacheService.isEnabled(FeatureFlagName.RECEIVABLE_HUB_OUTBOX)
    }
}
