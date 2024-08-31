package com.asaas.service.unsubscribe

import com.asaas.unsubscribeEmail.UnsubscribedEmailSource
import grails.transaction.Transactional

@Transactional
class UnsubscribeService {

    def hubspotContactService

    public void executeAccountDisableExternalUnsubscribeWithNewThread(String email) {
        executeExternalUnsubscribeWithNewThread(email, UnsubscribedEmailSource.ACCOUNT_DISABLED)
    }

    public void executeReferralExternalUnsubscribeWithNewThread(String email) {
        executeExternalUnsubscribeWithNewThread(email, UnsubscribedEmailSource.REFERRAL)
    }

    public void executeCustomerLostRecoveryExternalUnsubscribeWithNewThread(String email) {
        executeExternalUnsubscribeWithNewThread(email, UnsubscribedEmailSource.CUSTOMER_LOST_RECOVERY)
    }

    private void executeExternalUnsubscribeWithNewThread(String email, UnsubscribedEmailSource source) {
        hubspotContactService.saveUnsubscribe(email, source)
    }
}
