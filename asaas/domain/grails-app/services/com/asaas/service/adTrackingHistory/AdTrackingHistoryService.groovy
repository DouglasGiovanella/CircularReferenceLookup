package com.asaas.service.adTrackingHistory

import com.asaas.domain.adTrackingHistory.AdTrackingHistory
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class AdTrackingHistoryService {

    private static final TRACKS_FOR_NEW_CUSTOMER_RELEASE_DATE = CustomDateUtils.fromString("20/08/2021 16:00", "dd/MM/yyyy HH:mm")

    public void saveGoogleConversion(Customer customer, String name) {
		save(customer, name)
	}

    public Boolean shouldTrackFirstPaymentReceived(Customer customer) {
        if (customerAlreadyTracked(customer, AdTrackingHistory.FIRST_PAYMENT_RECEIVED_GOOGLE_CONVERSION)) return false

        if (!customer.hasReceivedPayments()) return false

        return true
    }

    public Boolean shouldTrackFirstPaymentReceivedInThreeDays(Customer customer) {
        if (customerAlreadyTracked(customer, AdTrackingHistory.FIRST_PAYMENT_RECEIVED_IN_THREE_DAYS_GOOGLE_CONVERSION)) return false

        if (!hasCustomerReceivedPaymentInThreeDays(customer)) return false

        return true
    }

    public Boolean shouldTrackAccountActivation(Customer customer) {
        if (customer.dateCreated < TRACKS_FOR_NEW_CUSTOMER_RELEASE_DATE) return false

        if (customerAlreadyTracked(customer, AdTrackingHistory.ACCOUNT_ACTIVATION_GOOGLE_CONVERSION)) return false

        if (!customer.getIsActive()) return false

        return true
    }

    public Boolean shouldTrackFirstCreatedPayment(Customer customer){
        if (customer.dateCreated < TRACKS_FOR_NEW_CUSTOMER_RELEASE_DATE) return false

        if (customerAlreadyTracked(customer, AdTrackingHistory.FIRST_CREATED_PAYMENT_GOOGLE_CONVERSION)) return false

        if (!customer.getFirstCreatedPayment().asBoolean()) return false

        return true
    }

    public Boolean shouldTrackDocumentSentToAnalysis(Customer customer) {
        if (customer.dateCreated < TRACKS_FOR_NEW_CUSTOMER_RELEASE_DATE) return false

        if (customerAlreadyTracked(customer, AdTrackingHistory.DOCUMENT_SENT_TO_ANALYSIS_GOOGLE_CONVERSION)) return false

        if (customer.customerRegisterStatus.documentStatus == Status.AWAITING_APPROVAL) return true

        return false
    }

    private Boolean customerAlreadyTracked(Customer customer, String name) {
        return AdTrackingHistory.query([exists: true, customer: customer, name: name]).get().asBoolean()
    }

    private Boolean hasCustomerReceivedPaymentInThreeDays(Customer customer) {
        Date customerDateCreatedPlusThreeDays = CustomDateUtils.addBusinessDays(customer.dateCreated, 3)
        return Payment.received([exists: true, customer: customer, "paymentDate[le]": customerDateCreatedPlusThreeDays]).get().asBoolean()
    }

    private void save(Customer customer, String name) {
        AdTrackingHistory adTrackingHistory = AdTrackingHistory.query([customer: customer, name: name]).get()
        if (!adTrackingHistory){
            adTrackingHistory = new AdTrackingHistory(customer: customer, name: name).save(failOnError: true)
        }
    }
}
