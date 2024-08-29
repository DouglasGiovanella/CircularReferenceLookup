package com.asaas.service.integration.pix

import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.enums.policy.PolicyType

import grails.transaction.Transactional

@Transactional
class HermesHealthCheckManagerService {

    public Boolean healthCheck() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/healthCheck", [:])

        if (hermesManager.isSuccessful()) {
            return true
        }

        return false
    }

    public Boolean checkCreditTransactionQueueDelay() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkCreditTransactionQueueDelay", [:])

        if (hermesManager.isSuccessful()) {
            return true
        }

        return false
    }

    public Boolean checkQrCodeWebhookQueueDelay() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkQrCodeWebhookQueueDelay", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkQrCodeWebhookQueueStopped() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkQrCodeWebhookQueueStopped", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkTransactionValidationQueueDelay() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkTransactionValidationQueueDelay", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkTransactionValidationQueueStopped() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkTransactionValidationQueueStopped", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkPolicy(PolicyType type) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkPolicy?type=${type.toString()}", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkCreditTransactionQueueStopped() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkCreditTransactionQueueStopped", [:])

        if (hermesManager.isSuccessful()) {
            return true
        }

        return false
    }

    public Boolean checkRequestedTransactionQueueDelay() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkRequestedTransactionQueueDelay", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkAccountConfirmedFraudQueueDelay() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkAccountConfirmedFraudQueueDelay", [:])

        return hermesManager.isSuccessful()
    }

    public Boolean checkRefundRequestReversalQueueDelay() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/healthCheck/checkRefundRequestReversalQueueDelay", [:])

        return hermesManager.isSuccessful()
    }

}
