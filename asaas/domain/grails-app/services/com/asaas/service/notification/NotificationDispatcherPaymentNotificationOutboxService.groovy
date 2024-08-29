package com.asaas.service.notification

import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationDispatcherCustomerAccount
import com.asaas.domain.notification.NotificationDispatcherPaymentNotificationOutbox
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentViewingInfo
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transactionreceipt.TransactionReceipt
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationPriority
import com.asaas.notification.dispatcher.NotificationDispatcherCustomerAccountStatus
import com.asaas.notification.dispatcher.NotificationDispatcherPaymentNotificationOutboxEvent
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPaymentNotificationBaseDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCreditCardSubscriptionPaymentCreatedAndNotAuthorizedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCreditCardTransactionAwaitingAnalysisRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCreditCardTransactionReprovedByAnalysisRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCustomerAccountUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishInstallmentUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishNotificationUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentConfirmedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentCreatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentCreditCardExpiredRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentDeletedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentDunningUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentOverdueTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentReceivedInCashRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentRehydrationRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentViewedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishTransactionReceiptCreatedRequestDTO
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.NewRelicUtils
import com.asaas.utils.ThreadUtils
import grails.transaction.Transactional

@Transactional
class NotificationDispatcherPaymentNotificationOutboxService {

    def featureFlagService
    def grailsLinkGenerator
    def notificationDispatcherManagerService
    def notificationDispatcherCustomerAccountService

    public void saveCustomerAccountUpdated(CustomerAccount customerAccount) {
        if (!shouldSaveOutboxEvent(customerAccount, true)) return

        NotificationDispatcherPublishCustomerAccountUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishCustomerAccountUpdatedRequestDTO(customerAccount)

        save(customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.CUSTOMER_ACCOUNT_UPDATED, requestDTO)
    }

    public void saveNotificationUpdated(Notification notification) {
        if (!shouldSaveOutboxEvent(notification.customerAccount)) return

        NotificationDispatcherPublishNotificationUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishNotificationUpdatedRequestDTO(notification)

        save(notification.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.NOTIFICATION_UPDATED, requestDTO)
    }

    public void saveInstallmentUpdated(Installment installment) {
        if (!shouldSaveOutboxEvent(installment.customerAccount)) return

        NotificationDispatcherPublishInstallmentUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishInstallmentUpdatedRequestDTO(installment)

        save(installment.customerAccountId, NotificationDispatcherPaymentNotificationOutboxEvent.INSTALLMENT_UPDATED, requestDTO)
    }

