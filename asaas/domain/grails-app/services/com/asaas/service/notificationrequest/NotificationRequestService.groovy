package com.asaas.service.notificationrequest

import com.asaas.billinginfo.BillingType
import com.asaas.cache.customer.CustomerNotificationConfigCacheVO
import com.asaas.customer.CustomerInfoFormatter
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.InvoiceCustomerInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.email.AsaasMailMessage
import com.asaas.domain.email.AsaasMailMessageAttachment
import com.asaas.domain.installment.Installment
import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.domain.notification.NotificationTemplate
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.domain.payment.Payment
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.transactionreceipt.TransactionReceipt
import com.asaas.email.asaasmailmessage.AsaasMailMessageVO
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.generatereceipt.PaymentGenerateReceiptUrl
import com.asaas.log.AsaasLogger
import com.asaas.notification.InstantTextMessageType
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationSchedule
import com.asaas.notification.NotificationStatus
import com.asaas.notification.NotificationTemplateSource
import com.asaas.notification.NotificationType
import com.asaas.notification.builder.InstantTextMessageBuilder
import com.asaas.notificationrequest.vo.NotificationRequestTemplateVO
import com.asaas.notificationrequest.worker.PendingNotificationRequestWorkerConfigVO
import com.asaas.notificationrequest.worker.PendingNotificationRequestWorkerItemVO
import com.asaas.payment.PaymentStatus
import com.asaas.payment.PaymentUtils
import com.asaas.redis.RedissonProxy
import com.asaas.transactionreceipt.TransactionReceiptType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.StringUtils as AsaasStringUtils
import com.asaas.utils.TemplateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.transaction.Transactional
import grails.util.Environment
import groovy.text.SimpleTemplateEngine
import org.apache.commons.lang.StringUtils
import org.hibernate.SQLQuery
import org.redisson.api.RBucket

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher

@Transactional
class NotificationRequestService {

    def asaasMailMessageService
    def boletoService
    def customerNotificationConfigCacheService
    def customerPlanService
    def featureFlagService
    def grailsApplication
    def groovyPageRenderer
    def instantTextMessageService
    def linkService
    def customerInvoiceConfigService
    def notificationDispatcherCustomerAccountService
    def notificationRequestParameterService
    def notificationRequestTemplateService
    def sessionFactory
    def smsMessageService
    def timelineEventService

	public List<NotificationRequest> saveWithoutNotification(CustomerAccount customerAccount, NotificationEvent event, Object entity, NotificationPriority priority, NotificationReceiver notificationReceiver) {
		if (!NotificationEvent.listToSendWithoutNotification().contains(event)) throw new RuntimeException("Evento não suportado!")
        if (!event.shouldIgnoreNotificationDisabled() && (notificationDisabledForCustomer(customerAccount.provider) || notificationDisabledForCustomerAccount(customerAccount))) return

		NotificationTrigger notificationTrigger = NotificationTrigger.query([event: event]).get()
		if (!notificationTrigger) return

		List<NotificationRequest> notificationRequestList = []

		if (event.isCreditCardExpired()) {
			NotificationEvent eventToSearch = NotificationEvent.PAYMENT_DUEDATE_WARNING
			String hql = "from Notification n where n.enabled = true and n.deleted = false and n.trigger.event = :event and n.customerAccount = :customerAccount and (n.emailEnabledForCustomer = true or n.smsEnabledForCustomer = true or n.whatsappEnabledForCustomer = true) order by n.trigger.scheduleOffset"

			Notification notification = Notification.executeQuery(hql, [event: eventToSearch, customerAccount: customerAccount])[0]
			if (!notification) return

            notificationRequestList = save(notification, entity, priority, [manual: false, ignoreWhatsapp: true])
		} else {
            if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.WHITE_LABEL)) return

