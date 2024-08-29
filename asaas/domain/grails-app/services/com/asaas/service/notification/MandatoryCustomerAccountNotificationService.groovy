package com.asaas.service.notification

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.customeraccount.CustomerAccountParameterName
import com.asaas.domain.customer.Customer
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customeraccount.CustomerAccountParameter
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.notification.Notification
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationType
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationSchedule
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import grails.transaction.Transactional

@Transactional
class MandatoryCustomerAccountNotificationService {

    def receivableAnticipationPartnerConfigService
    def notificationService

    public void createNotificationsForCustomerAccountIfNecessary(ReceivableAnticipation anticipation) {
        Integer installmentCount = anticipation.installment?.installmentCount ?: null
        if (!shouldEnableNotificationsOnCreditReceivableAnticipation(anticipation.customer, anticipation.customerAccount.cpfCnpj, anticipation.billingType, anticipation.value, installmentCount)) return
        if (hasAnyMandatoryNotification(anticipation.customerAccount)) return

        enableCustomerAccountNotificationsUpdate(anticipation.customerAccount)
        AsaasApplicationHolder.grailsApplication.mainContext.customerAccountService.createNotificationsForCustomerAccount(anticipation.customerAccount)
        disableCustomerAccountNotificationsUpdate(anticipation.customerAccount)
    }

    public Boolean shouldEnableNotificationsOnCreditReceivableAnticipation(Customer customer, String customerAccountCpfCnpj, BillingType billingType, BigDecimal anticipatedValue, Integer installmentCount) {
        if (!isMandatoryNotificationApplicableForCustomer(customer)) return false
        if (!billingType.isBoletoOrPix()) return false
        ReceivableAnticipationPartner receivableAnticipationPartner = receivableAnticipationPartnerConfigService.getReceivableAnticipationPartner(customer, installmentCount ? ChargeType.INSTALLMENT : ChargeType.DETACHED, customerAccountCpfCnpj, billingType, anticipatedValue)
        if (!receivableAnticipationPartner.isVortx()) return false
        return true
    }

    public Boolean isMandatoryNotificationApplicableForCustomer(Customer customer) {
        if (customer.accountOwner) return false
        if (CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) return false
        return true
    }

    public void enableCustomerAccountPhoneCallNotificationIfNecessary(CustomerAccount customerAccount) {
        if (!hasAnyMandatoryNotification(customerAccount)) return

		BigDecimal customerParamOffset = CustomerParameter.getNumericValue(customerAccount.provider, CustomerParameterName.OVERDUE_NOTIFICATION_AFTER_OFFSET)
		Integer scheduleOffset = customerParamOffset ? customerParamOffset.toInteger() : Notification.DEFAULT_OVERDUE_NOTIFICATION_AFTER_OFFSET

        enableCustomerAccountNotificationsUpdate(customerAccount)
        notificationService.saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, NotificationType.PHONE_CALL, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)
        notificationService.saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, NotificationType.PHONE_CALL, NotificationReceiver.CUSTOMER, true, NotificationSchedule.AFTER, scheduleOffset)
        disableCustomerAccountNotificationsUpdate(customerAccount)
    }

    public Boolean hasAnyMandatoryNotification(CustomerAccount customerAccount){
        Boolean hasUpdateBlockOverridden = CustomerAccountParameter.query([customerAccountId: customerAccount.id, name: CustomerAccountParameterName.OVERRIDE_NOTIFICATION_UPDATE_BLOCK]).get()?.parseBooleanValue()
        if (hasUpdateBlockOverridden) return false

        return Notification.query([exists: true, customerAccount: customerAccount, updateBlocked: true]).get().asBoolean()
    }

    public void enableCustomerAccountNotificationsUpdateIfPossible(CustomerAccount customerAccount){
        Boolean customerAccountHasAnticipationCredited = ReceivableAnticipation.query([exists: true, customer: customerAccount.provider, customerAccount: customerAccount, "partnerAcquisition[isNotNull]": true, billingType: BillingType.BOLETO, status: ReceivableAnticipationStatus.CREDITED]).get().asBoolean()
        if (customerAccountHasAnticipationCredited) return

        Boolean customerAccountHasPartnerSettlementAwaitingCredit = ReceivableAnticipationPartnerSettlement.awaitingCredit([exists: true, customer: customerAccount.provider, customerAccount: customerAccount]).get().asBoolean()
        if (customerAccountHasPartnerSettlementAwaitingCredit) return

        enableCustomerAccountNotificationsUpdate(customerAccount)
    }

    private void enableCustomerAccountNotificationsUpdate(CustomerAccount customerAccount){
        List<Notification> notificationList = Notification.query([customerAccount: customerAccount]).list()

        for (Notification notification : notificationList) {
            notification.updateBlocked = false
            notification.save(failOnError: true)
        }
    }

    private void disableCustomerAccountNotificationsUpdate(CustomerAccount customerAccount){
        List<Notification> notificationList = Notification.query([customerAccount: customerAccount]).list()

        for (Notification notification : notificationList) {
            notification.updateBlocked = true
            notification.save(failOnError: true)
        }
    }
}
