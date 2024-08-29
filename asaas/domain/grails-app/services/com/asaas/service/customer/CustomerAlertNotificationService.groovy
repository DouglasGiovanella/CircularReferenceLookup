package com.asaas.service.customer

import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.customer.CustomerAlertNotificationType
import com.asaas.customer.CustomerParameterName
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.bill.Bill
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAlertNotification
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.installment.Installment
import com.asaas.domain.loginunlockrequest.LoginUnlockRequest
import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequest
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationsimulation.ReceivableAnticipationSimulationBatch
import com.asaas.domain.smstokenphonechangerequest.SmsTokenPhoneChangeRequest
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.user.User
import com.asaas.integration.bifrost.adapter.notification.NotifyCardBillAdapter
import com.asaas.integration.bifrost.adapter.notification.NotifyTransactionAdapter
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.converters.JSON
import grails.transaction.Transactional

import static com.asaas.utils.CustomDateUtils.DATABASE_DATETIME_FORMAT

@Transactional
class CustomerAlertNotificationService {

    public void notifyChargebackAccepted(Chargeback chargeback) {
        Utils.withSafeException({
            save(chargeback.customer, CustomerAlertNotificationType.CHARGEBACK_ACCEPTED, [objectId: chargeback.id])
        })
    }

    public void notifyChargebackConfirmed(Chargeback chargeback) {
        Utils.withSafeException({
            save(chargeback.customer, CustomerAlertNotificationType.CHARGEBACK_CONFIRMED, [objectId:chargeback.id])
        })
    }

    public void notifyChargebackContested(Chargeback chargeback) {
        Utils.withSafeException({
            save(chargeback.customer, CustomerAlertNotificationType.CHARGEBACK_CONTESTED, [objectId: chargeback.id])
        })
    }

    public void notifyChargebackRejected(Chargeback chargeback) {
        Utils.withSafeException({
            save(chargeback.customer, CustomerAlertNotificationType.CHARGEBACK_REJECTED, [objectId: chargeback.id])
        })
    }

    public void notifyChargebackRequested(Chargeback chargeback) {
        Utils.withSafeException({
            Map alertInfo = [:]

            alertInfo.params = [value: chargeback.value]
            alertInfo.objectId = chargeback.id

            save(chargeback.customer, CustomerAlertNotificationType.CHARGEBACK_REQUESTED, alertInfo)
        })
    }

    public CustomerAlertNotification update(CustomerAlertNotification customerAlertNotification, Map params) {

        if (params.containsKey("displayed")) {
            customerAlertNotification.displayed = params.displayed
        }

        if (params.containsKey("alertDate")) {
            customerAlertNotification.alertDate = params.alertDate
        }

        if (params.containsKey("alertInfo")) {
            customerAlertNotification.alertInfo = params.alertInfo
        }

        if (params.containsKey("visualized")) {
            customerAlertNotification.visualized = params.visualized
        }

        customerAlertNotification.save(failOnError: true)

        return customerAlertNotification
    }

    public CustomerAlertNotification update(Customer customer, Long customerAlertNotificationId, Map params) {
        CustomerAlertNotification customerAlertNotification = CustomerAlertNotification.find(customerAlertNotificationId, customer)
        return update(customerAlertNotification, params)
    }

    public void notifyBillPaymentFailed(Bill bill) {
        Utils.withSafeException({ createBillPaymentFailedAlert(bill) })
	}

    public void notifyBillFailureForInsufficientBalance(List<Bill> billList, Customer customer) {
        Utils.withSafeException({ createBillFailureForInsufficientBalance(billList, customer) })
    }

    public void notifyBillPaid(Bill bill) {
        Utils.withSafeException({ createBillPaidAlert(bill) })
    }

    public void notifyBillRefunded(Bill bill) {
        Utils.withSafeException({ createBillRefundedAlert(bill) })
    }

    public void notifyBillCancelled(Bill bill) {
        Utils.withSafeException({ createBillCancelledAlert(bill) })
    }

    public void notifySubscriptionEnding(Subscription subscription) {
        Utils.withSafeException({ createSubscriptionEndingAlert(subscription) })
    }

    public void notifyBillScheduledDate(Customer customer, Date scheduleDate, Integer billCount) {
        Utils.withSafeException({ createBillScheduledDate(customer, scheduleDate, billCount) })
    }

    public void notifyTransferFailed(CreditTransferRequest creditTransferRequest) {
        Utils.withSafeException({ createTransferFailedAlert(creditTransferRequest) })
    }

    public void notifyDocumentAnalysisResult(DocumentAnalysis documentAnalysis) {
        Utils.withSafeException({ createDocumentAnalysisAlert(documentAnalysis) })
    }

    public void notifyCompulsoryCustomerRegisterUpdate(Customer customer) {
        Utils.withSafeException({ createCompulsoryCustomerRegisterUpdateAlert(customer) })
    }

    public void notifyCommercialInfoAnalysisResult(CustomerUpdateRequest customerUpdateRequest) {
        Utils.withSafeException({ createCommercialInfoAnalysisAlert(customerUpdateRequest) })
    }

    public void notifyBankAccountAnalysisResult(BaseBankAccount bankAccountInfo) {
        Utils.withSafeException({ createBankAccountAnalysisAlert(bankAccountInfo) })
    }

