package com.asaas.service.notification

import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationDispatcherCustomer
import com.asaas.domain.notification.NotificationDispatcherCustomerAccount
import com.asaas.domain.notification.NotificationDispatcherPaymentNotificationOutbox
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentViewingInfo
import com.asaas.notification.dispatcher.NotificationDispatcherCustomerAccountStatus
import com.asaas.notification.dispatcher.NotificationDispatcherCustomerStatus
import com.asaas.notification.dispatcher.cache.NotificationDispatcherCustomerAccountCacheVO
import com.asaas.notification.dispatcher.cache.NotificationDispatcherCustomerCacheVO
import com.asaas.payment.PaymentStatus
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

import java.time.Instant
import java.util.concurrent.TimeUnit
import org.redisson.api.RBucket

@Transactional
class NotificationDispatcherCustomerAccountService {

    def notificationDispatcherCustomerService
    def notificationDispatcherCustomerCacheService
    def notificationDispatcherCustomerAccountCacheService
    def notificationDispatcherPaymentNotificationMigrationOutboxService

    public void prepareCustomerAccountMigrations() {
        final Integer minutesSinceCustomerCreationToAvoidConflict = 1
        List<Map> processingCustomerMigrationMapList = NotificationDispatcherCustomer.query([
            columnList: ["id", "customer.id"],
            status: NotificationDispatcherCustomerStatus.PROCESSING,
            "dateCreated[le]": CustomDateUtils.sumMinutes(new Date(), minutesSinceCustomerCreationToAvoidConflict * -1),
            sort: "id",
            order: "asc"
        ]).list()

        if (!processingCustomerMigrationMapList) return

        for (Map customerMigrationMap : processingCustomerMigrationMapList) {
            List<Long> customerAccountIdList = listCustomerAccountId(customerMigrationMap."customer.id")
            if (!customerAccountIdList) {
                NotificationDispatcherCustomer notificationDispatcherCustomer = NotificationDispatcherCustomer.get(customerMigrationMap.id)
                notificationDispatcherCustomerService.setAsSynchronizing(notificationDispatcherCustomer)
                continue
            }

            final Integer minItemsPerThread = 325
            ThreadUtils.processWithThreadsOnDemand(customerAccountIdList, minItemsPerThread, { List<Long> customerAccountIdSubList ->
                final Integer batchSize = 50
                Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerAccountIdSubList, batchSize, batchSize, { Long customerAccountId ->
                    CustomerAccount customerAccount = CustomerAccount.read(customerAccountId)
                    save(customerAccount)
                }, [
                    logErrorMessage: "NotificationDispatcherCustomerAccountService.prepareCustomerAccountMigrations >> Falha ao preparar migração de CustomerAccount em lote",
                    appendBatchToLogErrorMessage: true
                ])
            })
        }
    }

    public void processMigration() {
        Integer maxCustomerAccountMigrationRate = getMaxCustomerAccountMigrationRate()
        List<Long> notificationDispatcherCustomerAccountIdList = NotificationDispatcherCustomerAccount.query([
            column: "id",
            status: NotificationDispatcherCustomerAccountStatus.PENDING,
            sort: "id",
            order: "asc"
        ]).list(max: maxCustomerAccountMigrationRate)

        if (!notificationDispatcherCustomerAccountIdList) return

        final Integer minItemsPerThread = Math.max(50, maxCustomerAccountMigrationRate.intdiv(8))
        ThreadUtils.processWithThreadsOnDemand(notificationDispatcherCustomerAccountIdList, minItemsPerThread, { List<Long> notificationDispatcherCustomerAccountIdSubList ->
            final Integer batchSize = 50
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(notificationDispatcherCustomerAccountIdSubList, batchSize, batchSize, { Long notificationDispatcherCustomerAccountId ->
                NotificationDispatcherCustomerAccount notificationDispatcherCustomerAccount = NotificationDispatcherCustomerAccount.get(notificationDispatcherCustomerAccountId)

                if (migrateCustomerAccount(notificationDispatcherCustomerAccount)) {
                    confirmMigration(notificationDispatcherCustomerAccount)
                }
            }, [
                logErrorMessage: "NotificationDispatcherCustomerAccountService.processMigration >> Falha durante a migração",
                appendBatchToLogErrorMessage: true
            ])
        })
    }

    public void saveIfCustomerMigrated(CustomerAccount customerAccount) {
        Boolean isMigrated = NotificationDispatcherCustomer.query([exists: true, customerId: customerAccount.providerId]).get().asBoolean()
        if (isMigrated) save(customerAccount)
    }

    public Boolean shouldProcessExternally(Long customerAccountId, Boolean bypassMigrationInstant) {
        NotificationDispatcherCustomerAccountCacheVO notificationDispatcherCustomerAccountCacheVO = notificationDispatcherCustomerAccountCacheService.byCustomerAccountId(customerAccountId)
        if (!notificationDispatcherCustomerAccountCacheVO) return false
        if (!notificationDispatcherCustomerAccountCacheVO.status.isFullyIntegrated()) return false

        NotificationDispatcherCustomerCacheVO notificationDispatcherCustomerCacheVO = notificationDispatcherCustomerCacheService.byCustomerId(notificationDispatcherCustomerAccountCacheVO.customerId)
        if (!notificationDispatcherCustomerCacheVO.enabled) return false
        if (bypassMigrationInstant) return notificationDispatcherCustomerCacheVO.status.isFullyIntegrated()

        Instant migrationDeadlineInstant = Instant.ofEpochMilli(notificationDispatcherCustomerAccountCacheVO.migrationDeadlineInstant)
        if (Instant.now().isAfter(migrationDeadlineInstant)) return true

        return false
    }

    public void confirmCustomerAccountFullyIntegrated(Long customerAccountId) {
        final Long migrationEstimatedTime = TimeUnit.MINUTES.toMillis(10)
        NotificationDispatcherCustomerAccount notificationDispatcherCustomerAccount = NotificationDispatcherCustomerAccount.query([customerAccountId: customerAccountId]).get()
        notificationDispatcherCustomerAccount.status = NotificationDispatcherCustomerAccountStatus.FULLY_INTEGRATED
        notificationDispatcherCustomerAccount.migrationDeadlineInstant = Instant.now().toEpochMilli() + migrationEstimatedTime
        notificationDispatcherCustomerAccount.save(failOnError: true)
    }

    public void setMaxCustomerAccountMigrationRate(Integer maxCustomerAccountRate) {
        RBucket<Integer> maxCustomerAccountMigrationRate = RedissonProxy.instance.getBucket("NotificationDispatcherMaxCustomerAccountMigrationRate:value", Integer)
        maxCustomerAccountMigrationRate.set(maxCustomerAccountRate)
    }

    private Integer getMaxCustomerAccountMigrationRate() {
        RBucket<Integer> maxCustomerAccountMigrationRate = RedissonProxy.instance.getBucket("NotificationDispatcherMaxCustomerAccountMigrationRate:value", Integer)
        if (maxCustomerAccountMigrationRate?.get()) return maxCustomerAccountMigrationRate.get()

        return 500
    }

    private Boolean migrateCustomerAccount(NotificationDispatcherCustomerAccount notificationDispatcherCustomerAccount) {
        notificationDispatcherPaymentNotificationMigrationOutboxService.saveCustomerAccountUpdated(notificationDispatcherCustomerAccount.customerAccount)

        if (!notificationDispatcherCustomerAccount.lastPaymentIdMigrated) {
            migrateCustomerAccountNotification(notificationDispatcherCustomerAccount.customerAccount)
        }

        List<Long> migratedPaymentsIdList = migrateCustomerAccountPayments(notificationDispatcherCustomerAccount)
        migratePaymentViewing(migratedPaymentsIdList)
        migratePaymentDunnings(notificationDispatcherCustomerAccount.customerAccount, migratedPaymentsIdList)

        if (!migratedPaymentsIdList) return true

        notificationDispatcherCustomerAccount.lastPaymentIdMigrated = migratedPaymentsIdList.max()
        notificationDispatcherCustomerAccount.save()

        return false
    }

    private void migrateCustomerAccountNotification(CustomerAccount customerAccount) {
        List<Notification> notificationList = Notification.query([
            customerAccountId: customerAccount.id,
            customer: customerAccount.provider,
            enabled: true,
            sort: "id",
            order: "asc"
        ]).list(readOnly: true)

        List<Notification> notificationListWithAnyNotificationMethodEnabled = notificationList.findAll({ it.hasAnyNotificationMethodEnabled() })
        if (!notificationListWithAnyNotificationMethodEnabled) return
        notificationDispatcherPaymentNotificationMigrationOutboxService.saveNotificationUpdatedBatch(customerAccount, notificationListWithAnyNotificationMethodEnabled)
    }

    private void save(CustomerAccount customerAccount) {
        NotificationDispatcherCustomer notificationDispatcherCustomer = NotificationDispatcherCustomer.query([customerId: customerAccount.providerId]).get()

        NotificationDispatcherCustomerAccount dispatcherCustomerAccount = new NotificationDispatcherCustomerAccount()
        dispatcherCustomerAccount.customer = customerAccount.provider
        dispatcherCustomerAccount.notificationDispatcherCustomer = notificationDispatcherCustomer
        dispatcherCustomerAccount.customerAccount = customerAccount
        if (notificationDispatcherCustomer.status.isFullyIntegrated()) {
            dispatcherCustomerAccount.status = NotificationDispatcherCustomerAccountStatus.FULLY_INTEGRATED
            dispatcherCustomerAccount.migrationDeadlineInstant = Instant.now().toEpochMilli()
        } else {
            dispatcherCustomerAccount.status = NotificationDispatcherCustomerAccountStatus.PENDING
        }

        dispatcherCustomerAccount.save(failOnError: true)
    }

    private List<Long> migrateCustomerAccountPayments(NotificationDispatcherCustomerAccount notificationDispatcherCustomerAccount) {
        Map paymentSearch = [
            customerAccountId: notificationDispatcherCustomerAccount.customerAccountId,
            customerId: notificationDispatcherCustomerAccount.customerId,
            "dueDate[ge]": CustomDateUtils.sumDays(new Date(), NotificationDispatcherPaymentNotificationOutbox.PAYMENT_DAYS_TTL_AFTER_DUE_DATE * -1),
            "status[notIn]": [PaymentStatus.RECEIVED, PaymentStatus.RECEIVED_IN_CASH],
            sort: "id",
            order: "asc"
        ]
        if (notificationDispatcherCustomerAccount.lastPaymentIdMigrated) paymentSearch += ["id[gt]": notificationDispatcherCustomerAccount.lastPaymentIdMigrated]

        List<Payment> paymentList = Payment.query(paymentSearch).list(readOnly: true, max: 1000)

        Map<Installment, List<Payment>> paymentByInstallmentMap = paymentList.groupBy { it.installment }
        for (Installment installment : paymentByInstallmentMap.keySet()) {
            if (installment) notificationDispatcherPaymentNotificationMigrationOutboxService.saveInstallmentUpdated(installment)
            for (Payment payment : paymentByInstallmentMap[installment]) {
                notificationDispatcherPaymentNotificationMigrationOutboxService.savePaymentUpdated(payment)
            }
        }

        return paymentList.collect { it.id }
    }

    private void migratePaymentDunnings(CustomerAccount customerAccount, List<Long> paymentIdList) {
        List<PaymentDunning> dunningList = []
        final Integer maxItemsPerQuery = 1000
        for (List<Long> paymentIdCollatedList : paymentIdList.collate(maxItemsPerQuery)) {
            dunningList.addAll(PaymentDunning.query([
                customerId: customerAccount.provider.id,
                "paymentId[in]": paymentIdCollatedList,
                sort: "id",
                order: "asc"
            ]).list(readOnly: true))
        }
        for (PaymentDunning dunning : dunningList) {
            notificationDispatcherPaymentNotificationMigrationOutboxService.savePaymentDunning(dunning)
        }
    }

    private void migratePaymentViewing(List<Long> paymentIdList) {
        List<PaymentViewingInfo> paymentViewingList = []
        final Integer maxItemsPerQuery = 1000
        for (List<Long> paymentIdCollatedList : paymentIdList.collate(maxItemsPerQuery)) {
            paymentViewingList.addAll(PaymentViewingInfo.query(["paymentId[in]": paymentIdCollatedList, sort: "id", order: "asc"]).list(readOnly: true))
        }
        for (PaymentViewingInfo paymentViewing in paymentViewingList) {
            notificationDispatcherPaymentNotificationMigrationOutboxService.savePaymentViewed(paymentViewing.payment)
        }
    }

    private List<Long> listCustomerAccountId(Long customerId) {
        Map search = [
            column: "id",
            customerId: customerId,
            notificationDisabled: false,
            "notificationDispatcherCustomerAccount[notExists]": true,
            sort: "id",
            order: "asc"
        ]

        return CustomerAccount.query(search).list(max: 5000)
    }

    private void confirmMigration(NotificationDispatcherCustomerAccount notificationDispatcherCustomerAccount) {
        if (!notificationDispatcherCustomerAccount.notificationDispatcherCustomer.manualMigration) {
            notificationDispatcherPaymentNotificationMigrationOutboxService.saveCustomerAccountFullyIntegrated(notificationDispatcherCustomerAccount.customerAccountId)
        }

        notificationDispatcherCustomerAccount.status = NotificationDispatcherCustomerAccountStatus.SYNCHRONIZED
        notificationDispatcherCustomerAccount.save(failOnError: true)
    }
}
