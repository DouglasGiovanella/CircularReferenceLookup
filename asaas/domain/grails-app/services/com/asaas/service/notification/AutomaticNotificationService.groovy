package com.asaas.service.notification

import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.notification.AutomaticPaymentNotification
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.notification.AutomaticPaymentNotificationStatus
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.notification.NotificationQueryBuilder
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationSchedule
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AutomaticNotificationService {

	static final String PAYMENTS_QUERY = "from Payment p where p.customerAccount = :customerAccount and p.dueDate = :dueDate and p.status = :status and p.deleted = false"
	static final String NOT_VIEWED_BOLETOS_QUERY = "from Payment p where p.customerAccount = :customerAccount and status = :status and deleted = false and dueDate = :dueDate and p.billingType in (:billingTypeList) and p.boletoBank is not null and not exists (select 1 from PaymentViewingInfo pv where pv.payment.id = p.id)"

    def automaticPaymentNotificationService
	def customerService
    def notificationDispatcherCustomerOutboxService
    def notificationDispatcherPaymentNotificationOutboxService
	def notificationRequestService

    public void sendPaymentDueDateWarning(NotificationSchedule notificationSchedule) {
        NotificationEvent notificationEvent = NotificationEvent.PAYMENT_DUEDATE_WARNING

        String notificationLogIdentifier = "[${ notificationEvent } - ${ notificationSchedule }]"
        AsaasLogger.info("AutomaticNotificationService.sendPaymentDueDateWarning >> Iniciando processamento de Notifications ${ notificationLogIdentifier }")

        List<Long> notificationTriggerIdList = NotificationTrigger.query([column: "id", event: notificationEvent, schedule: notificationSchedule]).list()

        List<Long> notificationIdList = []
        for (Long notificationTriggerId : notificationTriggerIdList) {
            notificationIdList.addAll(getPaymentDueDateWarningNotificationIdList(notificationTriggerId))
        }

        AsaasLogger.info("AutomaticNotificationService.sendPaymentDueDateWarning >> Encontradas ${ notificationIdList.size() } Notifications ${ notificationLogIdentifier }")
        processNotificationList(notificationIdList, notificationLogIdentifier)
    }

	public void sendPaymentOverdue() {
        final Integer maxItemsPerCycle = 48000
        List<Map> pendingPaymentOverdueNotificationIdList = AutomaticPaymentNotification.query([columnList: ["id", "notification.id"], notificationDate: new Date().clearTime(), status: AutomaticPaymentNotificationStatus.PENDING]).list(max: maxItemsPerCycle)

        if (!pendingPaymentOverdueNotificationIdList) return

		processAutomaticPaymentNotificationList(pendingPaymentOverdueNotificationIdList)
	}

    public void sendLinhaDigitavelNotifications() {
        NotificationEvent notificationEvent = NotificationEvent.SEND_LINHA_DIGITAVEL
        NotificationSchedule notificationSchedule = NotificationSchedule.IMMEDIATELY

        String notificationLogIdentifier = "[${ notificationEvent } - ${ notificationSchedule }]"
        AsaasLogger.info("AutomaticNotificationService.sendLinhaDigitavelNotifications >> Iniciando processamento de Notifications ${ notificationLogIdentifier }")

        Long sendLinhaDigitavelNotificationTriggerId = NotificationTrigger.query([column: "id", event: notificationEvent, schedule: notificationSchedule]).get()
        Map queryParams = [triggerId: sendLinhaDigitavelNotificationTriggerId, today: new Date().clearTime()]

        List<Long> notificationIdList = Notification.executeQuery(NotificationQueryBuilder.buildSendLinhaDigitavelIdListToSend(), queryParams)

        AsaasLogger.info("AutomaticNotificationService.sendLinhaDigitavelNotifications >> Encontradas ${ notificationIdList.size() } Notifications ${ notificationLogIdentifier }")
        processNotificationList(notificationIdList, notificationLogIdentifier)
    }

    public void sendExpiredCreditCard() {
        List<Long> paymentList = Payment.query([column: "id", dueDate: new Date().clearTime(), billingType: BillingType.MUNDIPAGG_CIELO, status: PaymentStatus.PENDING, billingInfo: true]).list()

        Utils.forEachWithFlushSession(paymentList, 50, { Long paymentId ->
            Utils.withNewTransactionAndRollbackOnError({
                Payment payment = Payment.get(paymentId)

                if (payment.provider.notificationDisabled()) return
                if (!payment.billingInfo.creditCardInfo.isExpired()) return

                notificationRequestService.saveWithoutNotification(payment.customerAccount, NotificationEvent.CREDIT_CARD_EXPIRED, payment, NotificationPriority.LOW, NotificationReceiver.CUSTOMER)
                notificationDispatcherPaymentNotificationOutboxService.savePaymentCreditCardExpired(payment)
            }, [logErrorMessage: "Erro ao notificar cartão vencido para a cobrança [${paymentId}]"])
        })
    }

    public void generatePaymentOverduePendingQueue() {
        Map queryParams = [schedule: NotificationSchedule.AFTER, event: NotificationEvent.CUSTOMER_PAYMENT_OVERDUE]
        List<Long> notificationIdList = Notification.executeQuery(NotificationQueryBuilder.buildPaymentOverdueIdList(), queryParams)
        if (!notificationIdList) return

        processGeneratePaymentOverdueNotificationQueue(notificationIdList)
    }

    public void newGeneratePaymentOverduePendingQueue() {
        List<Long> paymentOverdueNotificationIdList = getPaymentOverdueNotificationIdList()
        if (!paymentOverdueNotificationIdList) return
        AsaasLogger.info("newGeneratePaymentOverduePendingQueue >> Encontradas ${paymentOverdueNotificationIdList.size()} notifications ${paymentOverdueNotificationIdList}")

        if (AsaasEnvironment.isProduction()) return

        processGeneratePaymentOverdueNotificationQueue(paymentOverdueNotificationIdList)
    }

    public void processOverduePaymentAfterNotification(Notification notification) {
        if (notification.customerAccount.provider.isAsaasProvider() || notification.trigger.scheduleOffset == Notification.DAILY_SCHEDULE_OFFSET) return

        if (notification.customerAccount.provider.highDefaultRate == null) {
            setCustomerDefaultRate(notification.customerAccount.provider.id)
            notification.customerAccount.provider.refresh()
        }

        if (notification.customerAccount.provider.highDefaultRate) return

        List<Payment> paymentList = []

        Date dueDate = new Date().clearTime()

        for (int i = 1; i <= Notification.MAX_NOTIFICATIONS_FOR_OVERDUE_PAYMENTS; i++) {
            dueDate = CustomDateUtils.sumDays(dueDate, notification.trigger.scheduleOffset * -1)

            List<Date> dueDateList = []
            dueDateList.add(dueDate)

            Date notificationDate = new Date().clearTime()
            if (Holiday.isHoliday(notificationDate)) return

            Integer holidayDueDateSubtractionValue = 0
            while (Holiday.previousDayWasHoliday(notificationDate)) {
                dueDateList.add(CustomDateUtils.sumDays(dueDate, --holidayDueDateSubtractionValue))
                notificationDate = CustomDateUtils.sumDays(notificationDate, -1)
            }

            paymentList.addAll(Payment.query([
                customerAccount: notification.customerAccount,
                customer: notification.customerAccount.provider,
                "dueDate[in]": dueDateList,
                status: PaymentStatus.OVERDUE,
                withoutValidDunning: true,
                disableSort: true
            ]).list())
        }

        processPayments(notification, paymentList, NotificationPriority.LOW)
    }

    private void processGeneratePaymentOverdueNotificationQueue(List<Long> paymentOverdueNotificationIdList) {
        Boolean hasPaymentOverdueNotificationQueueCreatedAtToday = AutomaticPaymentNotification.query([
            exists: true,
            notificationDate: new Date().clearTime(),
            disableSort: true
        ]).get().asBoolean()

        final Integer numberOfThreads = 16
        final Integer batchSize = 200
        final Integer flushEvery = 200

        AsaasLogger.info("AutomaticNotificationService.processGeneratePaymentOverdueNotificationQueue >> Iniciando geração de fila de notificações. Total: ${paymentOverdueNotificationIdList.size()}. Validação de duplicados: ${hasPaymentOverdueNotificationQueueCreatedAtToday}")

        Utils.processWithThreads(paymentOverdueNotificationIdList, numberOfThreads, { List<Long> notificationIdPartialList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(notificationIdPartialList, batchSize, flushEvery, { Long notificationId ->
                try {
                    automaticPaymentNotificationService.save(notificationId, hasPaymentOverdueNotificationQueueCreatedAtToday)
                } catch (Exception exception) {
                    AsaasLogger.error("AutomaticNotificationService.processGeneratePaymentOverdueNotificationQueue >> Ocorreu um erro ao agendar a notificação de cobrança vencida [${notificationId}]", exception)
                }
            }, [logErrorMessage: "AutomaticNotificationService.processGeneratePaymentOverdueNotificationQueue >> Erro ao processar lote de Notifications:", appendBatchToLogErrorMessage: true])
        })
    }

    private void processNotificationList(List<Long> notificationIdList, String notificationLogIdentifier) {
        final Integer numberOfThreads = 16
        final Integer batchSize = 100
        final Integer flushEvery = 10

        Utils.processWithThreads(notificationIdList, numberOfThreads, { List<Long> notificationBatchIdList ->
            AsaasLogger.info("AutomaticNotificationService.processNotificationList >> Iniciando nova Thread para processar ${ notificationBatchIdList.size() } Notifications ${ notificationLogIdentifier }")

            Utils.forEachWithFlushSessionAndNewTransactionInBatch(notificationBatchIdList, batchSize, flushEvery, { Long notificationId ->
                processNotification(notificationId)
            }, [logErrorMessage: "AutomaticNotificationService.processNotificationList >> Erro ao processar Notifications ${ notificationLogIdentifier } : ", appendBatchToLogErrorMessage: true])
        })
    }

    private void processAutomaticPaymentNotificationList(List<Map> automaticPaymentNotificationInfoList) {
        final Integer numberOfThreads = 24
        final Integer flushEvery = 50

        Utils.processWithThreads(automaticPaymentNotificationInfoList, numberOfThreads, { List<Map> automaticPaymentNotificationInfoPartialList ->
            List<Long> processedNotificationIdList = []
            Utils.forEachWithFlushSession(automaticPaymentNotificationInfoPartialList, flushEvery, { Map automaticPaymentNotificationInfo ->
                Utils.withNewTransactionAndRollbackOnError({
                    processNotification(automaticPaymentNotificationInfo."notification.id")
                    processedNotificationIdList.add(automaticPaymentNotificationInfo.id)
                }, [logErrorMessage: "processNotificationAsyncActionList >> Ocorreu um erro ao processar a notificação [ID: ${automaticPaymentNotificationInfo."notification.id"}]",
                    onError: { automaticPaymentNotificationService.setAsErrorWithNewTransaction(automaticPaymentNotificationInfo.id) }
                ])
            })

            if (!processedNotificationIdList) return

            Utils.withNewTransactionAndRollbackOnError ({
                automaticPaymentNotificationService.deleteAll(processedNotificationIdList)
            }, [logErrorMessage: "processNotificationAsyncActionList >> Ocorreu um erro ao deletar as notificações processadas: [${ processedNotificationIdList }]"])
        })
    }

	private void processNotification(Long id) {
		Notification notification = Notification.read(id)

		if (notification.customerAccount.provider.notificationDisabled()) return

		if (notification.trigger.event == NotificationEvent.CUSTOMER_PAYMENT_OVERDUE && CustomerParameter.getValue(notification.customerAccount.provider, CustomerParameterName.DISABLE_PAYMENT_AFTER_DUE_DATE)) return

		if (notification.trigger.event == NotificationEvent.PAYMENT_DUEDATE_WARNING) {
			processPaymentDuedateWarningNotification(notification)
		} else if (notification.trigger.event == NotificationEvent.CUSTOMER_PAYMENT_OVERDUE && notification.trigger.schedule == NotificationSchedule.AFTER) {
			processOverduePaymentAfterNotification(notification)
		} else if (notification.trigger.event == NotificationEvent.SEND_LINHA_DIGITAVEL) {
			processLinhaDigitavelNotification(notification)
		}
	}

	private void processPaymentDuedateWarningNotification(Notification notification) {
		Calendar dueDate = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime())
		dueDate.add(Calendar.DAY_OF_MONTH, notification.trigger.scheduleOffset)

		List<Payment> payments = Payment.executeQuery(PAYMENTS_QUERY, [customerAccount: notification.customerAccount, dueDate: dueDate.getTime(), status: PaymentStatus.PENDING])

		Calendar notificationDate = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime())
		notificationDate.add(Calendar.DAY_OF_MONTH, 1)

		while (Holiday.isHoliday(notificationDate.getTime())) {
			dueDate.add(Calendar.DAY_OF_MONTH, 1)
			payments.addAll(Payment.executeQuery(PAYMENTS_QUERY, [customerAccount: notification.customerAccount, dueDate: dueDate.getTime(), status: PaymentStatus.PENDING]))
			notificationDate.add(Calendar.DAY_OF_MONTH, 1)
		}

		payments.removeAll{ it.isCreditCard() && it.billingInfo }
		payments.removeAll{ it.installment && it.isCreditCard() && it.installmentNumber > 1 }

		if (payments.size() == 0) return

        NotificationPriority notificationPriority = notification.trigger.schedule.isImmediately() ? NotificationPriority.MEDIUM : NotificationPriority.LOW
        processPayments(notification, payments, notificationPriority)
	}

    private void setCustomerDefaultRate(Long customerId) {
        Utils.withNewTransactionAndRollbackOnError({
            Customer customer = Customer.get(customerId)
            customer.highDefaultRate = customerService.hasHighDefaultRate(customer)
            AsaasLogger.info("Setting customer [${customer.id}] highDefaultRate >> [${customer.highDefaultRate}]")
            customer.save(failOnError: true)
            notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)
        }, [logErrorMessage: "AutomaticNotificationService.setCustomerDefaultRate >> Erro inesperado"])
    }

    private void processLinhaDigitavelNotification(Notification notification) {
        Date dueDate = new Date().clearTime()

        if (!notification.customerAccount.cpfCnpj) return

        List<Payment> payments = Payment.executeQuery(
            NOT_VIEWED_BOLETOS_QUERY, [
                customerAccount: notification.customerAccount,
                dueDate: dueDate,
                status: PaymentStatus.PENDING,
                billingTypeList: [BillingType.BOLETO, BillingType.UNDEFINED]
            ], [readOnly: true])

        processPayments(notification, payments, NotificationPriority.MEDIUM)
    }

    private void processPayments(Notification notification, List<Payment> payments, NotificationPriority notificationPriority) {
        if (!payments) return
        for (Payment payment : payments) {
            Boolean alreadyExists = NotificationRequest.query([
                exists: true,
                payment: payment,
                notification: notification,
                "dateCreated[ge]": new Date().clearTime(),
                manual: false
            ]).get().asBoolean()

            if (alreadyExists) continue

            notificationRequestService.save(notification, payment, notificationPriority, [manual: false, bypassCustomerAccountMigrationDeadline: true])
        }
    }

    private List<Long> getPaymentDueDateWarningNotificationIdList(Long notificationTriggerId) {
        NotificationTrigger notificationTrigger = NotificationTrigger.get(notificationTriggerId)

        Date dueDate = CustomDateUtils.sumDays(new Date().clearTime(), notificationTrigger.scheduleOffset)
        Map queryParams = [triggerId: notificationTriggerId, dueDate: dueDate]
        List<Long> notificationIdList = Notification.executeQuery(NotificationQueryBuilder.buildPaymentDueDateWarningIdList(), queryParams)

        Date notificationDate = new Date().clearTime()
        notificationDate = CustomDateUtils.sumDays(notificationDate, 1)

        while (Holiday.isHoliday(notificationDate)) {
            dueDate = CustomDateUtils.sumDays(notificationDate, notificationTrigger.scheduleOffset)
            queryParams = [triggerId: notificationTriggerId, dueDate: dueDate]
            notificationIdList.addAll(Notification.executeQuery(NotificationQueryBuilder.buildPaymentDueDateWarningIdList(), queryParams))

            notificationDate = CustomDateUtils.sumDays(notificationDate, 1)
        }

        return notificationIdList.unique()
    }

    private List<Long> getPaymentOverdueNotificationIdList() {
        List<NotificationTrigger> notificationTriggerList = NotificationTrigger.query([
            event: NotificationEvent.CUSTOMER_PAYMENT_OVERDUE,
            schedule: NotificationSchedule.AFTER,
            "scheduleOffset[gt]": Notification.DAILY_SCHEDULE_OFFSET
        ]).list()

        List<Long> paymentOverdueNotificationIdList = []
        for (NotificationTrigger notificationTrigger : notificationTriggerList) {
            final Date today = new Date().clearTime()
            final List<Date> notificationDatesToSendToday = CustomDateUtils.listDateAndPreviousHolidays(today)

            Set<Date> paymentDueDatesSet = []
            for (Date notificationDate : notificationDatesToSendToday) {
                paymentDueDatesSet.addAll(listPaymentDueDatesToSearch(notificationDate, notificationTrigger.scheduleOffset))
            }

            List<Long> notificationIdList = Notification.executeQuery(NotificationQueryBuilder.buildNewPaymentOverdueIdList(), [triggerId: notificationTrigger.id, dueDateList: paymentDueDatesSet])
            if (!notificationIdList) continue

            paymentOverdueNotificationIdList.addAll(notificationIdList)
        }

        return paymentOverdueNotificationIdList
    }

    private List<Date> listPaymentDueDatesToSearch(Date notificationDate, Integer scheduleOffset) {
        List<Date> dueDateToSearchList = []

        for (Integer notificationCount : 1..Notification.MAX_NOTIFICATIONS_FOR_OVERDUE_PAYMENTS) {
            final Integer daysAgoToSearch = scheduleOffset * notificationCount
            final Date dueDateToSearch = CustomDateUtils.sumDays(notificationDate, daysAgoToSearch * -1)
            dueDateToSearchList.add(dueDateToSearch)
        }

        return dueDateToSearchList
    }
}