    public void createBankAccountAnalysisAlert(BaseBankAccount bankAccountInfo) {
        if (bankAccountInfo.status == Status.APPROVED) {
            save(bankAccountInfo.customer, CustomerAlertNotificationType.BANK_ACCOUNT_INFO_APPROVED)
        } else {
            save(bankAccountInfo.customer, CustomerAlertNotificationType.BANK_ACCOUNT_INFO_REJECTED)
        }
    }

    public void notifyGeneralApprovalApproved(Customer customer) {
        Utils.withSafeException({ save(customer, CustomerAlertNotificationType.GENERAL_APPROVAL_APPROVED) })
	}

	public void notifyInvoiceConfigRejected(Customer customer) {
        Utils.withSafeException({ save(customer, CustomerAlertNotificationType.INVOICE_CONFIG_REJECTED) })
	}

    public void notifyAnticipationRequestResult(ReceivableAnticipation receivableanticipation) {
        Utils.withSafeException({ createAntecipationRequestAlert(receivableanticipation) })
	}

    public void notifyDunningPaid(Customer customer, Long id) {
        Utils.withSafeException({saveDunningPaid(customer, id)})
	}

    public void notifySmsTokenPhoneChangeRequestResult(SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest) {
        Utils.withSafeException({
            createSmsTokenPhoneChangeRequestAlert(smsTokenPhoneChangeRequest)
        })
	}

    public void processCustomerAlertsOnDisplay(Long customerId) {
        if (!customerId) return

        CustomerAlertNotification.executeUpdate("""
            UPDATE CustomerAlertNotification
            SET displayed = true,
                visualized = (CASE WHEN alertType IN (:alertTypeList) THEN true ELSE visualized END),
                lastUpdated = :lastUpdated,
                version = version + 1
            WHERE customer.id = :customerId AND displayed = false
        """, [
            alertTypeList: CustomerAlertNotificationType.setAsVisualizedOnDisplay(),
            customerId: customerId,
            lastUpdated: new Date()
        ])
    }

    public CustomerAlertNotification visualize(Customer customer, Long id) {
        CustomerAlertNotification customerAlertNotification = CustomerAlertNotification.find(id, customer)
        return update(customerAlertNotification, [visualized: true])
    }

    public void notifyAsaasCardActivated(AsaasCard asaasCard) {
        Utils.withSafeException({
            save(asaasCard.customer, CustomerAlertNotificationType.ASAAS_CARD_ACTIVATED, [:])
        })
    }

    public void notifyAsaasCardDelivered(AsaasCard asaasCard) {
        Utils.withSafeException({
            save(asaasCard.customer, CustomerAlertNotificationType.ASAAS_CARD_DELIVERED, [:])
        })
    }