    public void savePaymentCreated(Payment payment, Boolean shouldNotifyImmediately, NotificationPriority notificationPriority) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        NotificationDispatcherPublishPaymentCreatedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentCreatedRequestDTO(payment, shouldNotifyImmediately, notificationPriority)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_CREATED, requestDTO)
    }

    public void saveCreditCardSubscriptionPaymentCreatedAndNotAuthorized(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        NotificationDispatcherPublishCreditCardSubscriptionPaymentCreatedAndNotAuthorizedRequestDTO requestDTO = new NotificationDispatcherPublishCreditCardSubscriptionPaymentCreatedAndNotAuthorizedRequestDTO(payment)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.CREDIT_CARD_SUBSCRIPTION_PAYMENT_CREATED_AND_NOT_AUTHORIZED, requestDTO)
    }

    public void savePaymentUpdated(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        if (isOverDueDateTTL(payment.dueDate)) rehydratePayment(payment)

        NotificationDispatcherPublishPaymentUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentUpdatedRequestDTO(payment, false)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_UPDATED, requestDTO)
    }

    public void savePaymentDetailsUpdated(Payment payment, Boolean shouldNotify, Date previousDueDate) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        if (isOverDueDateTTL(previousDueDate)) rehydratePayment(payment)

        NotificationDispatcherPublishPaymentUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentUpdatedRequestDTO(payment, shouldNotify)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_UPDATED, requestDTO)
    }

    public void savePaymentDeleted(Payment payment, Boolean notifyCustomerAccount) {
        if (!shouldSaveOutboxEvent(payment.customerAccount, true)) return

        if (isOverDueDateTTL(payment.dueDate)) rehydratePayment(payment)

        NotificationDispatcherPublishPaymentDeletedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentDeletedRequestDTO(payment, notifyCustomerAccount)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_DELETED, requestDTO)
    }

    public void savePaymentOverdue(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        NotificationDispatcherPublishPaymentOverdueTO requestDTO = new NotificationDispatcherPublishPaymentOverdueTO(payment)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_OVERDUE, requestDTO)
    }

    public void savePaymentViewed(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        if (isOverDueDateTTL(payment.dueDate)) rehydratePayment(payment)

        NotificationDispatcherPublishPaymentViewedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentViewedRequestDTO(payment)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_VIEWED, requestDTO)
    }

    public void savePaymentReceivedInCash(Payment payment, Boolean shouldNotify) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        if (isOverDueDateTTL(payment.dueDate)) rehydratePayment(payment)

        NotificationDispatcherPublishPaymentReceivedInCashRequestDTO requestDTO = new NotificationDispatcherPublishPaymentReceivedInCashRequestDTO(payment, shouldNotify)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_RECEIVED_IN_CASH, requestDTO)
    }

    public void savePaymentConfirmed(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        Boolean isPixDynamicQrCode = payment.billingType.isPix() && PixTransaction.credit([column: "originType", payment: payment]).get()?.isDynamicQrCode()
        NotificationDispatcherPublishPaymentConfirmedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentConfirmedRequestDTO(payment, isPixDynamicQrCode)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_CONFIRMED, requestDTO)
    }

    public void savePaymentCreditCardExpired(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        NotificationDispatcherPublishPaymentCreditCardExpiredRequestDTO requestDTO = new NotificationDispatcherPublishPaymentCreditCardExpiredRequestDTO(payment)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_CREDIT_CARD_EXPIRED, requestDTO)
    }

    public void savePaymentDunning(PaymentDunning paymentDunning) {
        if (!shouldSaveOutboxEvent(paymentDunning.payment.customerAccount)) return

        if (isOverDueDateTTL(paymentDunning.payment.dueDate)) {
            rehydratePayment(paymentDunning.payment)
            return
        }

        NotificationDispatcherPublishPaymentDunningUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentDunningUpdatedRequestDTO(paymentDunning)

        save(paymentDunning.payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_DUNNING, requestDTO)
    }

    public void saveCreditCardTransactionReprovedByAnalysis(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        NotificationDispatcherPublishCreditCardTransactionReprovedByAnalysisRequestDTO requestDTO = new NotificationDispatcherPublishCreditCardTransactionReprovedByAnalysisRequestDTO(payment.id)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.CREDIT_CARD_TRANSACTION_REPROVED_BY_ANALYSIS, requestDTO)
    }

    public void saveCreditCardTransactionAwaitingAnalysis(Payment payment) {
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        BigDecimal valueAwaitingRiskAnalysis = payment.value
        if (payment.installment) valueAwaitingRiskAnalysis = Payment.sumValue([status: PaymentStatus.AWAITING_RISK_ANALYSIS, installmentId: payment.installment.id]).get()

        NotificationDispatcherPublishCreditCardTransactionAwaitingAnalysisRequestDTO requestDTO = new NotificationDispatcherPublishCreditCardTransactionAwaitingAnalysisRequestDTO(payment.id, valueAwaitingRiskAnalysis)

        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.CREDIT_CARD_TRANSACTION_AWAITING_ANALYSIS, requestDTO)
    }

    public void saveTransactionReceiptCreatedIfNecessary(TransactionReceipt transactionReceipt) {
        if (!transactionReceipt.type.isNotificationDispatcherNotifiableReceipt()) return

        CustomerAccount customerAccount = transactionReceipt.installment ? transactionReceipt.installment.customerAccount : transactionReceipt.payment.customerAccount
        if (!shouldSaveOutboxEvent(customerAccount)) return

        List<Payment> paymentsList = transactionReceipt.installment ? transactionReceipt.installment.payments.toList() : [transactionReceipt.payment]
        for (Payment payment : paymentsList) {
            if (isOverDueDateTTL(payment.dueDate)) rehydratePayment(payment)
        }

        String receiptUrl = grailsLinkGenerator.link(controller: 'transactionReceipt', action: 'show', id: transactionReceipt.publicId, absolute: false)
        NotificationDispatcherPublishTransactionReceiptCreatedRequestDTO requestDTO = new NotificationDispatcherPublishTransactionReceiptCreatedRequestDTO(transactionReceipt, receiptUrl)

        save(customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.TRANSACTION_RECEIPT_CREATED, requestDTO)
    }

    public void saveTransactionReceiptPaymentDeletedOnDemandIfNecessary(Payment payment) {
        if (payment.installment) return
        if (!shouldSaveOutboxEvent(payment.customerAccount)) return

        if (isOverDueDateTTL(payment.dueDate)) {
            rehydratePayment(payment)
        }

        NotificationDispatcherPublishTransactionReceiptCreatedRequestDTO requestDTO =
            NotificationDispatcherPublishTransactionReceiptCreatedRequestDTO.buildPaymentDeletedOnDemand(payment)

        save(payment.customerAccountId, NotificationDispatcherPaymentNotificationOutboxEvent.TRANSACTION_RECEIPT_CREATED, requestDTO)
    }

    public void processPendingOutboxEvents() {
        List<List<Map>> listOutboxDataGroupedByCustomerAccountId = listOutboxDataGroupedByCustomerAccountId()
        if (!listOutboxDataGroupedByCustomerAccountId) return

        final Integer minItemsForNewThread = 100

        List<Long> idToDeleteList = Collections.synchronizedList(new ArrayList<Long>())
        ThreadUtils.processWithThreadsOnDemand(listOutboxDataGroupedByCustomerAccountId, minItemsForNewThread, { List<List<Map>> outboxItemListPerThread ->
            List<Map> outboxItemList = outboxItemListPerThread.flatten()
            List<Long> successfulIdList = notificationDispatcherManagerService.sendPaymentNotificationOutboxMessages(outboxItemList)
            if (successfulIdList) idToDeleteList.addAll(successfulIdList)
        })

        deleteBatch(idToDeleteList.sort())
    }

    private List<List<Map>> listOutboxDataGroupedByCustomerAccountId() {
        final Integer maxPerExecution = 500

        List<Map> outboxPaymentNotificationMapList = NotificationDispatcherPaymentNotificationOutbox.query([
            columnList: ["id", "customerAccountId", "eventName", "payload"],
            notificationDispatcherCustomerAccountStatus: NotificationDispatcherCustomerAccountStatus.FULLY_INTEGRATED,
            sort: "id",
            order: "asc"
        ]).list(max: maxPerExecution)
        if (!outboxPaymentNotificationMapList) return null

        List<List<Map>> groupedOutboxData = []
        NewRelicUtils.registerMetric("${this.getClass().getSimpleName()}/groupByCustomerAccountId", {
            groupedOutboxData = outboxPaymentNotificationMapList.groupBy { it.customerAccountId }.values() as List<List<Map>>
        })
        return groupedOutboxData
    }

    private void deleteBatch(List<Long> idToDeleteList) {
        NotificationDispatcherPaymentNotificationOutbox.where {
            "in"("id", idToDeleteList)
        }.deleteAll()
    }

    private Boolean shouldSaveOutboxEvent(CustomerAccount customerAccount, Boolean ignoreNotificationDisabled = false) {
        if (!featureFlagService.isNotificationDispatcherOutboxEnabled()) return false
        if (customerAccount.notificationDisabled && !ignoreNotificationDisabled) return false

        Boolean isMigrated = NotificationDispatcherCustomerAccount.query([customerAccountId: customerAccount.id]).get().asBoolean()
        return isMigrated
    }

    private void save(Long customerAccountId, NotificationDispatcherPaymentNotificationOutboxEvent eventName, NotificationDispatcherPaymentNotificationBaseDTO payloadObject) {
        payloadObject.updateOnly = !notificationDispatcherCustomerAccountService.shouldProcessExternally(customerAccountId, false)

        NotificationDispatcherPaymentNotificationOutbox notificationDispatcherPaymentNotificationOutbox = new NotificationDispatcherPaymentNotificationOutbox()
        notificationDispatcherPaymentNotificationOutbox.customerAccountId = customerAccountId
        notificationDispatcherPaymentNotificationOutbox.eventName = eventName
        notificationDispatcherPaymentNotificationOutbox.payload = GsonBuilderUtils.toJsonWithoutNullFields(payloadObject)

        final Integer textLimitSize = 65535
        if (notificationDispatcherPaymentNotificationOutbox.payload.length() > textLimitSize) {
            AsaasLogger.error("NotificationDispatcherPaymentNotificationOutbox.save >> Payload do outbox ultrapassou o limite de ${textLimitSize} caracteres: ${notificationDispatcherPaymentNotificationOutbox.payload}")
            return
        }

        notificationDispatcherPaymentNotificationOutbox.save(failOnError: true)
    }

    private Boolean isOverDueDateTTL(Date dueDate) {
        Integer daysSinceDueDate = CustomDateUtils.calculateDifferenceInDays(dueDate, new Date())
        return daysSinceDueDate >= NotificationDispatcherPaymentNotificationOutbox.PAYMENT_DAYS_TTL_AFTER_DUE_DATE
    }

    private void rehydratePayment(Payment payment) {
        List<PaymentDunning> paymentDunningList = PaymentDunning.query([
            paymentId: payment.id,
            customerId: payment.provider.id
        ]).list()

        Boolean viewed = PaymentViewingInfo.query([exists: true, paymentId: payment.id]).get().asBoolean()

        NotificationDispatcherPublishPaymentRehydrationRequestDTO requestDTO = new NotificationDispatcherPublishPaymentRehydrationRequestDTO(payment, paymentDunningList, viewed)
        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_REHYDRATION, requestDTO)
    }
}