            notificationRequestList.add(buildAndSaveNotificationRequest(null, notificationReceiver, NotificationType.EMAIL, entity, customerAccount, priority, [manual: false]))
        }

        if (notificationReceiver.isCustomer() && NotificationEvent.listSendSmsToCustomerAccountWithoutNotificationEvents().contains(event)) {
            notificationRequestList.add(buildAndSaveNotificationRequest(null, notificationReceiver, NotificationType.SMS, entity, customerAccount, priority, [manual: false]))
        }

		notificationRequestList.removeAll([null])

		for (NotificationRequest notificationRequest in notificationRequestList) {
			if (notificationRequest.receiver == NotificationReceiver.PROVIDER && notificationReceiver == NotificationReceiver.CUSTOMER) {
				notificationRequest.delete()
				continue
			}

			if (notificationRequest.receiver == NotificationReceiver.CUSTOMER && notificationReceiver == NotificationReceiver.PROVIDER) {
				notificationRequest.delete()
				continue
			}

			notificationRequest.notification = null
			notificationRequest.notificationTrigger = notificationTrigger
			notificationRequest.save(failOnError: true)
		}

		return notificationRequestList
	}

    public List<NotificationRequest> save(CustomerAccount customerAccount, NotificationEvent event, Object entity, NotificationPriority priority) {
        return save(customerAccount, event, entity, priority, [manual: false])
    }

	public List<NotificationRequest> save(CustomerAccount customerAccount, NotificationEvent event, Object entity, NotificationPriority priority, Map options) {
		List<NotificationRequest> notificationRequestList = []
		try {
			List<Notification> notifications = Notification.executeQuery("from Notification n where n.enabled = true and n.deleted = false and n.trigger.event = :event and n.customerAccount = :customerAccount and n.trigger.schedule = :schedule and (n.emailEnabledForCustomer = true or n.emailEnabledForProvider = true or n.smsEnabledForCustomer = true or n.smsEnabledForProvider = true or n.phoneCallEnabledForCustomer = true or n.whatsappEnabledForCustomer = true)", [event: event, customerAccount: customerAccount, schedule: NotificationSchedule.IMMEDIATELY])

			if (notifications.size() == 0) return

			for (Notification notification : notifications) {
				notificationRequestList.addAll(save(notification, entity, priority, options))
			}
		} catch (Exception e) {
            AsaasLogger.error("Erro ao salvar notification requests através do dados CustomerAccount ${customerAccount}, NotificationEvent ${event}, Entity ${entity}, NotificationPriority ${priority}", e)
        }

		return notificationRequestList
	}

	public List<NotificationRequest> save(Notification notification, Object entity, NotificationPriority priority, Map options) {
		List<NotificationRequest> notificationRequestList = []

        if (notificationDisabledForCustomer(notification.customerAccount.provider) || notificationDisabledForCustomerAccount(notification.customerAccount)) return notificationRequestList

		if (notification.isOverduePaymentNotification() && CustomerParameter.getValue(notification.customerAccount.provider, CustomerParameterName.DISABLE_OVERDUE_PAYMENT_NOTIFICATION)) return notificationRequestList

		if (notification.emailEnabledForCustomer) {
			NotificationRequest notificationRequest = buildAndSaveNotificationRequest(notification, NotificationReceiver.CUSTOMER, NotificationType.EMAIL, entity, notification.customerAccount, priority, options)
			if (notificationRequest) notificationRequestList.add(notificationRequest)
		}

		if (notification.smsEnabledForCustomer && !CustomerParameter.getValue(notification.customerAccount.provider, CustomerParameterName.DISABLE_ALL_SMS_NOTIFICATIONS)) {
			NotificationRequest notificationRequest = buildAndSaveNotificationRequest(notification, NotificationReceiver.CUSTOMER, NotificationType.SMS, entity, notification.customerAccount, priority, options)
			if (notificationRequest) notificationRequestList.add(notificationRequest)
		}

		if (notification.phoneCallEnabledForCustomer) {
			NotificationRequest notificationRequest = buildAndSaveNotificationRequest(notification, NotificationReceiver.CUSTOMER, NotificationType.PHONE_CALL, entity, notification.customerAccount, priority, options)
			if (notificationRequest) notificationRequestList.add(notificationRequest)
		}

        if (notification.whatsappEnabledForCustomer && !options.ignoreWhatsapp) {
            NotificationRequest notificationRequest = buildAndSaveNotificationRequest(notification, NotificationReceiver.CUSTOMER, NotificationType.WHATSAPP, entity, notification.customerAccount, priority, options)
            if (notificationRequest) notificationRequestList.add(notificationRequest)
        }

		if (notification.isOverduePaymentNotification() && CustomerParameter.getValue(notification.customerAccount.provider, CustomerParameterName.DISABLE_OVERDUE_PAYMENT_NOTIFICATION_PROVIDER)) return notificationRequestList

		if (entity.instanceOf(Payment) && notification.trigger?.event == NotificationEvent.CUSTOMER_PAYMENT_RECEIVED && entity.anticipated == true) return notificationRequestList

		if (notification.emailEnabledForProvider) {
			NotificationRequest notificationRequest = buildAndSaveNotificationRequest(notification, NotificationReceiver.PROVIDER, NotificationType.EMAIL, entity, notification.customerAccount, priority, options)
			if (notificationRequest) notificationRequestList.add(notificationRequest)
		}

		if (notification.smsEnabledForProvider && !smsNotificationsDisabledForProvider(notification)) {
			NotificationRequest notificationRequest = buildAndSaveNotificationRequest(notification, NotificationReceiver.PROVIDER, NotificationType.SMS, entity, notification.customerAccount, priority, options)
			if (notificationRequest) notificationRequestList.add(notificationRequest)
		}

		return notificationRequestList
	}

    public void processPendingNotificationRequestWithThreads(NotificationType notificationType, NotificationPriority notificationPriority, Integer maxItems, Integer maxItemsPerThread) {
        List<Long> pendingNotificationRequestIdList = getPendingNotificationRequestIdList(notificationType, notificationPriority, maxItems, [])
        ThreadUtils.processWithThreadsOnDemand(pendingNotificationRequestIdList, maxItemsPerThread, true, { List<Long> notificationRequestIdList ->
            try {
                processNotificationRequestList(notificationRequestIdList)
            } catch (Exception exception) {
                AsaasLogger.error("NotificationRequestService.processPendingNotificationRequestWithThreads >> Ocorreu um erro ao enviar notificações de [${ notificationType }] com prioridade [${ notificationPriority }]: [${ notificationRequestIdList }]", exception)
            }
        })
    }

    public AsaasError validateIfCanManuallyResendPaymentNotification(Payment payment) {
        if (payment.deleted) {
            return new AsaasError("payment.notification.cannotResendPaymentNotification")
        }

        Boolean isResendableNotification = PaymentStatus.getResendableNotificationStatusList().contains(payment.status)
        if (!isResendableNotification) {
            return new AsaasError("payment.notification.cannotResendPaymentNotification")
        }

        Boolean hasPendingManualNotification = NotificationRequest.query([
            exists: true,
            manual: true,
            payment: payment,
            statusList: [NotificationStatus.PENDING, NotificationStatus.PROCESSING]
        ]).get().asBoolean()

        if (hasPendingManualNotification) {
            return new AsaasError("payment.notification.waitSendNotification")
        }

        final Integer maxManualPaymentNotificationLimit = 100
        Integer manualPaymentNotificationCount = NotificationRequest.query([
            exists: true,
            payment: payment,
            manual: true,
            status: NotificationStatus.SENT
        ]).count()

        if (manualPaymentNotificationCount > maxManualPaymentNotificationLimit) {
            return new AsaasError("payment.notification.exceedsAttemptsToResendPaymentNotification")
        }
    }

    public void manuallyResendPaymentNotification(Payment payment) {
        AsaasError validatedResendNotification = validateIfCanManuallyResendPaymentNotification(payment)
        if (validatedResendNotification) {
            throw new BusinessException(validatedResendNotification.getMessage())
        }

        NotificationEvent notificationEvent = PaymentUtils.paymentDateHasBeenExceeded(payment)
            ? NotificationEvent.CUSTOMER_PAYMENT_OVERDUE
            : NotificationEvent.PAYMENT_DUEDATE_WARNING

        Notification notification = Notification.query([
            enabled: true,
            event: notificationEvent,
            customerAccount: payment.customerAccount,
            hasAnyNotificationEnabledForCustomerAccount: true
        ]).get()

        if (!notification) throw new BusinessException(Utils.getMessageProperty("notification.PaymentDueDateWarning.disabledForCustomerAccount"))

        save(notification, payment, NotificationPriority.HIGH, [manual: true])
    }

    public Map buildTemplateParametersMap(NotificationRequestTemplateVO notificationRequestTemplateVO) {
        NotificationRequest notificationRequest = notificationRequestTemplateVO.notificationRequest
        if (notificationRequest.type.isSms() && notificationRequestTemplateVO.notificationTemplate?.isCustomTemplate) {
            return notificationRequestParameterService.buildNotificationTemplatePropertiesMap(notificationRequest)
        }

        Map parameters = [:]

        Integer dueDateDays = notificationRequestParameterService.calculateAbsoluteDaysUntilDueDate(notificationRequest)
        CustomerAccount customerAccount = notificationRequest.customerAccount
        Customer provider = customerAccount.provider
        Payment payment = notificationRequest.payment
        Map linkParams = notificationRequestParameterService.buildInternalLinkParams(notificationRequest)

        parameters.put("customer", provider)
        parameters.put("fromName", buildFromName(provider))
        parameters.put("fromEmail", buildEmailFrom(provider))
        parameters.put("replyTo", CustomerInfoFormatter.formatEmail(provider))
        parameters.put("notificationRequestId", notificationRequest.id)
        parameters.put("days", dueDateDays)
        parameters.put("boletoURL", linkService.viewInvoice(payment, linkParams))
        parameters.put("boletoURLCustomer", linkService.viewInvoice(payment, linkParams))
        parameters.put("invoicePublicUrlShort", linkService.viewInvoiceShort(payment, linkParams))
        parameters.put("customerAccount", customerAccount)
        parameters.put("payment", payment)
        parameters.put("subscription", payment.getSubscription())
        parameters.put("notificationRequest", notificationRequest)
        parameters.put("providerName", CustomerInfoFormatter.formatName(provider))
        parameters.put("customerName", customerAccount.name)
        parameters.put("paymentDescription", payment.buildDescription())
        parameters.put("originalValueWithInterest", payment.getOriginalValueWithInterest())
        parameters.put("receivedValueIsDifferentThanExpected", payment.receivedValueIsDifferentFromExpected())
        parameters.put("event", notificationRequest.getEventTrigger())
        parameters.put("trigger", notificationRequest.getNotificationRequestTrigger())
        parameters.put("dueDate", payment.dueDate)
        parameters.put("dueDateReminderStart", CustomDateUtils.setTime(payment.dueDate, 10, 0, 0))
        parameters.put("dueDateReminderEnd", CustomDateUtils.setTime(payment.dueDate, 10, 15, 0))
        parameters.put("baseUrl", linkParams.baseUrl)

        if (payment.isCreditCard() && payment.installment) {
            parameters.put("totalValue", payment.installment.getValue())
            parameters.put("installmentCount", payment.installment.payments.size())
            parameters.put("installmentNumber", payment.installmentNumber)
            parameters.put("installmentValue", payment.value)
        } else {
            parameters.put("totalValue", payment.value)
        }

        if (payment.billingType == BillingType.BOLETO && notificationRequest.getEventTrigger() == NotificationEvent.SEND_LINHA_DIGITAVEL) {
            parameters.put("linhaDigitavel", boletoService.getLinhaDigitavel(payment))
        }

        if (notificationRequest.type == NotificationType.EMAIL) {
            def domainInstance = payment.isCreditCardInstallment() ? payment.installment : payment

            if (notificationRequest.getEventTrigger() == NotificationEvent.CUSTOMER_PAYMENT_RECEIVED) {
                String transactionPublicId = TransactionReceipt.query([domainInstance: domainInstance, column: "publicId", "type[in]": [TransactionReceiptType.PAYMENT_CONFIRMED, TransactionReceiptType.PAYMENT_RECEIVED]]).get()
                parameters.put("transactionReceiptPublicIdOfPaymentConfirmedOrReceived", transactionPublicId)
            } else if (notificationRequest.getEventTrigger() == NotificationEvent.PAYMENT_DELETED) {
                parameters.put("paymentDeletedTransactionReceiptUrl", new PaymentGenerateReceiptUrl(payment).generateAbsoluteUrl())
            } else if (notificationRequest.getEventTrigger() == NotificationEvent.PAYMENT_AWAITING_RISK_ANALYSIS) {
                if (payment.installment) {
                    parameters.put("sumValuePaymentsAwaitingRiskAnalysis", Payment.sumValue([status: PaymentStatus.AWAITING_RISK_ANALYSIS, installmentId: payment.installment.id]).get())
                } else {
                    parameters.put("sumValuePaymentsAwaitingRiskAnalysis", payment.value)
                }
            } else if (notificationRequest.getEventTrigger().isPaymentRefundRequested()) {
                Map refundRequest = RefundRequest.query([
                    columnList: ["publicId", "dateCreated"],
                    paymentId: payment.id
                ]).get()
                parameters.put("refundRequestPublicId", refundRequest.publicId)
                parameters.put("refundRequestExpirationDate", CustomDateUtils.sumDays(refundRequest.dateCreated, RefundRequest.DAYS_TO_EXPIRE))
            }

            parameters += notificationRequestParameterService.getLogoParameters(provider, linkParams.baseUrl)

            if (notificationRequestParameterService.getCustomerNotificationMessageType(notificationRequest).isPayment()) {
                parameters.put("viewInvoiceButtonUrl", linkService.buildImageButtonUrl("notification/visualizar_cobranca.png", linkParams))
            } else {
                parameters.put("viewInvoiceButtonUrl", linkService.buildImageButtonUrl("notification/realizar_contribuicao.png", linkParams))
                parameters.put("viewChangesButtonUrl", linkService.buildImageButtonUrl("notification/visualizar_alteracoes.png", linkParams))
            }
            parameters.put("viewBoletoButtonUrl", linkService.buildImageButtonUrl("notification/visualizar_boleto.png", linkParams))
            parameters.put("viewUpdatedBoletoButtonUrl", linkService.buildImageButtonUrl("notification/emitir_segunda_via.png", linkParams))
            parameters.put("updateCreditCardButtonUrl", linkService.buildImageButtonUrl("notification/atualizar_cartao.png", linkParams))

            CustomerInvoiceConfig customerInvoiceConfig = provider.getInvoiceConfig()
            parameters.put("invoicePrimaryColor", customerInvoiceConfig?.primaryColor ?: "#f1f1f4")
            parameters.put("invoiceSecondaryColor", customerInvoiceConfig?.secondaryColor ?: "#f1f1f4")
            parameters.put("invoiceInfoFontColor", customerInvoiceConfig?.customerInfoFontColor ?: "#000000")

            parameters.put("mailSubject", groovyPageRenderer.render(template: "${ buildNotificationTemplatePath(notificationRequest) }/emailSubject", model: parameters).decodeHTML())
            parameters.put("mailTitle", notificationRequestParameterService.getNotificationTitle(notificationRequest, parameters))

            if (!notificationRequest.receiver.isProvider()) {
                parameters += notificationRequestParameterService.getCustomerNotificationColours(provider)
                parameters.put("truncatedPaymentDescription", AsaasStringUtils.truncateAndAddEllipsisIfNecessary(payment.buildDescription(), 180))
                parameters.put("callToActionLabel", notificationRequestParameterService.getNotificationCallToActionButtonLabel(notificationRequest))
            }

            parameters.put("boxContentLabel", "font-weight: bold;")
            parameters.put("boxContent_p", "margin: 0; margin-bottom: 24px;")
            parameters.put("boxContent_p_span", "font-weight: bold;")
            parameters.put("boxContent_p_a", "text-decoration: underline; color: #1DA9DA;")
        } else if (notificationRequest.type.isWhatsApp()) {
            parameters.put("customerPhoneNumberIsToBeShowed", customerInvoiceConfigService.customerInfoIsToBeShowed(provider, InvoiceCustomerInfo.PHONE_NUMBERS))
            parameters.put("customerEmailIsToBeShowed", customerInvoiceConfigService.customerInfoIsToBeShowed(provider, InvoiceCustomerInfo.EMAIL))
        }

        return parameters
    }

    public List<PendingNotificationRequestWorkerItemVO> listPendingNotificationRequestIdListWithWorker(PendingNotificationRequestWorkerConfigVO notificationRequestWorkerConfigVO, Integer pendingItemsLimit, List<Long> idsOnProcessing) {
        List<Long> pendingNotificationRequestIdList = getPendingNotificationRequestIdList(notificationRequestWorkerConfigVO.notificationType, notificationRequestWorkerConfigVO.notificationPriority, pendingItemsLimit, idsOnProcessing)

        List<PendingNotificationRequestWorkerItemVO> pendingNotificationRequestWorkerItemVOList = pendingNotificationRequestIdList.collate(notificationRequestWorkerConfigVO.maxItemsPerThread).collect { new PendingNotificationRequestWorkerItemVO(it) }

        return pendingNotificationRequestWorkerItemVOList
    }

    private Boolean notificationDisabledForCustomer(Customer customer) {
        return customer.notificationDisabled() || customer.customerConfig.notificationDisabled
    }

    private Boolean notificationDisabledForCustomerAccount(CustomerAccount customerAccount) {
        return customerAccount.notificationDisabled
    }

    private Boolean smsNotificationsDisabledForProvider (Notification notification) {
        return (CustomerParameter.getValue(notification.customerAccount.provider, CustomerParameterName.DISABLE_NOTIFICATION_SMS_PROVIDER) || CustomerParameter.getValue(notification.customerAccount.provider, CustomerParameterName.DISABLE_ALL_SMS_NOTIFICATIONS))
    }

    private Boolean canSendPaymentManualSmsNotification(Payment payment) {
        if (payment.provider.id == 369060L) return false
        if (!payment.customerAccount.mobilePhone) return false

        NotificationEvent notificationEvent = PaymentUtils.paymentDateHasBeenExceeded(payment)
            ? NotificationEvent.CUSTOMER_PAYMENT_OVERDUE
            : NotificationEvent.PAYMENT_DUEDATE_WARNING

        String sanitizedPhone = PhoneNumberUtils.sanitizeNumber(
            PhoneNumberUtils.removeBrazilAreaCode(payment.customerAccount.mobilePhone)
        )

        Boolean hasManualSmsNotification = NotificationRequest.query([
            exists: true,
            event: notificationEvent,
            type: NotificationType.SMS,
            payment: payment,
            manual: true,
            phoneNumber: sanitizedPhone
        ]).get().asBoolean()
        if (hasManualSmsNotification) return false

        return true
    }

    private NotificationRequest buildAndSaveNotificationRequest(Notification notification, NotificationReceiver receiver, NotificationType type, Object entity, CustomerAccount customerAccount, NotificationPriority priority, Map options) {
        if (!validateCustomerAndProviderNotificationInfo(customerAccount, receiver, type)) return

        if (notification && NotificationRequest.existsPendingOrProcessing(notification, receiver, type, (Payment) entity)) return

        if (options.manual && type.isSms() && entity instanceof Payment) {
            if (!canSendPaymentManualSmsNotification(entity as Payment)) return
        }

        if (!priority) priority = NotificationPriority.LOW

        NotificationRequest notificationRequest = new NotificationRequest()
        notificationRequest.notification = notification
        notificationRequest.customerAccount = customerAccount
        notificationRequest.receiver = receiver
        notificationRequest.type = type
        notificationRequest.priority = priority.priorityInt
        notificationRequest.manual = options.manual

        String phoneNumber
        if ([NotificationType.SMS, NotificationType.WHATSAPP].contains(type)) {
            phoneNumber = customerAccount.mobilePhone
        } else if (type == NotificationType.PHONE_CALL) {
            phoneNumber = customerAccount.mobilePhone ?: customerAccount.phone
        }

        if (phoneNumber) notificationRequest.phoneNumber = PhoneNumberUtils.sanitizeNumber(PhoneNumberUtils.removeBrazilAreaCode(phoneNumber))

        setNotificationRequestEntity(notificationRequest, entity)

        Date scheduledDate = options.scheduledDate

        if (!priority.isHigh() && !scheduledDate) scheduledDate = calculateScheduledDate(notificationRequest)

        if (scheduledDate && scheduledDate > new Date()) {
            notificationRequest.scheduledDate = scheduledDate
            if (scheduledDate > new Date()) notificationRequest.status = NotificationStatus.SCHEDULED
        }

        setAsExternalProcessingIfNecessary(notificationRequest, options.bypassCustomerAccountMigrationDeadline)

        notificationRequest.save(failOnError: true)

        return notificationRequest
	}

    private void setAsExternalProcessingIfNecessary(NotificationRequest notificationRequest, Boolean bypassCustomerAccountMigrationDeadline) {
        if (!notificationRequest.type.isEmail()) return
        if (!featureFlagService.isNotificationRequestExternalProcessingEnabled()) return
        if (!notificationDispatcherCustomerAccountService.shouldProcessExternally(notificationRequest.customerAccount.id, bypassCustomerAccountMigrationDeadline)) return

        notificationRequest.deleted = true
        notificationRequest.status = NotificationStatus.EXTERNAL
    }

	private Boolean validateCustomerAndProviderNotificationInfo(CustomerAccount customerAccount, NotificationReceiver receiver, NotificationType type) {
		if (receiver.equals(NotificationReceiver.PROVIDER) && type.equals(NotificationType.EMAIL) && StringUtils.isBlank(customerAccount.provider.email)) return false

		if (receiver.equals(NotificationReceiver.PROVIDER) && type.equals(NotificationType.SMS) && StringUtils.isBlank(customerAccount.provider.mobilePhone)) return false

		if (receiver.equals(NotificationReceiver.CUSTOMER) && type.equals(NotificationType.EMAIL) && StringUtils.isBlank(customerAccount.email)) return false

		if (receiver.equals(NotificationReceiver.CUSTOMER) && type.equals(NotificationType.SMS) && StringUtils.isBlank(customerAccount.mobilePhone)) return false

		if (receiver.equals(NotificationReceiver.CUSTOMER) && type.equals(NotificationType.PHONE_CALL) && (StringUtils.isBlank(customerAccount.mobilePhone) && StringUtils.isBlank(customerAccount.phone))) return false

        if (receiver.equals(NotificationReceiver.CUSTOMER) && type.equals(NotificationType.WHATSAPP) && StringUtils.isBlank(customerAccount.mobilePhone)) return false

		return true
	}

	private void setNotificationRequestEntity(NotificationRequest notificationRequest, Object entity) {
		if (entity == null) return

		if (entity.instanceOf(Payment)) {
			notificationRequest.payment = (Payment) entity
		} else if (entity.instanceOf(CustomerAccount)) {
			notificationRequest.customerAccount = (CustomerAccount) entity
		} else if (entity.instanceOf(Subscription)) {
			notificationRequest.subscription = (Subscription) entity
		}
	}

    public void processNotificationRequestList(List<Long> pendingRequestList) {
        for (Long notificationRequestId in pendingRequestList) {
            processNotificationRequest(notificationRequestId)
        }
    }

    private void processNotificationRequest(Long notificationRequestId) {
        try {
            Utils.withNewTransactionAndRollbackOnError ({
                NotificationRequest notificationRequest = NotificationRequest.get(notificationRequestId)

                if (!isNotificationRequestReceiverValid(notificationRequest)) {
                    notificationRequest.status = NotificationStatus.FAILED
                    notificationRequest.save(failOnError: true)
                    return
                }

                Boolean isOverdueEvent = notificationRequest.notification?.trigger?.event?.isCustomerPaymentOverdue()
                Boolean isOverdueEventPaymentAlreadyConfirmed = isOverdueEvent && PaymentStatus.hasAlreadyPaidStatusList().contains(notificationRequest.payment.status)

                if (isOverdueEventPaymentAlreadyConfirmed || alreadyUsedNotificationLimit(notificationRequest)) {
                    notificationRequest.status = NotificationStatus.CANCELLED
                    notificationRequest.save(failOnError: true)
                    return
                }

                NotificationRequestTemplateVO notificationRequestTemplateVO = new NotificationRequestTemplateVO(notificationRequest)
                if (notificationRequest.type.isEmail() || notificationRequest.type.isSms()) {
                    findAndSetTemplate(notificationRequestTemplateVO)
                }

                notificationRequestTemplateVO.notificationTemplateDataMap = buildTemplateParametersMap(notificationRequestTemplateVO)

                if (notificationRequest.type.isEmail()) {
                    addEmailOnSendQueue(notificationRequestTemplateVO)
                } else if (notificationRequest.type.isSms()) {
                    addSmsOnSendQueue(notificationRequestTemplateVO)
                } else if (notificationRequest.type.isWhatsApp()) {
                    addWhatsappOnSendQueue(notificationRequestTemplateVO)
                }

                notificationRequest.status = NotificationStatus.PROCESSING
                notificationRequest.attemptsToSend++
                notificationRequest.save(failOnError: true)

                notificationRequestTemplateService.saveIfNecessary(notificationRequestTemplateVO)
            }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
        } catch (Exception exception) {
            AsaasLogger.error("NotificationRequestService.processNotificationRequest >> Erro ao processar NotificationRequest [${ notificationRequestId }]", exception)
            notificationRequestProcessFailHandler(notificationRequestId)
        }
    }

	private Boolean alreadyUsedNotificationLimit(NotificationRequest notificationRequest) {
		Map params = [:]
		params.status = [NotificationStatus.SCHEDULED, NotificationStatus.SENT]
		params.type = notificationRequest.type
		params.customer = notificationRequest.customerAccount.provider

		Integer dailyLimit = NotificationRequest.getDailyLimitByType(notificationRequest.type)

		if (dailyLimit) {
			Integer dailySentNotifications = NotificationRequest.query(params + ['dateCreated[ge]': new Date().clearTime()]).count()
			if (dailySentNotifications >= dailyLimit) return true
		}

		Integer weeklyLimit = NotificationRequest.getWeeklyLimitByType(notificationRequest.type)

		if (weeklyLimit) {
			Integer weeklySentNotifications = NotificationRequest.query(params + ['dateCreated[ge]': CustomDateUtils.sumDays(new Date(), -7)]).count()
			if (weeklySentNotifications >= weeklyLimit) return true
		}

		return false
	}

    private NotificationTemplate findCustomDatabaseTemplateIfExists(NotificationRequest notificationRequest) {
        Customer customer = notificationRequest.customerAccount.provider
        NotificationTrigger notificationTrigger = notificationRequest.getNotificationRequestTrigger()

        Boolean customerHasNotificationConfigEnabled = CustomerNotificationConfig.findIfEnabled(customer.id, [exists: true]).get().asBoolean()
        if (!customerHasNotificationConfigEnabled) return null

        if (customerPlanService.isCustomNotificationTemplatesEnabled(customer)) {
            NotificationTemplate notificationTemplate = NotificationTemplate.query([
                providerId: customer.id,
                receiver: notificationRequest.receiver,
                type: notificationRequest.type,
                event: notificationTrigger.event,
                schedule: notificationTrigger.schedule,
                isCustomTemplate: true
            ]).get()

            if (notificationTemplate) return notificationTemplate
        }

        return NotificationTemplate.query([
            providerId: customer.id,
            receiver: notificationRequest.receiver,
            type: notificationRequest.type,
            event: notificationTrigger.event,
            schedule: notificationTrigger.schedule,
            isCustomTemplate: false
        ]).get()
    }

    private String buildNotificationTemplatePath(NotificationRequest notificationRequest) {
        String messageType = notificationRequestParameterService.getCustomerNotificationMessageType(notificationRequest).toString().toLowerCase()
        String messageReceiver = notificationRequest.receiver.toString().toLowerCase()
        String templatePath = "/notificationTemplates/${ messageType }/${ messageReceiver }"

        if (!notificationRequest.type.isEmail()) return templatePath

        NotificationEvent event = notificationRequest.getEventTrigger()

        if (!notificationRequest.receiver.isProvider()) {
            return templatePath + "/${ AsaasStringUtils.convertSnakeToCamelCase(event.toString()) }"
        }

        if (event.isCustomerPaymentReceived()) return templatePath + "/paymentReceived"
        if (event.isPaymentRefunded()) return templatePath + "/paymentRefunded"
        if (event.isPaymentDeleted()) return templatePath + "/paymentDeleted"

        return templatePath
    }

    private void findAndSetTemplate(NotificationRequestTemplateVO notificationRequestTemplateVO) {
        notificationRequestTemplateVO.notificationTemplate = findCustomDatabaseTemplateIfExists(notificationRequestTemplateVO.notificationRequest)
        if (notificationRequestTemplateVO.notificationTemplate) {
            notificationRequestTemplateVO.notificationTemplateSource = NotificationTemplateSource.NOTIFICATION_TEMPLATE
            return
        }

        notificationRequestTemplateVO.notificationTemplateSource = NotificationTemplateSource.GSP
    }

    private void addEmailOnSendQueue(NotificationRequestTemplateVO notificationRequestTemplateVO) {
        NotificationRequest notificationRequest = notificationRequestTemplateVO.notificationRequest
        NotificationTemplate notificationTemplate = notificationRequestTemplateVO.notificationTemplate
        Map dataMap = notificationRequestTemplateVO.notificationTemplateDataMap

        String mailSubject
        String mailBody
        if (notificationRequestTemplateVO.notificationTemplateSource.isGsp()) {
            mailBody = groovyPageRenderer.render([
                template: buildNotificationTemplatePath(notificationRequest) + "/emailBody",
                model: dataMap
            ]).decodeHTML()

            mailSubject = dataMap.mailSubject
        } else if (notificationTemplate?.isCustomTemplate) {
            mailBody = ""
            mailSubject = ""
        } else {
            mailBody = TemplateUtils.buildHtmlTemplateWithoutWebRequest(
                notificationTemplate?.body,
                "sampleBody${notificationRequest.id}",
                dataMap
            )

            mailSubject = TemplateUtils.buildHtmlTemplateWithoutWebRequest(
                notificationTemplate?.subject,
                "sampleSubject${notificationRequest.id}",
                dataMap
            )
        }

        mailSubject = truncateEmailSubjectIfNecessary(dataMap, mailSubject)

        AsaasMailMessageVO asaasMailMessageVO = new AsaasMailMessageVO()

        asaasMailMessageVO.replyTo = dataMap["replyTo"]
        asaasMailMessageVO.from = dataMap["fromEmail"]
        asaasMailMessageVO.fromName = dataMap["fromName"]
        asaasMailMessageVO.subject = mailSubject
        asaasMailMessageVO.text = mailBody
        asaasMailMessageVO.isHtml = true
        asaasMailMessageVO.ccList = null
        asaasMailMessageVO.bccList = null
        asaasMailMessageVO.notificationRequest = notificationRequest
        asaasMailMessageVO.attachmentList = createMailAttachments(notificationRequest, dataMap)
        asaasMailMessageVO.externalTemplate = notificationTemplate?.externalTemplate
        asaasMailMessageVO.notificationTemplate = notificationTemplate

        List<String> toAddressList = getReceiverEmailList(notificationRequest)
        for (String toAddress : toAddressList) {
            try {
                if (StringUtils.isBlank(toAddress)) continue

                NotificationRequestTo notificationRequestTo = new NotificationRequestTo(
                    email: toAddress,
                    notificationRequest: notificationRequest
                ).save(failOnError: true)

                notificationRequest.addToNotificationRequestToList(notificationRequestTo)

                asaasMailMessageVO.notificationRequestTo = notificationRequestTo
                asaasMailMessageVO.to = toAddress

                asaasMailMessageService.save(asaasMailMessageVO)
            } catch (Exception exception) {
                AsaasLogger.error("NotificationRequestService.addEmailOnSendQueue >> Erro ao criar AsaasMailMessage a partir de NotificationRequest [${ notificationRequest.id }]", exception)
                throw exception
            }
        }
    }

    private String truncateEmailSubjectIfNecessary(Map dataMap, String mailSubject) {
        if (!mailSubject) return mailSubject
        mailSubject = mailSubject.trim()

        if (mailSubject.size() <= AsaasMailMessage.MAX_SUBJECT_SIZE) return mailSubject

        Integer minimumNameLength = 50
        Integer overflowLength = mailSubject.size() - AsaasMailMessage.MAX_SUBJECT_SIZE

        for (String name : [dataMap.customerName, dataMap.providerName]) {
            if (!mailSubject.contains(name)) continue

            Integer newNameSize = name.size() - overflowLength
            if (newNameSize < minimumNameLength) {
                throw new RuntimeException("O nome não pode ter menos que ${ minimumNameLength } caracteres")
            }

            String truncatedName = AsaasStringUtils.truncateAndAddEllipsisIfNecessary(name, newNameSize)
            return mailSubject.replace(name, truncatedName)
        }

        return AsaasStringUtils.truncateAndAddEllipsisIfNecessary(mailSubject, AsaasMailMessage.MAX_SUBJECT_SIZE)
    }

	private List<AsaasMailMessageAttachment> createMailAttachments(NotificationRequest notificationRequest, Map dataMap) {
		List<AsaasMailMessageAttachment> attachments = []

		if (CustomerParameter.getValue(notificationRequest.customerAccount.provider, CustomerParameterName.DISABLE_ICS_ATTACHMENT)) return attachments

		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return attachments

		NotificationEvent event = notificationRequest.getEventTrigger()

		if (NotificationEvent.listToSendIcs().contains(event) && notificationRequest.receiver == NotificationReceiver.CUSTOMER) {
			AsaasMailMessageAttachment attachment = new AsaasMailMessageAttachment()
			attachment.content = groovyPageRenderer.render(template: "/notificationTemplates/payment/customer/ics/${event.toString().toLowerCase()}", model: dataMap).toString().getBytes('UTF-8')
			attachment.name = "pagamento.ics"
			attachments.add(attachment)
		}

		return attachments
	}

	private void addSmsOnSendQueue(NotificationRequestTemplateVO notificationRequestTemplateVO) throws Exception {
        NotificationRequest notificationRequest = notificationRequestTemplateVO.notificationRequest
        String messageText = buildSmsMessageText(notificationRequestTemplateVO)

        String phoneNumber = notificationRequest.receiver == NotificationReceiver.PROVIDER
            ? notificationRequest.customerAccount.provider.mobilePhone
            : notificationRequest.customerAccount.mobilePhone

        smsMessageService.save(messageText.decodeHTML(), notificationRequest.getSmsFrom(), phoneNumber, notificationRequest)
    }

    public Date calculateScheduledDate(NotificationRequest notificationRequest) {
        if (notificationRequest.priority == NotificationPriority.HIGH.priorityInt()) return null

        Date scheduledDate
        if (notificationRequest.scheduledDate) {
            scheduledDate = notificationRequest.scheduledDate
        } else {
            scheduledDate = new Date().clearTime()
        }

        if ([NotificationType.WHATSAPP, NotificationType.SMS].contains(notificationRequest.type)) {
            scheduledDate = CustomDateUtils.setTime(scheduledDate, NotificationRequest.MOBILE_PHONE_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)
            scheduledDate = adjustScheduledDateByPhoneRegion(notificationRequest, scheduledDate)
        } else {
            scheduledDate = calculateEmailNotificationScheduledHour(notificationRequest.customerAccount.provider, scheduledDate)
        }

        if (!shouldApplyCalculatedScheduledDate(scheduledDate, notificationRequest.type)) return null

        return scheduledDate
    }

    private Boolean shouldApplyCalculatedScheduledDate(Date scheduleDate, NotificationType notificationType) {
        final Date today = new Date().clearTime()

        if (AsaasEnvironment.isSandbox()) return true
        if (scheduleDate.clone().clearTime() > today) return true

        Date notificationWithoutHighPriorityStartingTime
        if (notificationType.isEmail()) {
            notificationWithoutHighPriorityStartingTime = CustomDateUtils.setTime(today, NotificationRequest.EMAIL_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)
        } else {
            notificationWithoutHighPriorityStartingTime = CustomDateUtils.setTime(today, NotificationRequest.MOBILE_PHONE_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)
        }

        return scheduleDate > notificationWithoutHighPriorityStartingTime
    }

    private Date adjustScheduledDateByPhoneRegion(NotificationRequest notificationRequest, Date scheduledDate) {
        String phone = notificationRequest.receiver.equals(NotificationReceiver.PROVIDER) ? notificationRequest.customerAccount.provider.mobilePhone : notificationRequest.customerAccount.mobilePhone
        String ddd = Utils.retrieveDDD(phone)

        if (grailsApplication.config.asaas.summerTime.timeZoneSubtract[ddd]) {
            scheduledDate = CustomDateUtils.sumHours(scheduledDate, grailsApplication.config.asaas.summerTime.timeZoneSubtract[ddd].intdiv(60))
        }

        if (CustomDateUtils.isSummerTime() && !grailsApplication.config.asaas.summerTime.timeZoneWithSummerTime.contains(Integer.parseInt(ddd))) {
            scheduledDate = CustomDateUtils.sumHours(scheduledDate, 1)
        }

        return scheduledDate
    }

    private String buildSmsMessageText(NotificationRequestTemplateVO notificationRequestTemplateVO) {
        NotificationRequest notificationRequest = notificationRequestTemplateVO.notificationRequest
        NotificationTemplate notificationTemplate = notificationRequestTemplateVO.notificationTemplate
        Map dataMap = notificationRequestTemplateVO.notificationTemplateDataMap

        final Integer minimumNameLength = 10
        final Integer maximumSmsLength = 155
        final Integer smsFromLength = notificationRequest.getSmsFrom().length() + 2

        String messageText
        if (notificationTemplate?.isCustomTemplate) {
            String unsafeSmsMessage = CustomNotificationTemplate.removeRenderPrevention(notificationTemplate.message)

            for (Map.Entry entry : dataMap) {
                unsafeSmsMessage = unsafeSmsMessage.replaceAll(entry.key, Matcher.quoteReplacement(entry.value))
            }

            messageText = unsafeSmsMessage
        } else if (notificationRequestTemplateVO.notificationTemplateSource.isGsp()) {
            messageText = groovyPageRenderer.render([
                template: buildNotificationTemplatePath(notificationRequest) + "/sms",
                model: dataMap
            ])
        } else {
            messageText = TemplateUtils.buildHtmlTemplateWithoutWebRequest(
                notificationTemplate?.message,
                "sampleMessage${notificationRequest.id}",
                dataMap
            )
        }

        messageText = messageText.toString().trim()

        if ((messageText.length() + smsFromLength) > maximumSmsLength) {
            String customerName = notificationRequestTemplateVO.getDataMapCustomerName()
            String customerAccountName = notificationRequestTemplateVO.getDataMapCustomerAccountName()

            if (customerName.length() <= minimumNameLength && customerAccountName.length() <= minimumNameLength) {
                String truncatedMessageText = messageText.substring(0, (maximumSmsLength - smsFromLength))
                AsaasLogger.warn("NotificationRequestService.buildSmsMessageText >> A notificação de SMS NotificationRequestId [${notificationRequest.id}] excedeu o limite caracteres e será truncada! [${truncatedMessageText}]")
                return truncatedMessageText
            }

            Integer charactersToReduce = 2
            if (customerName.length() > customerAccountName.length()) {
                String newCustomerName = customerName.substring(0, customerName.length() - charactersToReduce)
                notificationRequestTemplateVO.setDataMapCustomerName(newCustomerName)
            } else {
                String newCustomerAccountName = customerAccountName.substring(0, customerAccountName.length() - charactersToReduce)
                notificationRequestTemplateVO.setDataMapCustomerAccountName(newCustomerAccountName)
            }

            return buildSmsMessageText(notificationRequestTemplateVO)
		}

		return messageText
	}

    private List<Long> getPendingNotificationRequestIdList(NotificationType notificationType, NotificationPriority notificationPriority, Integer limit, List<Long> onProcessingIdList) {
        String fieldName = AsaasStringUtils.convertSnakeToCamelCase("${ notificationType }_${ notificationPriority }").capitalize()
        final String cacheName = "NotificationRequest:last${ fieldName }IdProcessed"

        RBucket<Long> lastIdProcessedBucket = RedissonProxy.instance.getBucket(cacheName, Long)
        Long lastIdProcessed = lastIdProcessedBucket?.get()?.toLong()

        StringBuilder builder = new StringBuilder()
        builder.append(" SELECT id ")
        builder.append(" FROM notification_request n FORCE INDEX(notification_request_status_type_idx) ")
        builder.append(" WHERE n.type = :type ")
        builder.append(" AND n.status = :status ")
        builder.append(" AND n.priority = :priority ")
        if (onProcessingIdList) builder.append(" AND n.id not in (:inProcessingId) ")
        if (lastIdProcessed) builder.append(" AND n.id > ${ lastIdProcessed } ")
        builder.append(" ORDER BY n.id ASC ")
        builder.append(" LIMIT :limit ")

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(builder.toString())
        sqlQuery.setString("type", notificationType.toString())
        sqlQuery.setString("status", NotificationStatus.PENDING.toString())
        sqlQuery.setInteger("priority", notificationPriority.priorityInt())
        if (onProcessingIdList) sqlQuery.setParameterList("inProcessingId", onProcessingIdList)
        sqlQuery.setLong("limit", limit)

        List<Long> pendingNotificationRequestIdList = sqlQuery.list().collect { Utils.toLong(it) }

        if (lastIdProcessedBucket && pendingNotificationRequestIdList && !lastIdProcessed) {
            final Integer ttlCacheInMinutes = 10
            lastIdProcessedBucket.set(pendingNotificationRequestIdList.last(), ttlCacheInMinutes, TimeUnit.MINUTES)
        }

        return pendingNotificationRequestIdList
    }

    public void processScheduledNotification(NotificationType notificationType) {
        String sql = "SELECT id FROM notification_request n FORCE INDEX(notification_request_status_type_idx) WHERE n.status = :status AND n.scheduled_date <= :scheduledDate AND n.type = :type LIMIT :limit"
        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql)
        query.setString("status", NotificationStatus.SCHEDULED.toString())
        query.setTimestamp("scheduledDate", new Date())
        query.setString("type", notificationType.toString())
        query.setLong("limit", 2000)

        List<Long> notificationRequestIdList = query.list().collect { Utils.toLong(it) }

        if (!notificationRequestIdList) return

        String notificationTypeName = notificationType.toString()
            Notification.withNewTransaction { status ->
            try {
                AsaasLogger.info("Updating ${notificationRequestIdList.size()} ${notificationTypeName} notifications from SCHEDULED to PENDING.")
                NotificationRequest.executeUpdate("UPDATE NotificationRequest SET version = version + 1, status = :pending, lastUpdated = :now WHERE id IN (:idList)", [pending: NotificationStatus.PENDING, idList: notificationRequestIdList, now: new Date()])
            } catch (Exception exception) {
                status.setRollbackOnly()
                AsaasLogger.error("NotificationRequestService.processScheduledNotification >> Erro ao atualizar notificacoes de ${notificationTypeName} agendadas para pendentes", exception)
            }
        }
    }

    public void cancelUnsentNotifications(Payment payment, Installment installment) {
        if (installment) {
            for (Payment installmentPayment in installment.getNotDeletedPayments()) {
                cancelPaymentUnsentNotifications(installmentPayment)
            }
        } else {
            cancelPaymentUnsentNotifications(payment)
        }
    }

    public void cancelPaymentUnsentNotifications(Payment payment) {
        List<Long> notificationRequestIdList = NotificationRequest.query([column: "id", payment: payment, statusList: [NotificationStatus.PENDING, NotificationStatus.SCHEDULED]]).list()
        cancelNotificationRequestList(notificationRequestIdList)
    }

	public void cancelCustomerAccountPendingNotification(Payment payment) {
        List<Long> notificationRequestIdList = NotificationRequest.pending([column: "id", payment: payment, receiver: NotificationReceiver.CUSTOMER]).list()
        cancelNotificationRequestList(notificationRequestIdList)
	}

    public void cancelPhoneCallNotificationsByNotification(Notification notification) {
        List<Long> notificationRequestIdList = NotificationRequest.query([column: "id", notification: notification, type: NotificationType.PHONE_CALL, status: [NotificationStatus.SCHEDULED, NotificationStatus.PENDING]]).list()
        cancelNotificationRequestList(notificationRequestIdList)
    }

	public void setNotificationRequestAsViewed(Long id, Date viewedDate) {
		if (!id) return

		NotificationRequest.executeUpdate("update NotificationRequest set version = version + 1, viewed = true, viewedDate = :viewedDate where id = :id and viewed = false", [id: id, viewedDate: viewedDate])
	}

	public NotificationRequest save(NotificationRequest notificationRequest) {
		return notificationRequest.save(flush: true, failOnError: true)
	}

    private String buildFromName(Customer customer) {
        CustomerNotificationConfigCacheVO customerNotificationConfigCacheVO = customerNotificationConfigCacheService.getInstance(customer.id)

        if (customerNotificationConfigCacheVO?.emailFromName && customerNotificationConfigCacheVO?.emailFrom) {
            return Utils.replaceInvalidMailCharacters(customerNotificationConfigCacheVO.emailFromName, " ")
        } else {
            return CustomerInfoFormatter.formatName(customer)
        }
    }

    private String buildEmailFrom(Customer customer) {
        if (AsaasEnvironment.isSandbox()) {
            return new SimpleTemplateEngine().createTemplate(grailsApplication.config.asaas.sandbox.notification.sender.email.template).make([customerId: customer.id]).toString()
        }

        CustomerNotificationConfigCacheVO customerNotificationConfigCacheVO = customerNotificationConfigCacheService.getInstance(customer.id)

        if (customerNotificationConfigCacheVO?.emailFrom && customerNotificationConfigCacheVO?.emailFromName) {
            return customerNotificationConfigCacheVO.emailFrom
        }

        CustomerInvoiceConfig publicInfoConfig = customer.getPublicInfoConfig()
        if (publicInfoConfig?.shouldUsePublicEmail()) {
            return customer.publicInfoConfig?.publicEmail
        }

        return new SimpleTemplateEngine().createTemplate(grailsApplication.config.asaas.notification.sender.email.template).make([customerId: customer.id]).toString()
    }

    public void createPaymentOverdueNotificationRequestInDailySchedule(Payment payment) {
        Notification notification = Notification.query([customerAccount: payment.customerAccount, event: NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, schedule: NotificationSchedule.AFTER, scheduleOffset: Notification.DAILY_SCHEDULE_OFFSET, enabled: true]).get()
        if (!notification) return

        Date scheduledDate = new Date().clearTime()

        for (int i = 0; i < Notification.MAX_NOTIFICATIONS_FOR_OVERDUE_PAYMENTS; i++) {
            scheduledDate = CustomDateUtils.addBusinessDays(scheduledDate, 1)
            List<NotificationRequest> notificationRequestList = save(notification, payment, NotificationPriority.LOW, [manual: false, scheduledDate: CustomDateUtils.setTime(scheduledDate, 8, 0, 0)])

            List<NotificationRequest> emailNotificationRequestList = notificationRequestList.findAll { it.type.equals(NotificationType.EMAIL) }
            for (emailNotificationRequest in emailNotificationRequestList) {
                emailNotificationRequest.scheduledDate = calculateScheduledDate(emailNotificationRequest)
                emailNotificationRequest.save(failOnError: true)
            }
        }
    }

    private void addWhatsappOnSendQueue(NotificationRequestTemplateVO notificationRequestTemplateVO) throws Exception {
        NotificationRequest notificationRequest = notificationRequestTemplateVO.notificationRequest
        Map dataMap = notificationRequestTemplateVO.notificationTemplateDataMap

        InstantTextMessageType instantTextMessageType = InstantTextMessageType.convert(notificationRequest.type)
        NotificationEvent notificationEvent = notificationRequest.getEventTrigger()
        NotificationSchedule notificationSchedule = notificationRequest.getNotificationRequestTrigger()?.schedule
        String messageText = InstantTextMessageBuilder.buildMessageTemplate(notificationEvent, notificationSchedule, dataMap)
        String phoneNumber = notificationRequest.customerAccount.mobilePhone

        instantTextMessageService.save(messageText.decodeHTML(), phoneNumber, instantTextMessageType, notificationRequest)
    }

    private Date calculateEmailNotificationScheduledHour(Customer customer, Date scheduledDate) {
        if (hasPartitionedSchedulingEmailNotificationEnabled(customer)) {
            Integer maximumSchedulingHour = 13
            Integer minimumSchedulingHour = [NotificationRequest.EMAIL_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, CustomDateUtils.getHourOfDate(scheduledDate)].max()
            if (minimumSchedulingHour > maximumSchedulingHour) minimumSchedulingHour = maximumSchedulingHour

            Integer intervalSize = maximumSchedulingHour - minimumSchedulingHour
            Integer schedulingHour = minimumSchedulingHour + (intervalSize > 0 ? new SecureRandom().nextInt(intervalSize) : intervalSize)
            Integer schedulingMinute = new SecureRandom().nextInt(60)
            scheduledDate = CustomDateUtils.setTime(scheduledDate, schedulingHour, schedulingMinute, 0)

            if (scheduledDate >= CustomDateUtils.setTime(new Date().clearTime(), NotificationRequest.EMAIL_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)) return scheduledDate
        }

        scheduledDate = CustomDateUtils.setTime(scheduledDate, NotificationRequest.EMAIL_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)

        return scheduledDate
    }

    private Boolean hasPartitionedSchedulingEmailNotificationEnabled(Customer customer) {
        return CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_PARTITIONED_SCHEDULING_FOR_EMAIL_NOTIFICATION)
    }

    private Boolean isNotificationRequestReceiverValid(NotificationRequest notificationRequest) {
        if (notificationRequest.type.equals(NotificationType.EMAIL)) {
            List<String> receiverMailList = getReceiverEmailList(notificationRequest)
            if (!receiverMailList || receiverMailList.any { !Utils.emailIsValid(it) } ) {
                AsaasLogger.warn("NotificationRequestService.isNotificationRequestReceiverValid >> O email do destinatário da notificationRequest ${notificationRequest.id} é inválido!")
                return false
            }
        } else if ([NotificationType.SMS, NotificationType.WHATSAPP].contains(notificationRequest.type)) {
            String receiverMobilePhone
            if (notificationRequest.receiver.isCustomer()) receiverMobilePhone = notificationRequest.customerAccount.mobilePhone
            if (notificationRequest.receiver.isProvider()) receiverMobilePhone = notificationRequest.customerAccount.provider.mobilePhone

            if (!receiverMobilePhone || !PhoneNumberUtils.validateMobilePhone(receiverMobilePhone) || !PhoneNumberUtils.validateBlockListNumber(receiverMobilePhone)) {
                AsaasLogger.warn("NotificationRequestService.isNotificationRequestReceiverValid >> O telefone móvel do destinatário da notificationRequest ${notificationRequest.id} é inválido!")
                return false
            }
        }

        return true
    }

    private List<String> getReceiverEmailList(NotificationRequest notificationRequest) {
        List<String> receiverEmailList = []
        if (notificationRequest.receiver.isCustomer() && notificationRequest.customerAccount.email) receiverEmailList = [notificationRequest.customerAccount.email]
        if (notificationRequest.receiver.isProvider() && notificationRequest.customerAccount.provider.email) receiverEmailList = [notificationRequest.customerAccount.provider.email]

        if (notificationRequest.receiver.isCustomer() && notificationRequest.customerAccount.additionalEmails) {
            receiverEmailList.addAll(notificationRequest.customerAccount.additionalEmails.split(CustomerAccount.ADDITIONAL_EMAILS_SEPARATOR).collect { it.trim() })
        }

        if (notificationRequest.receiver.isProvider() && notificationRequest.customerAccount.provider.additionalEmails) {
            receiverEmailList.addAll(notificationRequest.customerAccount.provider.additionalEmails.split(Customer.ADDITIONAL_EMAILS_SEPARATOR).collect { it.trim() })
        }

        return receiverEmailList
    }

    private Boolean isPaymentStatusAllowedToSentOverdueNotification(PaymentStatus status) {
        return [PaymentStatus.OVERDUE, PaymentStatus.DUNNING_REQUESTED].contains(status)
    }

    private void cancelNotificationRequestList(List<Long> notificationRequestIdList) {
        for (Long notificationRequestId in notificationRequestIdList) {
            cancel(notificationRequestId)
        }
    }

    private void cancel(Long notificationRequestId) {
        Boolean shouldCancelInSameTransaction = false
        Utils.withNewTransactionAndRollbackOnError({
            NotificationRequest notificationRequest = NotificationRequest.get(notificationRequestId)
            if (!notificationRequest) {
                shouldCancelInSameTransaction = true
                return
            }

            cancelIfPossible(notificationRequest)
        }, [logErrorMessage: "NotificationRequestService.cancel >> Erro ao cancelar notificationRequest [${notificationRequestId}]."])

        if (shouldCancelInSameTransaction) {
            NotificationRequest notificationRequest = NotificationRequest.get(notificationRequestId)
            cancelIfPossible(notificationRequest)
        }
    }

    private void cancelIfPossible(NotificationRequest notificationRequest) {
        if (!notificationRequest.status.isCancellable()) return
        notificationRequest.status = NotificationStatus.CANCELLED
        notificationRequest.save(failOnError: true)
    }

    private void notificationRequestProcessFailHandler(Long notificationRequestId) {
        try {
            Utils.withNewTransactionAndRollbackOnError ({
                NotificationRequest notificationRequest = NotificationRequest.get(notificationRequestId)
                if (!notificationRequest.status.isPending()) {
                    AsaasLogger.info("NotificationRequestService.notificationRequestProcessFailHandler >> Status de NotificationRequest [${ notificationRequestId }] não está mais pendente [${ notificationRequest.status }]")
                    return
                }

                notificationRequest.attemptsToSend++

                final Integer maxAttemptsToSend = Integer.valueOf(grailsApplication.config.notificationRequest.maxAttempts)
                if (notificationRequest.attemptsToSend < maxAttemptsToSend) {
                    notificationRequest.save(failOnError: true)
                    return
                }

                notificationRequest.status = NotificationStatus.FAILED
                notificationRequest.save(failOnError: true)

                timelineEventService.createNotificationEvent(NotificationStatus.FAILED, notificationRequest, null, null)

                AsaasLogger.info("NotificationRequestService.notificationRequestProcessFailHandler >> Status de NotificationRequest [${ notificationRequestId }] alterado para FAILED")
            }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
        } catch (Exception exception) {
            AsaasLogger.error("NotificationRequestService.notificationRequestProcessFailHandler >> Erro ao tratar falha da NotificationRequest [${ notificationRequestId }]", exception)
        }
    }
}