    public void notifyAsaasCardDeliveredNotActivated(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_DELIVERED_NOT_ACTIVATED, [:])
        })
    }

    public void notifyAsaasCardBillClosed(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        Map params = [
            billValue: notifyCardBillAdapter.value,
            dueDate: CustomDateUtils.fromDate(notifyCardBillAdapter.dueDate, "dd/MM"),
            asaasCardId: notifyCardBillAdapter.asaasCardId,
            billId: notifyCardBillAdapter.billId
        ]
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_BILL_CLOSED, [params: params])
        })
    }

    public void notifyUpcomingAsaasCardBillDueDate(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        Map params = [
            asaasCardId: notifyCardBillAdapter.asaasCardId,
            billId: notifyCardBillAdapter.billId
        ]

        Map titleParams = [dueDate: CustomDateUtils.fromDate(notifyCardBillAdapter.dueDate, "dd/MM/yyyy")]

        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_BILL_UPCOMING_DUE_DATE, [params: params, titleParams: titleParams])
        })
    }

    public void notifyOverdueAsaasCardBill(Customer customer, NotifyCardBillAdapter notifyCardBillAdapter) {
        CustomerAlertNotificationType type = notifyCardBillAdapter.automaticPaymentValue ? CustomerAlertNotificationType.ASAAS_CARD_BILL_OVERDUE_PARTIAL_AUTOMATIC_PAYMENT : CustomerAlertNotificationType.ASAAS_CARD_BILL_OVERDUE_NO_AUTOMATIC_PAYMENT
        Map params = [
            asaasCardId: notifyCardBillAdapter.asaasCardId,
            billId: notifyCardBillAdapter.billId,
            valueAvailableForPayment: notifyCardBillAdapter.valueAvailableForPayment
        ]

        Utils.withSafeException({
            save(customer, type, [params: params])
        })
    }

    public void notifyAsaasCardBillPaidByAutomaticDebit(AsaasCardBillPayment asaasCardBillPayment, Long billId, Date dueDate) {
        Map params = [
            billValue: asaasCardBillPayment.value,
            dueDate: CustomDateUtils.fromDate(dueDate, "dd/MM"),
            billId: billId,
            asaasCardId: asaasCardBillPayment.asaasCard.id
        ]

        Utils.withSafeException({
            save(asaasCardBillPayment.asaasCard.customer, CustomerAlertNotificationType.ASAAS_CARD_BILL_PAID_BY_AUTOMATIC_DEBIT, [params: params])
        })
    }

    public void notifyAsaasCardBillPaymentReceived(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_BILL_PAYMENT_RECEIVED, [:])
        })
    }

    public void notifyAsaasCardUnblockedAfterBalanceAcquittance(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_UNBLOCKED_AFTER_BALANCE_ACQUITTANCE, [:])
        })
    }

    public void notifyAsaasCardAutomaticDebitActivated(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_AUTOMATIC_DEBIT_ACTIVATED, [:])
        })
    }

    public void notifyDunningDenied(PaymentDunning paymentDunning) {
        Utils.withSafeException({
            Map alertInfo = [:]
            alertInfo.objectId = paymentDunning.id

            save(paymentDunning.customer, CustomerAlertNotificationType.DUNNING_DENIED, alertInfo)
        })
    }

    public void notifyDunningCanceledByInsufficientBalance(PaymentDunning paymentDunning) {
        Utils.withSafeException({
            Map alertInfo = [:]
            alertInfo.objectId = paymentDunning.id

            save(paymentDunning.customer, CustomerAlertNotificationType.DUNNING_CANCELED_BY_INSUFFICIENT_BALANCE, alertInfo)
        })
    }

    public void notifyAuthorizationDeviceUpdateRequestSmsTokenResult(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        if (authorizationDeviceUpdateRequest.status.isApproved()) {
            save(authorizationDeviceUpdateRequest.customer, CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_SMS_TOKEN_APPROVED, [:])
        } else {
            save(authorizationDeviceUpdateRequest.customer, CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_SMS_TOKEN_REJECTED, [:])
        }
    }

    public void notifyAuthorizationDeviceUpdateRequestMobileAppTokenResult(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, AuthorizationDevice oldDevice) {
        Boolean isApproved = authorizationDeviceUpdateRequest.status.isApproved()
        CustomerAlertNotificationType alertType

        if (oldDevice.type.isSmsToken()) {
            if (isApproved) {
                alertType = CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_MOBILE_APP_TOKEN_APPROVED
            } else {
                alertType = CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUEST_SMS_TOKEN_TO_MOBILE_APP_TOKEN_REJECTED
            }
        } else {
            if (isApproved) {
                alertType = CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUEST_MOBILE_APP_TOKEN_TO_MOBILE_APP_TOKEN_APPROVED
            } else {
                alertType = CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUEST_MOBILE_APP_TOKEN_TO_MOBILE_APP_TOKEN_REJECTED
            }
        }

        save(authorizationDeviceUpdateRequest.customer, alertType, [:])
    }

    public void notifyPixExternalClaimRequested(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_REQUESTED, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixClaimCancelledByOwner(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_CANCELLED_BY_OWNER, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixExternalClaimCancelledByClaimer(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_EXTERNAL_CLAIM_CANCELLED_BY_CLAIMER, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixClaimRequestApproved(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_REQUEST_APPROVED, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixClaimRequestRefused(String pixKey, Customer customer, String description) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_REQUEST_REFUSED, [params: [pixKey: pixKey, description: description]])
        })
    }

    public void notifyPixClaimCancelled(String pixKey, Customer customer, String description) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_CANCELLED, [params: [pixKey: pixKey, description: description]])
        })
    }

    public void notifyPixClaimDone(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_DONE, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixAddressKeyActivated(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_ADDRESS_KEY_ACTIVATED, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixAddressKeyActivateRefused(String pixKey, Customer customer, String description) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_ADDRESS_KEY_ACTIVATE_REFUSED, [params: [pixKey: pixKey, description: description]])
        })
    }

    public void notifyPixAddressKeyDeleted(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_ADDRESS_KEY_DELETED, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixExternalClaimApprovalSent(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_APPROVAL_SENT, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixExternalClaimApprovalRefused(String pixKey, Customer customer, String description) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_APPROVAL_REFUSED, [params: [pixKey: pixKey, description: description]])
        })
    }

    public void notifyPixExternalClaimCancellationSent(String pixKey, Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_CANCELLATION_SENT, [params: [pixKey: pixKey]])
        })
    }

    public void notifyPixExternalClaimCancellationRefused(String pixKey, Customer customer, String description) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.PIX_CLAIM_CANCELLATION_REFUSED, [params: [pixKey: pixKey, description: description]])
        })
    }

    public void notifyPixDebitDone(PixTransaction pixTransaction, String transferPublicId) {
        Utils.withSafeException({ save(pixTransaction.customer, CustomerAlertNotificationType.PIX_DEBIT_DONE, [objectId: pixTransaction.id, params: [transferPublicId: transferPublicId]]) })
    }

    public void notifyPixDebitRefused(PixTransaction pixTransaction, String reason, String transferPublicId) {
        Utils.withSafeException({
            String description = "Não foi possível concluir sua transação Pix: ${reason}"
            save(pixTransaction.customer, CustomerAlertNotificationType.PIX_DEBIT_REFUSED, [objectId: pixTransaction.id, params: [description: description, transferPublicId: transferPublicId]])
        })
    }

    public void notifyPixCreditRefundDone(PixTransaction pixTransaction, String transferPublicId) {
        Utils.withSafeException({ save(pixTransaction.customer, CustomerAlertNotificationType.PIX_CREDIT_REFUND_DONE, [objectId: pixTransaction.id, params: [transferPublicId: transferPublicId]]) })
    }

    public void notifyPixCreditRefundRefused(PixTransaction pixTransaction, String reason, String transferPublicId) {
        Utils.withSafeException({
            String description = "Não foi possível concluir o seu estorno da transação Pix: ${reason}"
            save(pixTransaction.customer, CustomerAlertNotificationType.PIX_CREDIT_REFUND_REFUSED, [objectId: pixTransaction.id, params: [description: description, transferPublicId: transferPublicId]])
        })
    }

    public void notifyPixCreditReceived(PixTransaction pixTransaction) {
        Utils.withSafeException({
            Map alertInfo = [objectId: pixTransaction.id]
            if (pixTransaction.payment) alertInfo.params = [paymentId: pixTransaction.paymentId]

            save(pixTransaction.customer, CustomerAlertNotificationType.PIX_CREDIT_RECEIVED, alertInfo)
        })
    }

    public void notifyPixAwaitingCashInRiskAnalysis(PixTransaction pixTransaction) {
        Utils.withSafeException({
            Map alertInfo = [objectId: pixTransaction.id, params: [paymentId: pixTransaction.paymentId]]
            save(pixTransaction.customer, CustomerAlertNotificationType.PIX_AWAITING_CASH_IN_RISK_ANALYSIS, alertInfo)
        })
    }

    public void notifyPixAwaitingInstantPaymentAccountBalance(PixTransaction pixTransaction, Long transferId) {
        Utils.withSafeException({
            Map alertInfo = [objectId: pixTransaction.id, params: [transferId: transferId]]
            save(pixTransaction.customer, CustomerAlertNotificationType.PIX_AWAITING_INSTANT_PAYMENT_ACCOUNT_BALANCE, alertInfo)
        })
    }

    public void notifyPixCashInRiskAnalysisRefunded(PixTransaction pixTransaction) {
        Utils.withSafeException({
            Map alertInfo = [objectId: pixTransaction.id, params: [paymentId: pixTransaction.paymentId]]
            save(pixTransaction.customer, CustomerAlertNotificationType.PIX_CASH_IN_RISK_ANALYSIS_REFUNDED, alertInfo)
        })
    }

    public void notifyPixDebitRefundReceived(PixTransaction pixTransaction, String transferPublicId) {
        Utils.withSafeException({ save(pixTransaction.customer, CustomerAlertNotificationType.PIX_DEBIT_REFUND_RECEIVED, [objectId: pixTransaction.id, params: [transferPublicId: transferPublicId]]) })
    }

    public void notifyScheduledPixDebitProcessingRefused(PixTransaction pixTransaction, String transferPublicId) {
        Utils.withSafeException({ save(pixTransaction.customer, CustomerAlertNotificationType.SCHEDULED_PIX_DEBIT_PROCESSING_REFUSED, [objectId: pixTransaction.id, params: [transferPublicId: transferPublicId]]) })
    }

    public void notifyScheduledCreditTransferRequestProcessingRefused(CreditTransferRequest creditTransferRequest, String transferPublicId) {
        Utils.withSafeException({ save(creditTransferRequest.provider, CustomerAlertNotificationType.SCHEDULED_CREDIT_TRANSFER_REQUEST_PROCESSING_REFUSED, [objectId: creditTransferRequest.id, params: [transferPublicId: transferPublicId]]) })
    }

    public void notifyAnticipationSimulationBatchProcessed(ReceivableAnticipationSimulationBatch simulationBatch) {
        save(simulationBatch.customer, CustomerAlertNotificationType.ANTICIPATION_SIMULATION_BATCH_PROCESSED, [objectId: simulationBatch.id, titleParams: [simulationSize: simulationBatch.quantity]])
    }

    public void deleteCustomerEmailValidationIfExists(Customer customer) {
        CustomerAlertNotification customerAlertNotification = CustomerAlertNotification.query([customer: customer, alertType: CustomerAlertNotificationType.CUSTOMER_EMAIL_VALIDATION_REQUESTED]).get()
        if (!customerAlertNotification) return

        customerAlertNotification.deleted = true
        customerAlertNotification.save(failOnError: true)
    }

    public void notifyEmailValidationRequestedIfNotExist(Customer customer) {
        Boolean existsEmailValidationRequested = CustomerAlertNotification.query([customer: customer, alertType: CustomerAlertNotificationType.CUSTOMER_EMAIL_VALIDATION_REQUESTED, exists: true]).get().asBoolean()
        if (existsEmailValidationRequested) return

        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.CUSTOMER_EMAIL_VALIDATION_REQUESTED)
        })
    }

    public void notifyAutomaticAnticipationActivated(CustomerAutomaticReceivableAnticipationConfig config) {
        Utils.withSafeException({
            save(config.customer, CustomerAlertNotificationType.AUTOMATIC_ANTICIPATION_ACTIVATED, [params: [activateDate: formatToDatabaseDatePattern(config.lastActivationDate)]])
        })
    }

    public void notifyAutomaticAnticipationDeactivated(CustomerAutomaticReceivableAnticipationConfig config) {
        Utils.withSafeException({
            save(config.customer, CustomerAlertNotificationType.AUTOMATIC_ANTICIPATION_DEACTIVATED, [params: [deactivateDate: formatToDatabaseDatePattern(config.lastDeactivationDate)]])
        })
    }

    public void notifyCustomerAboutUserImpersonationRequest(Customer customer, Long id, String accountManagerName) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.USER_IMPERSONATION_REQUEST, [objectId: id, params: [accountManagerName: accountManagerName]])
        })
    }

    public void notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        Utils.withSafeException({
            Map alertInfo = [:]
            alertInfo.titleParams = [scope: pixTransactionCheckoutLimitChangeRequest.scope.toString()]
            alertInfo.params = [
                scope: pixTransactionCheckoutLimitChangeRequest.scope.toString(),
                period: pixTransactionCheckoutLimitChangeRequest.period.toString(),
                limitType: pixTransactionCheckoutLimitChangeRequest.limitType.toString()
            ]
            save(pixTransactionCheckoutLimitChangeRequest.customer, CustomerAlertNotificationType.PIX_TRANSACTION_CHECKOUT_LIMIT_VALUES_CHANGE_REQUEST_APPROVED, alertInfo)
        })
    }

    public void notifyCustomerPixTransactionBankAccountInfoCheckoutLimitChangeRequestApproved(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        Utils.withSafeException({
            save(pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer, CustomerAlertNotificationType.PIX_TRANSACTION_BANK_ACCOUNT_INFO_CHECKOUT_LIMIT_CHANGE_REQUEST_APPROVED, [:])
        })
    }

    public void notifyCustomerPixTransactionBankAccountInfoCheckoutLimitChangeRequestDenied(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        Utils.withSafeException({
            save(pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer, CustomerAlertNotificationType.PIX_TRANSACTION_BANK_ACCOUNT_INFO_CHECKOUT_LIMIT_CHANGE_REQUEST_DENIED, [:])
        })
    }

    public void notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestDenied(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        Utils.withSafeException({
            Map alertInfo = [:]
            alertInfo.titleParams = [scope: pixTransactionCheckoutLimitChangeRequest.scope.toString()]
            alertInfo.params = [scope: pixTransactionCheckoutLimitChangeRequest.scope.toString()]
            save(pixTransactionCheckoutLimitChangeRequest.customer, CustomerAlertNotificationType.PIX_TRANSACTION_CHECKOUT_LIMIT_VALUES_CHANGE_REQUEST_DENIED, alertInfo)
        })
    }

    public void notifyCustomerPixTransactionCheckoutLimitInitialNightlyHourChangeRequestApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        Utils.withSafeException({
            Map alertInfo = [:]
            alertInfo.params = [initialNightlyHour: pixTransactionCheckoutLimitChangeRequest.requestedLimit.toInteger()]
            save(pixTransactionCheckoutLimitChangeRequest.customer, CustomerAlertNotificationType.PIX_TRANSACTION_CHECKOUT_LIMIT_INITIAL_NIGHTLY_HOUR_CHANGE_REQUEST_APPROVED, alertInfo)
        })
    }

    public void notifyPushNotificationAttemptFail(Customer customer, PushNotificationConfig pushNotificationConfig) {
        Utils.withSafeException({
            Map params = [
                entityLabel: pushNotificationConfig.name,
            ]

            save(customer, CustomerAlertNotificationType.PUSH_NOTIFICATION_ATTEMPT_FAIL, [params: params])
        })
    }

    public void notifyPushNotificationQueueInterrupted(Customer customer, PushNotificationConfig pushNotificationConfig) {
        Utils.withSafeException({
            Map params = [
                entityLabel: pushNotificationConfig.name,
            ]

            save(customer, CustomerAlertNotificationType.PUSH_NOTIFICATION_QUEUE_INTERRUPTED, [params: params])
        })
    }

    public void notifyPushNotificationQueueInterruptedSevenDays(Customer customer, PushNotificationConfig pushNotificationConfig) {
        Utils.withSafeException({
            Map params = [
                entityLabel: pushNotificationConfig.name,
            ]

            save(customer, CustomerAlertNotificationType.PUSH_NOTIFICATION_QUEUE_INTERRUPTED_7_DAYS, [params: params])
        })
    }

    public void notifyPushNotificationQueueInterruptedFourteenDays(Customer customer, PushNotificationConfig pushNotificationConfig) {
        Utils.withSafeException({
            Map params = [
                entityLabel: pushNotificationConfig.name,
            ]

            save(customer, CustomerAlertNotificationType.PUSH_NOTIFICATION_QUEUE_INTERRUPTED_14_DAYS, [params: params])
        })
    }

    public void notifyAsaasCardTransactionAuthorized(Customer customer, NotifyTransactionAdapter notifyTransactionAdapter) {
        Utils.withSafeException({
            Map params = [
                externalId: notifyTransactionAdapter.transactionExternalId,
                asaasCardId: notifyTransactionAdapter.asaasCardId,
                value: notifyTransactionAdapter.value,
                establishmentName: notifyTransactionAdapter.establishmentName
            ]

            save(customer, CustomerAlertNotificationType.ASAAS_CARD_TRANSACTION_AUTHORIZED, [params: params])
        })
    }

    public void notifyAsaasCardTransactionRefused(AsaasCard asaasCard, NotifyTransactionAdapter notifyTransactionAdapter) {
        Utils.withSafeException({
            Map params = [
                value: notifyTransactionAdapter.value,
                establishmentName: notifyTransactionAdapter.establishmentName,
                refusalReason: notifyTransactionAdapter.refusalReason.name(),
                asaasCardType: asaasCard.type.name()
            ]

            save(asaasCard.customer, CustomerAlertNotificationType.ASAAS_CARD_TRANSACTION_REFUSED, [params: params])
        })
    }

    public void notifyAsaasCardTransactionRefunded(AsaasCard asaasCard, NotifyTransactionAdapter notifyTransactionAdapter) {
        Utils.withSafeException({
            Map params = [
                externalId: notifyTransactionAdapter.transactionExternalId,
                asaasCardId: notifyTransactionAdapter.asaasCardId,
                value: notifyTransactionAdapter.value,
                establishmentName: notifyTransactionAdapter.establishmentName
            ]

            save(asaasCard.customer, CustomerAlertNotificationType.ASAAS_CARD_TRANSACTION_REFUNDED, [params: params])
        })
    }

    public void notifyAsaasCardTransactionRefundCancelled(AsaasCard asaasCard, NotifyTransactionAdapter notifyTransactionAdapter) {
        Utils.withSafeException({
            Map params = [
                externalId: notifyTransactionAdapter.transactionExternalId,
                asaasCardId: notifyTransactionAdapter.asaasCardId,
                value: notifyTransactionAdapter.value,
                refundDate: CustomDateUtils.fromDate(notifyTransactionAdapter.refundDate)
            ]

            save(asaasCard.customer, CustomerAlertNotificationType.ASAAS_CARD_TRANSACTION_REFUND_CANCELLED, [params: params])
        })
    }

    public void notifyAsaasCardUnpaidBillBlock(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_UNPAID_BILL_BLOCK)
        })
    }

    public void notifyAsaasCardCreditEnabledLimitChanged(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.ASAAS_CARD_CREDIT_ENABLED_LIMIT_CHANGED)
        })
    }

    public void notifyFacematchLoginUnlockRequested(Customer customer, LoginUnlockRequest loginUnlockRequest) {
        Utils.withSafeException({
            Map params = [loginUnlockRequestDateCreated: CustomDateUtils.fromDateWithTime(loginUnlockRequest.dateCreated)]

            save(customer, CustomerAlertNotificationType.FACEMATCH_LOGIN_UNLOCK_REQUESTED, [params: params])
        })
    }

    public void notifyFacematchLoginUnlockApproved(FacematchCriticalAction facematchCriticalAction) {
        Utils.withSafeException({
            Map params = [
                loginUnlockRequestLastUpdated:  CustomDateUtils.fromDateWithTime(facematchCriticalAction.loginUnlockRequest.lastUpdated),
                username: facematchCriticalAction.requester.username
            ]

            save(facematchCriticalAction.requester.customer, CustomerAlertNotificationType.FACEMATCH_LOGIN_UNLOCK_APPROVED, [params: params])
        })
    }

    public void notifyCustomerOnUserCreation(User user) {
        Utils.withSafeException({
            String createdByUsername = user.createdBy ? user.createdBy.username : user.username
            Map params = [username: user.username, createdByUsername: createdByUsername]

            save(user.customer, CustomerAlertNotificationType.USER_CREATION, [params: params])
        })
    }

    public void notifyBaseErpIntegrated(Customer customer) {
        Utils.withSafeException({ save(customer, CustomerAlertNotificationType.BASE_ERP_INTEGRATED) })
    }

    public void notifyNexinvoiceIntegrated(Customer customer) {
        Utils.withSafeException({ save(customer, CustomerAlertNotificationType.NEXINVOICE_INTEGRATED) })
    }

    public void notifyInstallmentEnding(Installment installment) {
        Utils.withSafeException({ createInstallmentEndingAlert(installment) })
    }

    public void notifyCustomerOnSelfieUpload(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.SELFIE_UPLOAD, [params: params])
        })
    }

    public void notifyNewUserKnownDevice(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.NEW_USER_KNOWN_DEVICE, [params: params])
        })
    }

    public void notifyUserPasswordUpdated(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.USER_PASSWORD_UPDATED, [params: params])
        })
    }

    public void notifyUserUpdated(User user, User updatedByUser) {
        Utils.withSafeException({
            String updatedByUsername = updatedByUser ? updatedByUser.username : user.username
            Map params = [username: user.username, updatedByUsername: updatedByUsername]

            save(user.customer, CustomerAlertNotificationType.USER_UPDATED, [params: params])
        })
    }

    public void notifyApiKeyCreated(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.API_KEY_CREATED, [params: params])
        })
    }

    public void notifyPasswordRecoveryAttempt(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.PASSWORD_RECOVERY_ATTEMPT, [params: params])
        })
    }

    public void notifyCriticalActionConfigDisabled(User user, String criticalActionConfigDisabledMessage) {
        Utils.withSafeException({
            Map params = [username: user.username, criticalActionConfigDisabledMessage: criticalActionConfigDisabledMessage]

            save(user.customer, CustomerAlertNotificationType.CRITICAL_ACTION_CONFIG_DISABLED, [params: params])
        })
    }

    public void notifyFacematchCriticalActionAuthorized(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.FACEMATCH_CRITICAL_ACTION_AUTHORIZED, [params: params])
        })
    }

    public void notifyCustomerUpdateRequested(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.CUSTOMER_UPDATE_REQUESTED, [params: params])
        })
    }

    public void notifyAuthorizationDeviceUpdateRequested(User user) {
        Utils.withSafeException({
            Map params = [username: user.username]

            save(user.customer, CustomerAlertNotificationType.AUTHORIZATION_DEVICE_UPDATE_REQUESTED, [params: params])
        })
    }

    public void notifyCustomNotificationTemplateAnalysisApproved(CustomNotificationTemplate customTemplate) {
        Utils.withSafeException({
            Map params = [
                eventLabel: customTemplate.templateGroup.getLabel(),
                templateType: customTemplate.type.getName(),
                activeTab: customTemplate.type.toString().toLowerCase() + "Tab"
            ]

            save(
                customTemplate.customer,
                CustomerAlertNotificationType.CUSTOM_NOTIFICATION_TEMPLATE_ANALYSIS_APPROVED,
                [objectId: customTemplate.templateGroupId, params: params]
            )
        })
    }

    public void notifyCustomNotificationTemplateAnalysisReproved(CustomNotificationTemplate customTemplate) {
        Utils.withSafeException({
            Map params = [
                eventLabel: customTemplate.templateGroup.getLabel(),
                templateType: customTemplate.type.getName(),
                activeTab: customTemplate.type.toString().toLowerCase() + "Tab"
            ]

            save(
                customTemplate.customer,
                CustomerAlertNotificationType.CUSTOM_NOTIFICATION_TEMPLATE_ANALYSIS_REPROVED,
                [objectId: customTemplate.templateGroupId, params: params]
            )
        })
    }

    public void notifyCustomerOnCommercialInfoExpiration(Customer customer) {
        Utils.withSafeException({
            save(customer, CustomerAlertNotificationType.CUSTOMER_COMMERCIAL_INFO_EXPIRATION, [:])
        })
    }

    public void notifyInstantTextMessageInvalidMessageRecipient(Customer customer, Date failDate) {
        Utils.withSafeException({
            Map params = [failDate: CustomDateUtils.formatDate(failDate)]
            save(customer, CustomerAlertNotificationType.INSTANT_TEXT_MESSAGE_INVALID_MESSAGE_RECIPIENT, [params: params])
        })
    }

    private CustomerAlertNotification save(Customer customer, CustomerAlertNotificationType alertType, Map alertInfo) {
        Boolean hasCustomerAlertNotificationDisabled = CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_CUSTOMER_ALERT_NOTIFICATION)
        if (hasCustomerAlertNotificationDisabled) return null

        CustomerAlertNotification customerAlertNotification = new CustomerAlertNotification()
        customerAlertNotification.customer = customer
        customerAlertNotification.alertType = alertType
        customerAlertNotification.alertInfo = parseAlertInfoJson(alertInfo)
        customerAlertNotification.save(failOnError: true)
        return customerAlertNotification
    }

    private void saveDunningPaid(Customer customer, Long id) {
        CustomerAlertNotification latestNotification = CustomerAlertNotification.findLatestNotVisualized(customer, CustomerAlertNotificationType.DUNNING_PAID)

        if (latestNotification) {
            increaseNotificationCount(latestNotification, id)
        } else {
            Map alertInfo = [:]
            alertInfo.params = [idList: [id], count: 1]
            save(customer, CustomerAlertNotificationType.DUNNING_PAID, alertInfo)
        }
    }

    private CustomerAlertNotification increaseNotificationCount(CustomerAlertNotification notification, Long id) {
        Map notificationParams = notification.buildAlertInfoMap().params
        Map alertInfo = [:]
        alertInfo.params = [count: 1, idList: id]

        if (notificationParams) {
            if (notificationParams.count) alertInfo.params.count = notificationParams.count + 1
            if (notificationParams.idList) alertInfo.params.idList = notificationParams.idList + id
        }

        notification.displayed = false
        notification.alertInfo = parseAlertInfoJson(alertInfo)
        notification.alertDate = new Date()
        notification.save(failOnError: true)

        return notification
    }

    private String parseAlertInfoJson(Map data) {
        return data ? (data as JSON).toString() : null
    }

    private CustomerAlertNotification save(Customer customer, CustomerAlertNotificationType alertType) {
        return save(customer, alertType, null)
    }

    private void createBillPaymentFailedAlert(Bill bill) {
        Map alertInfo = [:]

        alertInfo.params = [value: bill.value, date: formatToDatabaseDatePattern(bill.scheduleDate)]
        alertInfo.objectId = bill.id

        save(bill.customer, CustomerAlertNotificationType.BILL_PAYMENT_FAILED, alertInfo)
    }

    private void createBillFailureForInsufficientBalance(List<Bill> billList, Customer customer) {
        Map alertInfo = [:]

        if (billList.size() > 1) {
            alertInfo.titleParams = [billCount: billList.size()]
            save(customer, CustomerAlertNotificationType.BILL_FAILED_INSUFFICIENT_BALANCE_GROUPED_INFO, alertInfo)
            return
        }

        alertInfo.params = [value: billList.first().value]
        alertInfo.objectId = billList.first().id

        save(customer, CustomerAlertNotificationType.BILL_FAILED_INSUFFICIENT_BALANCE, alertInfo)
    }

    private void createBillPaidAlert(Bill bill) {
        Map alertInfo = [:]
        alertInfo.params = [value: bill.value]
        alertInfo.objectId = bill.id

        save(bill.customer, CustomerAlertNotificationType.BILL_PAID, alertInfo)
    }

    private void createBillRefundedAlert(Bill bill) {
        Map alertInfo = [:]
        alertInfo.params = [value: bill.value]
        alertInfo.objectId = bill.id

        save(bill.customer, CustomerAlertNotificationType.BILL_REFUNDED, alertInfo)
    }

    private void createBillCancelledAlert(Bill bill) {
        Map alertInfo = [:]
        alertInfo.params = [value: bill.value]
        alertInfo.objectId = bill.id

        save(bill.customer, CustomerAlertNotificationType.BILL_CANCELLED, alertInfo)
    }

    private void createSubscriptionEndingAlert(Subscription subscription) {
        Map alertInfo = [:]
        alertInfo.params = [value: subscription.value]
        alertInfo.objectId = subscription.id

        save(subscription.provider, CustomerAlertNotificationType.SUBSCRIPTION_ENDING, alertInfo)
    }

    private void createBillScheduledDate(Customer customer, Date scheduleDate, Integer billCount) {
        Map alertInfo = [:]
        alertInfo.titleParams = [count: billCount, scheduledDate: formatToDatabaseDatePattern(scheduleDate)]
        alertInfo.params = [count: billCount]

        save(customer, CustomerAlertNotificationType.BILL_SCHEDULED_DATE, alertInfo)
    }

    private void createTransferFailedAlert(CreditTransferRequest creditTransferRequest) {
        Map alertInfo = [:]

        String accountName = creditTransferRequest.bankAccountInfo.accountName ?: creditTransferRequest.bankAccountInfo.bank.name

        alertInfo.params = [value: creditTransferRequest.value, accountName: accountName, date: formatToDatabaseDatePattern(creditTransferRequest.estimatedDebitDate)]
        alertInfo.objectId = creditTransferRequest.id

        save(creditTransferRequest.provider, CustomerAlertNotificationType.TRANSFER_FAILED, alertInfo)
    }

    private void createDocumentAnalysisAlert(DocumentAnalysis documentAnalysis) {
        if (documentAnalysis.status == DocumentAnalysisStatus.APPROVED) {
            save(documentAnalysis.customer, CustomerAlertNotificationType.DOCUMENTS_ANALYSIS_APPROVED)
        } else if (documentAnalysis.status == DocumentAnalysisStatus.REJECTED) {
            save(documentAnalysis.customer, CustomerAlertNotificationType.DOCUMENTS_ANALYSIS_REJECTED)
        }
    }

    private void createCompulsoryCustomerRegisterUpdateAlert(Customer customer) {
        save(customer, CustomerAlertNotificationType.COMPULSORY_CUSTOMER_REGISTER_UPDATE, [:])
    }

    private void createCommercialInfoAnalysisAlert(CustomerUpdateRequest customerUpdateRequest) {
        if (customerUpdateRequest.status == Status.APPROVED) {
            save(customerUpdateRequest.provider, CustomerAlertNotificationType.COMMERCIAL_INFO_APPROVED)
        } else {
            save(customerUpdateRequest.provider, CustomerAlertNotificationType.COMMERCIAL_INFO_REJECTED)
        }
    }

    private void createAntecipationRequestAlert(ReceivableAnticipation receivableanticipation) {
        Map alertInfo = [:]

        alertInfo.params = [date: formatToDatabaseDatePattern(receivableanticipation.anticipationDate)]
        alertInfo.objectId = receivableanticipation.id

        if (receivableanticipation.status == ReceivableAnticipationStatus.DENIED) {
            save(receivableanticipation.customer, CustomerAlertNotificationType.ANTECIPATION_DENIED, alertInfo)
        }
    }

    private void createSmsTokenPhoneChangeRequestAlert(SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest) {
        if (smsTokenPhoneChangeRequest.status.isApproved()) {
            save(smsTokenPhoneChangeRequest.customer, CustomerAlertNotificationType.SMS_TOKEN_PHONE_CHANGE_REQUEST_APPROVED)
        } else {
            save(smsTokenPhoneChangeRequest.customer, CustomerAlertNotificationType.SMS_TOKEN_PHONE_CHANGE_REQUEST_REJECTED)
        }
    }

    private void createInstallmentEndingAlert(Installment installment) {
        Map alertInfo = [:]
        alertInfo.params = [value: installment.getValue()]
        alertInfo.objectId = installment.id

        save(installment.getProvider(), CustomerAlertNotificationType.INSTALLMENT_ENDING, alertInfo)
    }

    private String formatToDatabaseDatePattern(Date date) {
        return CustomDateUtils.fromDate(date, DATABASE_DATETIME_FORMAT)
    }
}
