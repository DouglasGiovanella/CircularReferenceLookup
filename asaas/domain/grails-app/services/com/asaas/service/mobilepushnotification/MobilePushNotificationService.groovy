package com.asaas.service.mobilepushnotification

import com.asaas.api.ApiBaseParser
import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.billinginfo.BillingType
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.Referral
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.bill.Bill
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.installment.Installment
import com.asaas.domain.mobilepushnotification.MobilePushNotification
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.domain.pix.PixTransactionExternalAccount
import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.smstokenphonechangerequest.SmsTokenPhoneChangeRequest
import com.asaas.domain.user.User
import com.asaas.domain.usermobiledevice.UserMobileDevice
import com.asaas.integration.bifrost.adapter.notification.NotifyCardBillAdapter
import com.asaas.integration.bifrost.adapter.notification.NotifyTransactionAdapter
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.mobileapplicationtype.MobileApplicationType
import com.asaas.mobilepushnotification.MobileAppDeeplink
import com.asaas.mobilepushnotification.MobilePushNotificationAction
import com.asaas.mobilepushnotification.MobilePushNotificationPriority
import com.asaas.payment.PaymentStatus
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.status.Status
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.EmailUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class MobilePushNotificationService {

    public void notifyAsaasMoney(Map dataMap, Customer customer, List notificationPropertiesArguments) {
        withLogOnError({
            notifyUsers(customer, dataMap, [MobileApplicationType.MONEY], notificationPropertiesArguments, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyCommercialInfoAnalysisResult(CustomerUpdateRequest customerUpdateRequest) {
        withLogOnError({
            MobilePushNotificationPriority priority

            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.ACCOUNT_COMMERCIAL_INFO_INDEX.buildPath()

            if (customerUpdateRequest.status == Status.APPROVED) {
                dataMap.action = MobilePushNotificationAction.COMMERCIAL_INFORMATION_APPROVED
                priority = MobilePushNotificationPriority.LOW
            } else {
                dataMap.action = MobilePushNotificationAction.COMMERCIAL_INFORMATION_DENIED
                priority = MobilePushNotificationPriority.HIGH
            }

            notifyUsers(customerUpdateRequest.provider, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], null, priority)
        })
    }

    public void notifyCompulsoryCustomerRegisterUpdate(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.COMPULSORY_CUSTOMER_REGISTER_UPDATE

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyCustomerOnCommercialInfoExpiration(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.CUSTOMER_COMMERCIAL_INFO_EXPIRATION
            dataMap.deeplink = MobileAppDeeplink.ACCOUNT_COMMERCIAL_INFO_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyBankAccountAnalysisResult(BaseBankAccount bankAccountInfo) {
        withLogOnError({
            MobilePushNotificationPriority priority

            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.BANK_ACCOUNT_INDEX.buildPath()

            if (bankAccountInfo.status == Status.APPROVED) {
                dataMap.action = MobilePushNotificationAction.BANK_ACCOUNT_APPROVED
                priority = MobilePushNotificationPriority.LOW
            } else {
                dataMap.action = MobilePushNotificationAction.BANK_ACCOUNT_REJECTED
                priority = MobilePushNotificationPriority.HIGH
            }

            notifyUsers(bankAccountInfo.customer, dataMap, [MobileApplicationType.ASAAS], null, priority)
        })
    }

    public void notifyDocumentAnalysisResult(DocumentAnalysis documentAnalysis) {
        withLogOnError({
            MobilePushNotificationPriority priority

            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.ACCOUNT_DOCUMENTATION_INDEX.buildPath()

            if (documentAnalysis?.status == DocumentAnalysisStatus.APPROVED) {
                dataMap.action = MobilePushNotificationAction.DOCUMENT_ANALYSIS_APPROVED
                dataMap.isFirstDocumentAnalysisApproved = documentAnalysis.isFirstDocumentAnalysisApproved()
                priority = MobilePushNotificationPriority.LOW
            } else {
                dataMap.action = MobilePushNotificationAction.DOCUMENT_ANALYSIS_REJECTED
                priority = MobilePushNotificationPriority.HIGH
            }

            notifyUsers(documentAnalysis.customer, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], null, priority)
        })
    }

    public void notifyGeneralApprovalAnalysisResult(Customer customer) {
        withLogOnError({
            MobilePushNotificationPriority priority

            Map dataMap = [:]
            if (customer.customerRegisterStatus.generalApproval.isApproved()) {
                dataMap.action = MobilePushNotificationAction.GENERAL_APPROVAL_APPROVED
                priority = MobilePushNotificationPriority.LOW
            } else {
                dataMap.action = MobilePushNotificationAction.GENERAL_APPROVAL_REJECTED
                priority = MobilePushNotificationPriority.HIGH
            }

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], null, priority)
        })
    }

    public void notifyPaymentConfirmed(Payment payment) {
        withLogOnError({
            Map dataMap = [:]

            String value
            if (payment.installment) {
                dataMap.installmentId = payment.installment.publicId
                dataMap.action = MobilePushNotificationAction.INSTALLMENT_CONFIRMED
                dataMap.deeplink = MobileAppDeeplink.INSTALLMENT_DETAIL.buildPath([objectId: dataMap.installmentId])

                value = FormUtils.formatCurrencyWithMonetarySymbol(payment.installment.value)
            } else {
                dataMap.paymentId = payment.publicId
                dataMap.action = MobilePushNotificationAction.PAYMENT_CONFIRMED
                dataMap.deeplink = MobileAppDeeplink.PAYMENT_DETAIL.buildPath([objectId: dataMap.paymentId])

                value = FormUtils.formatCurrencyWithMonetarySymbol(payment.value)
            }

            notifyUsers(payment.provider, dataMap, [MobileApplicationType.ASAAS], [payment.customerAccount.name, value, payment.installment?.installmentCount], MobilePushNotificationPriority.MEDIUM)
        })
    }

    public void notifyFirstPaymentReceived(Payment payment) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.FIRST_PAYMENT_RECEIVED
            dataMap.paymentId = payment.publicId
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_DETAIL.buildPath([objectId: dataMap.paymentId])

            notifyUsers(payment.provider, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyForPaymentReceivedToday() {
        withLogOnError({
            List<Long> customerIdList = Payment.createCriteria().list() {
                projections {
                    distinct "provider.id"
                }

                eq("deleted", false)
                eq("status", PaymentStatus.RECEIVED)
                'in'("billingType", [BillingType.BOLETO, BillingType.MUNDIPAGG_CIELO])
                eq("paymentDate", new Date().clearTime())

                exists UserMobileDevice.where {
                    setAlias("userMobileDevice")
                    createAlias("user", "user")

                    eqProperty("user.customer.id", "this.provider.id")
                    eq("user.deleted", false)
                    eq("userMobileDevice.deleted", false)
                    eq("userMobileDevice.applicationType", MobileApplicationType.ASAAS)
                }.id()
            }

            final Integer minItemPerThread = 2000
            final Integer flushEvery = 50
            final Integer batchSize = 50

            ThreadUtils.processWithThreadsOnDemand(customerIdList, minItemPerThread, { List<Long> threadList ->
                Utils.forEachWithFlushSessionAndNewTransactionInBatch(threadList, batchSize, flushEvery, { customerId ->
                        notifyPaymentReceived(customerId)
                }, [logErrorMessage: "${ this.class.simpleName }.notifyForPaymentReceivedToday >> Erro ao enviar lote de notificações de pagamentos recebidos:",
                    appendBatchToLogErrorMessage: true
                ])
            })
        })
    }

    public void notifyTransferConfirmed(CreditTransferRequest creditTransferRequest) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.TRANSFER_CONFIRMED
            dataMap.deeplink = MobileAppDeeplink.TRANSFER_INDEX.buildPath()

            String transferValue = FormUtils.formatCurrencyWithMonetarySymbol(creditTransferRequest.value)

            notifyUsers(creditTransferRequest.provider, dataMap, [MobileApplicationType.ASAAS], [transferValue], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyBillPaid(Bill bill) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.BILL_PAID
            dataMap.deeplink = MobileAppDeeplink.BILL_DETAIL.buildPath([objectId: bill.id])

            String billValue = FormUtils.formatCurrencyWithMonetarySymbol(bill.value)

            notifyUsers(bill.customer, dataMap, [MobileApplicationType.ASAAS], [billValue], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyAsaasCardRechargeDone(AsaasCardRecharge asaasCardRecharge) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_RECHARGE_DONE
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            String rechargeValue = FormUtils.formatCurrencyWithMonetarySymbol(asaasCardRecharge.value)

            notifyUsers(asaasCardRecharge.asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], [asaasCardRecharge.asaasCard.getFormattedName(), rechargeValue], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyCriticalActionsAwaitingAuthorization(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.CRITICAL_ACTIONS_AWAITING_AUTHORIZATION
            dataMap.deeplink = MobileAppDeeplink.CRITICAL_ACTION_AUTHORIZATION_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], null, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyDeferredNotAuthorizedTransferCriticalAction(CriticalAction criticalAction) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.DEFERRED_NOT_AUTHORIZED_TRANSFER
            dataMap.deeplink = MobileAppDeeplink.CRITICAL_ACTION_AUTHORIZATION_INDEX.buildPath()

            List arguments = []
            arguments << FormUtils.formatCurrencyWithMonetarySymbol(criticalAction.transfer.value)
            arguments << AuthorizationDevice.findCurrentTypeDescription(criticalAction.customer)

            notifyUsers(criticalAction.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyNotAuthorizedTransferCriticalActions(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.NOT_AUTHORIZED_TRANSFER
            dataMap.deeplink = MobileAppDeeplink.CRITICAL_ACTION_AUTHORIZATION_INDEX.buildPath()

            List arguments = [CreditTransferRequest.LIMIT_HOUR_TO_APPROVE_TRANSFER_CRITICAL_ACTION_ON_SAME_DAY]

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyExpiredCriticalAction(CriticalAction criticalAction) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.EXPIRED_CRITICAL_ACTION
            dataMap.deeplink = MobileAppDeeplink.CRITICAL_ACTION_AUTHORIZATION_INDEX.buildPath()

            List arguments = []

            if (criticalAction.bill) {
                arguments << "O pagamento da sua conta no valor de " + FormUtils.formatCurrencyWithMonetarySymbol(criticalAction.bill.value)
            } else {
                arguments << "Seu evento crítico"
            }

            arguments << AuthorizationDevice.findCurrentTypeDescription(criticalAction.customer)

            notifyUsers(criticalAction.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyPaymentRefunded(Payment payment) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PAYMENT_REFUNDED
            dataMap.paymentId = payment.publicId
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_DETAIL.buildPath([objectId: dataMap.paymentId])

            String refundedValueFormatted = FormUtils.formatCurrencyWithMonetarySymbol(payment.value)

            notifyUsers(payment.provider, dataMap, [MobileApplicationType.ASAAS], [payment.customerAccount.name, refundedValueFormatted], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyInstallmentRefunded(Installment installment, Set<Payment> paymentsRefunded) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.INSTALLMENT_REFUNDED
            dataMap.installmentId = installment.publicId
            dataMap.deeplink = MobileAppDeeplink.INSTALLMENT_DETAIL.buildPath([objectId: dataMap.installmentId])

            String refundedValueFormatted = FormUtils.formatCurrencyWithMonetarySymbol(paymentsRefunded.sum { it.value })

            notifyUsers(installment.getProvider(), dataMap, [MobileApplicationType.ASAAS], [paymentsRefunded.size(), installment.customerAccount.name, refundedValueFormatted], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyAnticipationRequestResult(ReceivableAnticipation receivableAnticipation) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.anticipationId = receivableAnticipation.id
            dataMap.deeplink = MobileAppDeeplink.ANTICIPATION_DETAIL.buildPath([anticipationId: dataMap.anticipationId])

            switch (receivableAnticipation.status) {
                case ReceivableAnticipationStatus.DENIED:
                    dataMap.action = MobilePushNotificationAction.ANTICIPATION_DENIED
                    break
                case ReceivableAnticipationStatus.CREDITED:
                    dataMap.action = MobilePushNotificationAction.ANTICIPATION_CREDITED
                    break
            }

            notifyUsers(receivableAnticipation.customer, dataMap, [MobileApplicationType.ASAAS], [ApiBaseParser.formatDate(receivableAnticipation.dateCreated)], MobilePushNotificationPriority.MEDIUM)
        })
    }

    public void notifyCustomerWhenAnticipationIsReleased(Customer customer, BillingType billingType) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.ANTICIPATION_INDEX.buildPath()

            if (billingType.isBoleto()) {
                dataMap.action = MobilePushNotificationAction.ANTICIPATION_RELEASED_TO_BANK_SLIP
            } else if (billingType.isCreditCard()) {
                dataMap.action = MobilePushNotificationAction.ANTICIPATION_RELEASED_TO_CREDIT_CARD
            }

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyInvitedFriendRegistered(Referral referral) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.INVITED_FRIEND_REGISTERED
            dataMap.deeplink = MobileAppDeeplink.REFERRAL_INDEX.buildPath()

            notifyUsers(referral.invitedByCustomer, dataMap, [MobileApplicationType.ASAAS], [referral.invitedName], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyInvitedFriendFirstProductUse(Referral referral) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.REFERRAL_INDEX.buildPath()

            List arguments

            if (AbTestUtils.hasReferralPromotionVariantB(referral.invitedByCustomer)) {
                dataMap.action = MobilePushNotificationAction.INVITED_FRIEND_FIRST_PRODUCT_USE

                BigDecimal discountValue = PromotionalCode.query([column: "discountValue", referralId: referral.id]).get()
                String discountValueFormatted = FormUtils.formatCurrencyWithMonetarySymbol(discountValue)

                arguments = [discountValueFormatted]
            } else {
                dataMap.action = MobilePushNotificationAction.INVITED_FRIEND_FIRST_PRODUCT_USE_AWARD
                arguments = [BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS]
            }

            notifyUsers(referral.invitedByCustomer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifySmsTokenPhoneChangeRequestResult(SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest) {
        withLogOnError({
            Map dataMap = [:]

            if (smsTokenPhoneChangeRequest.status.isApproved()) {
                dataMap.action = MobilePushNotificationAction.SMS_TOKEN_PHONE_CHANGE_REQUEST_APPROVED
            } else {
                dataMap.action = MobilePushNotificationAction.SMS_TOKEN_PHONE_CHANGE_REQUEST_REJECTED
            }

            notifyUsers(smsTokenPhoneChangeRequest.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyDunningDenied(PaymentDunning paymentDunning) {
        withLogOnError({
            Map dataMap = [:]

            dataMap.action = MobilePushNotificationAction.DUNNING_DENIED
            dataMap.objectId = paymentDunning.id
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_DUNNING_DETAIL.buildPath([objectId: dataMap.objectId])

            notifyUsers(paymentDunning.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyChargebackRequested(Chargeback chargeback) {
        withLogOnError({
            Map dataMap = [:]

            dataMap.action = MobilePushNotificationAction.CHARGEBACK_REQUESTED
            dataMap.objectId = chargeback.id

            notifyUsers(chargeback.customer, dataMap, [MobileApplicationType.ASAAS], [CustomDateUtils.fromDate(chargeback.dateCreated), CustomDateUtils.fromDate(chargeback.getLastDateToSendDisputeDocuments(true))], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAuthorizationDeviceUpdateRequestSmsTokenResult(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.CRITICAL_ACTION_INDEX.buildPath()

            if (authorizationDeviceUpdateRequest.status.isApproved()) {
                dataMap.action = MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_SMS_TOKEN_APPROVED
            } else {
                dataMap.action = MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_SMS_TOKEN_REJECTED
            }

            notifyUsers(authorizationDeviceUpdateRequest.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyAuthorizationDeviceUpdateRequestMobileAppTokenResult(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, AuthorizationDevice oldDevice) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.deeplink = MobileAppDeeplink.CRITICAL_ACTION_INDEX.buildPath()

            Boolean isApproved = authorizationDeviceUpdateRequest.status.isApproved()

            if (oldDevice.type.isSmsToken()) {
                if (isApproved) {
                    dataMap.action = MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_MOBILE_APP_TOKEN_APPROVED
                } else {
                    dataMap.action = MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_MOBILE_APP_TOKEN_REJECTED
                }
            } else {
                if (isApproved) {
                    dataMap.action = MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUEST_MOBILE_APP_TOKEN_TO_MOBILE_APP_TOKEN_APPROVED
                } else {
                    dataMap.action = MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUEST_MOBILE_APP_TOKEN_TO_MOBILE_APP_TOKEN_REJECTED
                }
            }

            notifyUsers(authorizationDeviceUpdateRequest.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyPixExternalClaimRequested(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_REQUESTED
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixExternalClaimCancelledByClaimer(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_EXTERNAL_CLAIM_CANCELLED_BY_CLAIMER
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixClaimRequestApproved(String claimedPixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_REQUEST_APPROVED
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [claimedPixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixClaimRequestRefused(String claimedPixKey, Customer customer, String description) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_REQUEST_REFUSED
            dataMap.description = description
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [claimedPixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixClaimCancelledByOwner(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_CANCELLED_BY_OWNER
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixClaimCancelled(String claimedPixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_CANCELLED
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [claimedPixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixClaimDone(String claimedPixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_DONE
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [claimedPixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixAddressKeyActivated(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_ADDRESS_KEY_ACTIVATED
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixAddressKeyActivateRefused(String pixKey, Customer customer, String description) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_ADDRESS_KEY_ACTIVATE_REFUSED
            dataMap.description = description
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixAddressKeyDeleted(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_ADDRESS_KEY_DELETED
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixExternalClaimApprovalSent(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_APPROVAL_SENT

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixExternalClaimApprovalRefused(String pixKey, Customer customer, String description) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_APPROVAL_REFUSED
            dataMap.description = description
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixExternalClaimCancellationSent(String pixKey, Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_CANCELLATION_SENT
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixExternalClaimCancellationRefused(String pixKey, Customer customer, String description) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CLAIM_CANCELLATION_REFUSED
            dataMap.description = description
            dataMap.deeplink = MobileAppDeeplink.PIX_ADDRESS_KEY_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [pixKey], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixDebitDone(PixTransaction pixTransaction, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_DEBIT_DONE
            dataMap.objectId = pixTransaction.id
            dataMap.transferPublicId = transferPublicId
            dataMap.deeplink = MobileAppDeeplink.TRANSFER_DETAIL_PIX.buildPath([objectId: dataMap.objectId, transferPublicId: dataMap.transferPublicId])

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], buildPixTransactionParameters(pixTransaction), MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyPixDebitRefused(PixTransaction pixTransaction, String reason, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_DEBIT_REFUSED
            dataMap.objectId = pixTransaction.id
            dataMap.transferPublicId = transferPublicId
            dataMap.description = "Não foi possível concluir sua transação Pix: ${reason}"
            dataMap.deeplink = MobileAppDeeplink.TRANSFER_DETAIL_PIX.buildPath([objectId: dataMap.objectId, transferPublicId: dataMap.transferPublicId])

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], buildPixTransactionParameters(pixTransaction), MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyPixCreditRefundDone(PixTransaction pixTransaction, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CREDIT_REFUND_DONE
            dataMap.objectId = pixTransaction.id
            dataMap.transferPublicId = transferPublicId
            dataMap.deeplink = MobileAppDeeplink.TRANSFER_DETAIL_PIX.buildPath([objectId: dataMap.objectId, transferPublicId: dataMap.transferPublicId])

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], buildPixTransactionParameters(pixTransaction), MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixCreditRefundRefused(PixTransaction pixTransaction, String reason, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CREDIT_REFUND_REFUSED
            dataMap.objectId = pixTransaction.id
            dataMap.transferPublicId = transferPublicId
            dataMap.description = "Não foi possível concluir o seu estorno da transação Pix: ${reason}"
            dataMap.deeplink = MobileAppDeeplink.TRANSFER_DETAIL_PIX.buildPath([objectId: dataMap.objectId, transferPublicId: dataMap.transferPublicId])

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], buildPixTransactionParameters(pixTransaction), MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixCreditReceived(PixTransaction pixTransaction) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CREDIT_RECEIVED
            dataMap.objectId = pixTransaction.id
            dataMap.paymentId = pixTransaction.payment.publicId
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_PIX_DETAIL.buildPath([paymentId: dataMap.paymentId])

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], buildPixTransactionParameters(pixTransaction), MobilePushNotificationPriority.MEDIUM)
        })
    }

    public void notifyPixDebitRefundReceived(PixTransaction pixTransaction, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_DEBIT_REFUND_RECEIVED
            dataMap.objectId = pixTransaction.id
            dataMap.transferPublicId = transferPublicId
            dataMap.deeplink = MobileAppDeeplink.TRANSFER_DETAIL_PIX.buildPath([objectId: dataMap.objectId, transferPublicId: dataMap.transferPublicId])

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], buildPixTransactionParameters(pixTransaction), MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyPixAwaitingCashInRiskAnalysis(PixTransaction pixTransaction) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_AWAITING_CASH_IN_RISK_ANALYSIS
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_PIX_DETAIL.buildPath([paymentId: dataMap.paymentId])

            String valueWithMonetarySymbol = FormUtils.formatCurrencyWithMonetarySymbol(pixTransaction.value.abs())
            List arguments = [valueWithMonetarySymbol, CashInRiskAnalysisRequest.PIX_ANALYSIS_LIMIT_TIME_IN_HOURS]

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyPixCashInRiskAnalysisRefunded(PixTransaction pixTransaction) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_CASH_IN_RISK_ANALYSIS_REFUNDED
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_PIX_DETAIL.buildPath([paymentId: dataMap.paymentId])

            String valueWithMonetarySymbol = FormUtils.formatCurrencyWithMonetarySymbol(pixTransaction.value.abs())

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], [valueWithMonetarySymbol], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyScheduledPixDebitProcessingRefused(PixTransaction pixTransaction, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.SCHEDULED_PIX_DEBIT_PROCESSING_REFUSED
            dataMap.objectId = pixTransaction.id
            dataMap.transferPublicId = transferPublicId

            notifyUsers(pixTransaction.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyScheduledCreditTransferRequestProcessingRefused(CreditTransferRequest creditTransferRequest, String transferPublicId) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.SCHEDULED_CREDIT_TRANSFER_REQUEST_PROCESSING_REFUSED
            dataMap.objectId = creditTransferRequest.id
            dataMap.transferPublicId = transferPublicId

            notifyUsers(creditTransferRequest.provider, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyCustomerAboutUserImpersonationRequest(Customer customer, Long id, String accountManagerName) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.USER_IMPERSONATION_REQUEST
            dataMap.objectId = id
            dataMap.deeplink = MobileAppDeeplink.USER_IMPERSONATION_REQUEST.buildPath([objectId: dataMap.objectId])

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [accountManagerName], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_TRANSACTION_CHECKOUT_LIMIT_VALUES_CHANGE_REQUEST_APPROVED
            dataMap.objectId = pixTransactionCheckoutLimitChangeRequest.id
            dataMap.deeplink = MobileAppDeeplink.PIX_INDEX.buildPath()

            String scope = Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.scope)
            String period = Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.period).toLowerCase()
            String limitType = Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.limitType).toLowerCase()

            notifyUsers(pixTransactionCheckoutLimitChangeRequest.customer, dataMap, [MobileApplicationType.ASAAS], [scope, period, limitType], MobilePushNotificationPriority.MEDIUM)
        })
    }

    public void notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestDenied(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_TRANSACTION_CHECKOUT_LIMIT_VALUES_CHANGE_REQUEST_DENIED
            dataMap.objectId = pixTransactionCheckoutLimitChangeRequest.id
            dataMap.deeplink = MobileAppDeeplink.PIX_INDEX.buildPath()

            String scope = Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.scope)

            notifyUsers(pixTransactionCheckoutLimitChangeRequest.customer, dataMap, [MobileApplicationType.ASAAS], [scope], MobilePushNotificationPriority.MEDIUM)
        })
    }

    public void notifyCustomerPixTransactionCheckoutLimitInitialNightlyHourChangeRequestApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PIX_TRANSACTION_CHECKOUT_LIMIT_INITIAL_NIGHTLY_HOUR_CHANGE_REQUEST_APPROVED
            dataMap.objectId = pixTransactionCheckoutLimitChangeRequest.id
            dataMap.deeplink = MobileAppDeeplink.PIX_INDEX.buildPath()

            notifyUsers(pixTransactionCheckoutLimitChangeRequest.customer, dataMap, [MobileApplicationType.ASAAS], [pixTransactionCheckoutLimitChangeRequest.requestedLimit.toInteger()], MobilePushNotificationPriority.MEDIUM)
        })
    }

    public void notifyAsaasCardDelivered(AsaasCard asaasCard) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_DELIVERED
            dataMap.asaasCardId = asaasCard.id
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardActivated(AsaasCard asaasCard) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_ACTIVATED
            dataMap.asaasCardId = asaasCard.id
            dataMap.deeplink = MobileAppDeeplink.WEB_VIEW_ASAAS_CARD.buildPath()

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardDeliveredNotActivated(AsaasCard asaasCard) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_DELIVERED_NOT_ACTIVATED
            dataMap.asaasCardId = asaasCard.id
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardAutomaticDebitActivated(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_AUTOMATIC_DEBIT_ACTIVATED
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardUnpaidBillBlock(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_UNPAID_BILL_BLOCK
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardBillClosed(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_BILL_CLOSED
            dataMap.asaasCardId = notifyCardBillAdapter.asaasCardId
            dataMap.billId = notifyCardBillAdapter.billId
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            BigDecimal billValue = notifyCardBillAdapter.value
            String dueDate = CustomDateUtils.fromDate(notifyCardBillAdapter.dueDate, "dd/MM")

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [dueDate, FormUtils.formatCurrencyWithMonetarySymbol(billValue)], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardBillPaidByAutomaticDebit(AsaasCardBillPayment asaasCardBillPayment, Long billId, Date dueDate) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_BILL_PAID_BY_AUTOMATIC_DEBIT
            dataMap.asaasCardId = asaasCardBillPayment.asaasCard.id
            dataMap.billId = billId
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(asaasCardBillPayment.asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], [CustomDateUtils.fromDate(dueDate, "dd/MM")], MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyAsaasCardBillPaymentReceived(AsaasCard asaasCard) {
        Utils.withSafeException({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_BILL_PAYMENT_RECEIVED
            dataMap.asaasCardId = asaasCard.id
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyUpcomingAsaasCardBillDueDate(Customer customer) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_BILL_UPCOMING_DUE_DATE

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyOverdueAsaasCardBill(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        withLogOnError({
            MobilePushNotificationAction type = notifyCardBillAdapter.automaticPaymentValue ? MobilePushNotificationAction.ASAAS_CARD_BILL_OVERDUE_PARTIAL_AUTOMATIC_PAYMENT : MobilePushNotificationAction.ASAAS_CARD_BILL_OVERDUE_NO_AUTOMATIC_PAYMENT

            Map dataMap = [:]
            dataMap.action = type
            dataMap.asaasCardId = notifyCardBillAdapter.asaasCardId
            dataMap.billId = notifyCardBillAdapter.billId
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_BILL_DETAIL.buildPath([asaasCardId: dataMap.asaasCardId, billId: dataMap.billId])

            BigDecimal billValue = notifyCardBillAdapter.value

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [FormUtils.formatCurrencyWithMonetarySymbol(billValue)], MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardUnblockedAfterBalanceAcquittance(AsaasCard asaasCard) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_UNBLOCKED_AFTER_BALANCE_ACQUITTANCE
            dataMap.asaasCardId = asaasCard.id
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_INDEX.buildPath()

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], null, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardTransactionAuthorized(AsaasCard asaasCard, NotifyTransactionAdapter transactionAdapter) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.asaasCardId = asaasCard.publicId
            dataMap.externalId = transactionAdapter.transactionExternalId
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_TRANSACTION_AUTHORIZED
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_TRANSACTION_DETAIL.buildPath([externalId: dataMap.externalId, asaasCardId: dataMap.asaasCardId])

            List arguments = [FormUtils.formatCurrencyWithMonetarySymbol(transactionAdapter.value), transactionAdapter.establishmentName]

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyAsaasCardTransactionRefused(AsaasCard asaasCard, NotifyTransactionAdapter transactionAdapter) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_TRANSACTION_REFUSED

            String messageCode = "AsaasCardTransactionRefusalReasonNotification.${transactionAdapter.refusalReason}"
            if (transactionAdapter.refusalReason.isInsufficientBalance()) messageCode += ".${asaasCard.type}"

            List arguments = [FormUtils.formatCurrencyWithMonetarySymbol(transactionAdapter.value), transactionAdapter.establishmentName, Utils.getMessageProperty(messageCode)]

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyAsaasCardCreditTransactionRefund(AsaasCard asaasCard, NotifyTransactionAdapter transactionAdapter) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.asaasCardId = asaasCard.publicId
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_TRANSACTION_REFUND
            dataMap.externalId = transactionAdapter.transactionExternalId.toString()
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_TRANSACTION_DETAIL.buildPath([externalId: dataMap.externalId, asaasCardId: dataMap.asaasCardId])

            List arguments = [FormUtils.formatCurrencyWithMonetarySymbol(transactionAdapter.value), transactionAdapter.establishmentName]

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyAsaasCardCreditTransactionRefundCancelled(AsaasCard asaasCard, NotifyTransactionAdapter transactionAdapter) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.asaasCardId = asaasCard.publicId
            dataMap.action = MobilePushNotificationAction.ASAAS_CARD_TRANSACTION_REFUND_CANCELLED
            dataMap.externalId = transactionAdapter.transactionExternalId.toString()
            dataMap.deeplink = MobileAppDeeplink.ASAAS_CARD_TRANSACTION_DETAIL.buildPath([externalId: dataMap.externalId, asaasCardId: dataMap.asaasCardId])

            List arguments = [FormUtils.formatCurrencyWithMonetarySymbol(transactionAdapter.value), CustomDateUtils.fromDate(transactionAdapter.refundDate)]

            notifyUsers(asaasCard.customer, dataMap, [MobileApplicationType.ASAAS], arguments, MobilePushNotificationPriority.LOW)
        })
    }

    public void notifyUserPasswordExpiration(User user, Integer daysToExpire) {
        withLogOnError({
            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.USER_PASSWORD_EXPIRATION

            Map notificationProperties = buildNotificationProperties(dataMap.action, [daysToExpire])
            notifyUser(user, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], notificationProperties, MobilePushNotificationPriority.HIGH)
        })
    }

    public void notifyUserSecurityEvent(User user, MobilePushNotificationAction action) {
        withLogOnError({
            if (!action.isUserSecurityAction() && !user.customer.cpfCnpj) {
                AsaasLogger.warn("MobilePushNotificationService.notifyUserSecurityEvent >> Tentativa de envio da action [${action}] com CPF/CNPJ nulo do customer [${user.customer.id}].")
                return
            }

            Map dataMap = [:]
            dataMap.action = action

            MobilePushNotificationPriority priority = action == MobilePushNotificationAction.FACEMATCH_CRITICAL_ACTION_AUTHORIZED ? MobilePushNotificationPriority.MEDIUM : MobilePushNotificationPriority.HIGH
            List<String> notificationParameters = buildUserSecurityEventParameters(user, action)

            notifyUsers(user.customer, dataMap, [MobileApplicationType.ASAAS, MobileApplicationType.MONEY], notificationParameters, priority)
        })
    }

    private void notifyPaymentReceived(Long customerId) {
        withLogOnError({
            Customer customer = Customer.get(customerId)

            Map dataMap = [:]
            dataMap.action = MobilePushNotificationAction.PAYMENT_RECEIVED
            dataMap.paymentDate = ApiBaseParser.formatDate(new Date().clearTime())
            dataMap.deeplink = MobileAppDeeplink.PAYMENT_INDEX.buildPath([status: PaymentStatus.RECEIVED.toString(), filterBy: "paymentDate", dateStart: dataMap.paymentDate])

            int paymentReceivedCount = Payment.received([column: "id", customer: customer, paymentDate: dataMap.paymentDate]).count()

            notifyUsers(customer, dataMap, [MobileApplicationType.ASAAS], [paymentReceivedCount], MobilePushNotificationPriority.LOW)
        })
    }

    private List buildPixTransactionParameters(PixTransaction pixTransaction) {
        PixTransactionExternalAccount externalAccount = pixTransaction.externalAccount
        if (pixTransaction.type.isRefund()) externalAccount = pixTransaction.getRefundedTransaction().externalAccount

        String ispb = PixUtils.formatIspbNameForNotifications(externalAccount.getPaymentServiceProviderCorporateName())
        String valueFormattedWithMonetarySymbol = FormUtils.formatCurrencyWithMonetarySymbol(pixTransaction.value.abs())

        return [valueFormattedWithMonetarySymbol, StringUtils.getFirstWord(externalAccount.name), ispb]
    }

    private List<String> buildUserSecurityEventParameters(User user, MobilePushNotificationAction action) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        if (action.isUserSecurityAction()) return [maskedUsername]

        Boolean isCpf = CpfCnpjUtils.isCpf(user.customer.cpfCnpj)
        String maskedCpfCnpj = CpfCnpjUtils.maskCpfCnpjForPublicVisualization(user.customer.cpfCnpj)

        if (action == MobilePushNotificationAction.USER_CREATION || action == MobilePushNotificationAction.API_KEY_CREATED) return [maskedUsername, isCpf ? "CPF ${maskedCpfCnpj}" : "CNPJ ${maskedCpfCnpj}"]
        if (action == MobilePushNotificationAction.CRITICAL_ACTION_CONFIG_DISABLED || action == MobilePushNotificationAction.CUSTOMER_UPDATE_REQUESTED) return [isCpf ? "CPF ${maskedCpfCnpj}" : "CNPJ ${maskedCpfCnpj}", maskedUsername]

        throw new RuntimeException("A ação ${action} não faz parte dos eventos de segurança.")
    }

    private void notifyUsers(Customer customer, Map dataMap, List<MobileApplicationType> applicationList, List notificationPropertiesArguments, MobilePushNotificationPriority priority) {
        List<User> userList = User.adminOrFinancial(customer, [withUserMobileDevices: true]).list()
        Map notificationProperties = buildNotificationProperties(dataMap.action, notificationPropertiesArguments)

        for (User user : userList) {
            notifyUser(user, dataMap, applicationList, notificationProperties, priority)
        }
    }

    private void notifyUser(User user, Map dataMap, List<MobileApplicationType> applicationList, Map notificationProperties, MobilePushNotificationPriority priority) {
        List<UserMobileDevice> userMobileDeviceList = findSuitableUserMobileDevices(user, applicationList)

        for (UserMobileDevice userMobileDevice : userMobileDeviceList) {
            save(userMobileDevice, notificationProperties.title, notificationProperties.body, dataMap, priority)
        }
    }

    private void save(UserMobileDevice userMobileDevice, String title, String body, Map dataMap, MobilePushNotificationPriority priority) {
        MobilePushNotification mobilePushNotification = new MobilePushNotification()

        mobilePushNotification.userMobileDevice = userMobileDevice
        mobilePushNotification.title = title
        mobilePushNotification.body = body
        mobilePushNotification.dataJson = buildDataJson(dataMap)
        mobilePushNotification.priority = priority
        mobilePushNotification.save(failOnError: false)

        if (mobilePushNotification.hasErrors()) {
            AsaasLogger.error("MobilePushNotificationService.save >> Falha ao inserir MobilePushNotification.\n\n" + mobilePushNotification.errors)
        }
    }

    private String buildDataJson(Map dataMap) {
        if (dataMap.action) {
            dataMap.action = dataMap.action.toString()
        }

        return (dataMap as JSON).toString()
    }

    private Map buildNotificationProperties(MobilePushNotificationAction action, List arguments) {
        Map headerMap = [:]
        headerMap.title = Utils.getMessageProperty("userMobilePushNotification." + action + ".title")
        headerMap.body = Utils.getMessageProperty("userMobilePushNotification." + action + ".body", arguments)

        return headerMap
    }

    private List<UserMobileDevice> findSuitableUserMobileDevices(User user, List<MobileApplicationType> applicationList) {
        if (applicationList.size() == 1) {
            return UserMobileDevice.query([user: user, applicationType: applicationList.first(), order: "asc"]).list()
        }

        List<UserMobileDevice> userMobileDeviceList = UserMobileDevice.query([user: user, order: "asc"]).list()

        Boolean wasAccountCreatedThroughAsaas = user.customer.signedUpThrough == null || user.customer.signedUpThrough.isAsaasApp()
        if (wasAccountCreatedThroughAsaas) {
            List<UserMobileDevice> asaasDeviceList = userMobileDeviceList.findAll { userMobileDevice -> userMobileDevice.applicationType.isAsaas() }
            if (asaasDeviceList) return asaasDeviceList
        }

        List<UserMobileDevice> moneyDeviceList = userMobileDeviceList.findAll { userMobileDevice -> userMobileDevice.applicationType.isMoney() }
        if (moneyDeviceList) return moneyDeviceList

        return []
    }

    private void withLogOnError(Closure closure) {
        try {
            closure()
        } catch (Exception exception) {
            AsaasLogger.error("MobilePushNotificationService.withLogOnError >> Falha ao salvar push notification", exception)
        }
    }
}
