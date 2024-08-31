package com.asaas.service.notification

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.exception.NotificationNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationMessageType
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationSchedule
import com.asaas.notification.NotificationType
import com.asaas.notification.vo.CustomerAccountNotificationSettingsVO
import com.asaas.notification.vo.NotificationTypeVO
import com.asaas.notification.vo.NotificationVO
import com.asaas.payment.vo.WizardNotificationVO
import com.asaas.user.UserUtils
import com.asaas.utils.AsaasRandomUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class NotificationService {

    def messageSource
    def modulePermissionService
    def notificationDispatcherPaymentNotificationOutboxService
    def notificationRequestService
    def sessionFactory

	public Notification findNotification(notificationId, providerId, customerAccountId) {
		Notification notification

		try {
			notification = Notification.find("from Notification n where n.id = :id and n.customerAccount.provider.id = :providerId and n.customerAccount.id = :customerAccountId", [id: Long.valueOf(notificationId), providerId: providerId, customerAccountId: customerAccountId])
		} catch (ClassCastException) {
			notification = Notification.find("from Notification n where n.publicId = :id and n.customerAccount.provider.id = :providerId and n.customerAccount.id = :customerAccountId", [id: notificationId, providerId: providerId, customerAccountId: customerAccountId])
		}

		if (notification == null) throw new NotificationNotFoundException("Notificação inexistente.")

		return notification
	}

	public Notification findNotification(notificationId, providerId) {
		Notification notification

		try {
			notification = Notification.find("from Notification n where n.id = :id and n.customerAccount.provider.id = :providerId", [id: Long.valueOf(notificationId), providerId: providerId])
		} catch (ClassCastException) {
			notification = Notification.find("from Notification n where n.publicId = :id and n.customerAccount.provider.id = :providerId", [id: notificationId, providerId: providerId])
		}

		if (notification == null) throw new NotificationNotFoundException("Notificação inexistente.")

		return notification
	}

	public NotificationTrigger findNotificationTrigger(NotificationEvent event, NotificationSchedule schedule, Integer scheduleOffset) {
		return NotificationTrigger.find("from NotificationTrigger as n where n.event = :event and n.schedule = :schedule and n.scheduleOffset = :scheduleOffset and (n.deleted = false or n.deleted is null)", [event: event, schedule: schedule, scheduleOffset: scheduleOffset])
	}

	def list(Long customerAccountId, Long providerId, Integer max, Integer offset, search) {
		def list = Notification.createCriteria().list(max: max, offset: offset) {
			createAlias('customerAccount','customerAccount')
			createAlias('customerAccount.provider','provider')
			createAlias('trigger','trigger')

			and { eq("provider.id", providerId) }
			and { eq("deleted", false) }

			if (customerAccountId) {
				and { eq("customerAccount.id", customerAccountId) }
			}

			if (search?.event) {
				NotificationEvent event = NotificationEvent.convert(search.event)

				if (event) {
					and { eq("trigger.event", event)}
				} else {
					or {
						search.event.split(",").each {
							eq("trigger.event", NotificationEvent.convert(it))
						}
					}
				}
			}

			order(search?.sort ?: "trigger", search?.order ?: "asc")
		}
		return list
	}

    public Notification saveNotification(CustomerAccount customerAccount, NotificationEvent event, NotificationType type, NotificationReceiver receiver, Boolean enabled, NotificationSchedule schedule, Integer scheduleOffset) {
        NotificationVO notificationVO = new NotificationVO(event, schedule)
        notificationVO.scheduleOffset = scheduleOffset
        notificationVO.notificationTypeVOList.add(new NotificationTypeVO(type, receiver, enabled))

        saveWithNotificationVO(customerAccount, notificationVO)
    }

    public List<Map> buildEnabledNotificationTypeInfoForCustomerAccount(Long customerAccountId) {
        List<NotificationType> notificationTypeList = getEnabledNotificationTypeListForCustomerAccount(customerAccountId)
        List<Map> notificationTypeInfoList = []

        for (NotificationType notificationType : notificationTypeList) {
            Map notificationTypeInfo = [
                value: notificationType.toString(),
                label: messageSource.getMessage("sendType.${notificationType.toString()}", null, new Locale("pt", "BR"))
            ]

            notificationTypeInfoList.add(notificationTypeInfo)
        }

        return notificationTypeInfoList
    }

    public void saveWizardNotification(WizardNotificationVO wizardNotificationVO) {
        for (NotificationVO notificationVO : wizardNotificationVO.notificationVOList) {
            saveWithNotificationVO(wizardNotificationVO.customerAccount, notificationVO)
        }
    }

    public CustomerAccountNotificationSettingsVO buildCustomerAccountNotificationSettingsVO(Long customerAccountId) {
        List<Notification> notificationList = Notification.query(customerAccountId: customerAccountId).list()
        return new CustomerAccountNotificationSettingsVO(notificationList)
    }

    private List<NotificationType> getEnabledNotificationTypeListForCustomerAccount(Long customerAccountId) {
        List searchColumnList = ["smsEnabledForCustomer", "emailEnabledForCustomer", "phoneCallEnabledForCustomer", "whatsappEnabledForCustomer"]
        List<Map> customerAccountNotificationEnabledTypeList = Notification.query(columnList: searchColumnList, customerAccountId: customerAccountId).list()

        List<NotificationType> notificationTypeList = []

        if (customerAccountNotificationEnabledTypeList.any { it.smsEnabledForCustomer }) notificationTypeList.add(NotificationType.SMS)
        if (customerAccountNotificationEnabledTypeList.any { it.emailEnabledForCustomer }) notificationTypeList.add(NotificationType.EMAIL)
        if (customerAccountNotificationEnabledTypeList.any { it.phoneCallEnabledForCustomer }) notificationTypeList.add(NotificationType.PHONE_CALL)
        if (customerAccountNotificationEnabledTypeList.any { it.whatsappEnabledForCustomer }) notificationTypeList.add(NotificationType.WHATSAPP)

        return notificationTypeList
    }

	public Notification toggleNotificationType(Long notificationId, Long providerId, String field) {
		Notification notification = findNotification(notificationId, providerId)

        if (field.endsWith("ForCustomer") && notification.updateBlocked) throw new BusinessException("Esta notificação é obrigatória e não pode ser alterada")

		notification."${field}" = !notification."${field}"

		notification.save(flush: true, failOnError: true)

        notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)

		return notification
	}

	public void disableNotificationsIfNecessary(CustomerAccount customerAccount) {
        List<Notification> notificationList = Notification.getCustomerAccountNotificationList(customerAccount)
		for (Notification notification : notificationList) {
			if (!customerAccount.email) notification.emailEnabledForCustomer = false

			if (!customerAccount.mobilePhone && !customerAccount.phone) notification.phoneCallEnabledForCustomer = false

            if (!customerAccount.mobilePhone) {
                notification.smsEnabledForCustomer = false
                notification.whatsappEnabledForCustomer = false
            }

            if (notification.isDirty()) {
                notification.save(flush: true)
                notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
            }
		}
	}

    public void disableSmsAndEmailForCustomerAccountNotification(CustomerAccount customerAccount, NotificationEvent event, NotificationSchedule schedule, Integer scheduleOffset) {
		findAndDisableCustomerAccountNotifications(customerAccount, event, schedule, scheduleOffset, [NotificationType.SMS, NotificationType.EMAIL])
	}

	private void findAndDisableCustomerAccountNotifications(CustomerAccount customerAccount, NotificationEvent event, NotificationSchedule schedule, Integer scheduleOffset, List<NotificationType> typesToDisable) {
		Map notificationQueryParams = [customerAccount: customerAccount, event: event, schedule: schedule, updateBlocked: false]

		if (event == NotificationEvent.PAYMENT_DUEDATE_WARNING && scheduleOffset != 0) {
			notificationQueryParams.'scheduleOffset[ne]' = 0
		} else if (event == NotificationEvent.CUSTOMER_PAYMENT_OVERDUE && schedule == NotificationSchedule.AFTER) {
			notificationQueryParams.'scheduleOffset[ne]' = 0
		} else {
			notificationQueryParams.scheduleOffset = scheduleOffset
		}

		List<Notification> notifications = Notification.query(notificationQueryParams).list()

		if (!notifications) return

		for (Notification notification : notifications) {

            if (typesToDisable.contains(NotificationType.EMAIL)) {
				notification.emailEnabledForCustomer = false
			}
			if (typesToDisable.contains(NotificationType.SMS)) {
				notification.smsEnabledForCustomer = false
			}
			if (typesToDisable.contains(NotificationType.PHONE_CALL)) {
				notification.phoneCallEnabledForCustomer = false
			}
            if (typesToDisable.contains(NotificationType.WHATSAPP)) {
                notification.whatsappEnabledForCustomer = false
            }

            if (!notification.isDirty()) continue
			notification.save(failOnError: true)
            notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
		}
	}

    public void findAndDisableProviderNotifications(CustomerAccount customerAccount, NotificationEvent event, NotificationSchedule schedule, Integer scheduleOffset, List<NotificationType> typesToDisable) {
        Map notificationQueryParams = [customerAccount: customerAccount, event: event, schedule: schedule]

        if (event == NotificationEvent.PAYMENT_DUEDATE_WARNING && scheduleOffset != 0) {
            notificationQueryParams.'scheduleOffset[ne]' = 0
        } else if (event == NotificationEvent.CUSTOMER_PAYMENT_OVERDUE && schedule == NotificationSchedule.AFTER) {
            notificationQueryParams.'scheduleOffset[ne]' = 0
        } else {
            notificationQueryParams.scheduleOffset = scheduleOffset
        }

        List<Notification> notifications = Notification.query(notificationQueryParams).list()
        if (!notifications) return

        for (Notification notification : notifications) {
            if (typesToDisable.contains(NotificationType.EMAIL)) notification.emailEnabledForProvider = false
            if (typesToDisable.contains(NotificationType.SMS)) notification.smsEnabledForProvider = false

            if (!notification.isDirty()) continue
            notification.save(failOnError: true)
            notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
        }
    }

	public void delete(id, Long providerId) {
		Notification notification = findNotification(id, providerId)
		notification.deleted = true
		notification.save(flush: true, failOnError: true)

        notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
	}

	public Set<Notification> createDefaultNotificationsForProvider(CustomerAccount customerAccount) {
		Set<Notification> notificationSet = []

        notificationSet.add(saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_RECEIVED, NotificationType.EMAIL, NotificationReceiver.PROVIDER, !customerAccount.notificationDisabled, NotificationSchedule.IMMEDIATELY, 0))
        notificationSet.add(saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, NotificationType.EMAIL, NotificationReceiver.PROVIDER, !customerAccount.notificationDisabled, NotificationSchedule.IMMEDIATELY, 0))

		return notificationSet
	}

    public void configureBatchCustomerAccountNotifications(List<Long> customerAccountsIdsList, List<Map> notificationSetupList) {
        final Integer numberOfThreads = 2
        final Integer numberMaxItensToFlush = 100

        Utils.processWithThreads(customerAccountsIdsList, numberOfThreads, { List<Long> items ->
            Utils.forEachWithFlushSession(items, numberMaxItensToFlush, { Long customerAccountId ->
                for (Map notificationSetup in notificationSetupList) {
                    Utils.withNewTransactionAndRollbackOnError({
                        Notification notification = Notification.query([customerAccountId: customerAccountId, event: notificationSetup.event, schedule: notificationSetup.schedule, scheduleOffset: notificationSetup.scheduleOffset]).get()
                        if (!notification) {
                            notification = Notification.query([customerAccountId: customerAccountId, event: notificationSetup.event, schedule: notificationSetup.schedule, "scheduleOffset[ne]": 0]).get()

                            if (!notification) {
                                notification = Notification.query([customerAccountId: customerAccountId, event: notificationSetup.event, schedule: notificationSetup.schedule, deletedOnly: true]).get()
                                notification?.deleted = false
                            }

                            if (!notification) {
                                AsaasLogger.warn("Não foi encontrada a notificação do pagador [${customerAccountId}]: event ${notificationSetup.event}, schedule ${notificationSetup.schedule}, scheduleOffset ${notificationSetup.scheduleOffset}")
                                return
                            }

                            notification.trigger = findTriggerByEvent(notificationSetup.event, notificationSetup.scheduleOffset)
                        }

                        if (notification.updateBlocked) {
                            AsaasLogger.warn("A notificação  ${notification.id} está bloqueada e não pode ser alterada")
                            return
                        }

                        notification.emailEnabledForProvider = Boolean.valueOf(notificationSetup.emailEnabledForProvider)
                        if (notification.customerAccount.email) {
                            notification.emailEnabledForCustomer = Boolean.valueOf(notificationSetup.emailEnabledForCustomer)
                        } else {
                            notification.emailEnabledForCustomer = false
                        }

                        notification.smsEnabledForProvider = Boolean.valueOf(notificationSetup.smsEnabledForProvider)
                        if (notification.customerAccount.mobilePhone) {
                            notification.smsEnabledForCustomer = Boolean.valueOf(notificationSetup.smsEnabledForCustomer)
                            notification.whatsappEnabledForCustomer = Boolean.valueOf(notificationSetup.whatsappEnabledForCustomer)
                        } else {
                            notification.smsEnabledForCustomer = false
                            notification.whatsappEnabledForCustomer = false
                        }

                        if (notification.customerAccount.mobilePhone || notification.customerAccount.phone) {
                            notification.phoneCallEnabledForCustomer = Boolean.valueOf(notificationSetup.phoneCallEnabledForCustomer)
                        } else {
                            notification.phoneCallEnabledForCustomer = false
                        }

                        if (!notification.isDirty()) return
                        AuditLogListener.withoutAuditLog ({ notification.save(flush: true, failOnError: true) })
                        notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
                    }, [logErrorMessage: "NotificationService.configureBatchCustomerAccountNotifications >> Erro ao configurar notificação para pagador ${customerAccountId} - event: ${notificationSetup.event}, schedule: ${notificationSetup.schedule}, scheduleOffset: ${notificationSetup.scheduleOffset}"])
                }
            })
        })
    }

    public NotificationTrigger findTriggerByEvent(NotificationEvent event, Integer scheduleOffset) {
        if (!event) return null

        if (event in [NotificationEvent.PAYMENT_DUEDATE_WARNING, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE]) {
            return NotificationTrigger.query([event: event, scheduleOffset: scheduleOffset]).get()
        } else {
            return NotificationTrigger.query([event: event]).get()
        }
    }

    public void disableAllPhoneCallNotifications(Customer customer, String accountManagerAssignmentEmail) {
        User currentUser = UserUtils.getCurrentUser()
        if (!currentUser.belongsToCommercialTeam() && !currentUser.belongsToBackofficeProductManagerTeam()) {
            throw new BusinessException("Este usuário não tem permissão para executar esta ação.")
        }
        if (currentUser.username != accountManagerAssignmentEmail) throw new BusinessException("Email de confirmação inválido!")

        AsaasLogger.info("disableAllPhoneCallNotifications - Desativação de notificações por voz solicitada por ${currentUser.username} para o cliente de ID ${customer.id}")

        List<Long> phoneCallNotificationIdList = Notification.query([column: "id", customer: customer, phoneCallEnabledForCustomer: true]).list()
        if (!phoneCallNotificationIdList) return

        Utils.forEachWithFlushSession(phoneCallNotificationIdList, 100, { Long notificationId ->
            Utils.withNewTransactionAndRollbackOnError( {
                Notification notification = Notification.get(notificationId)
                notification.phoneCallEnabledForCustomer = false

                if (notification.isDirty()) {
                    notification.save(failOnError: true)
                    notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
                }

                notificationRequestService.cancelPhoneCallNotificationsByNotification(notification)
            }, [onError: { Exception exception -> throw exception }, ignoreStackTrace: true])
        })
    }

    public void updateTemplatesAccordingToTheNotificationMessageType(Customer provider, NotificationMessageType notificationMessageType) {
        def updateStatement = sessionFactory.currentSession.createSQLQuery("""update notification n, notification_trigger ntr, customer_account ca
		                                                                         set n.email_template_for_customer_id = (select nt.id from notification_template nt where nt.type = :email and nt.receiver = :customerReceiver and nt.event = ntr.event and nt.schedule = ntr.schedule and nt.deleted = 0 and nt.provider_id is null and nt.message_type = :messageType),
		                                                                             n.sms_template_for_customer_id = (select nt.id from notification_template nt where nt.type = :sms and nt.receiver = :customerReceiver and nt.event = ntr.event and nt.schedule = ntr.schedule and nt.deleted = 0 and nt.provider_id is null and nt.message_type = :messageType),
		                                                                             n.email_template_for_provider_id = (select nt.id from notification_template nt where nt.type = :email and nt.receiver = :providerReceiver and nt.event = ntr.event and nt.schedule = ntr.schedule and nt.deleted = 0 and nt.provider_id is null and nt.message_type = :messageType),
		                                                                             n.sms_template_for_provider_id = (select nt.id from notification_template nt where nt.type = :sms and nt.receiver = :providerReceiver and nt.event = ntr.event and nt.schedule = ntr.schedule and nt.deleted = 0 and nt.provider_id is null and nt.message_type = :messageType),
		                                                                             n.last_updated = now(),
                                                                                     n.version = n.version + 1
		                                                                       where n.trigger_id = ntr.id
		                                                                         and n.customer_account_id = ca.id
		                                                                         and ca.provider_id = :providerId""")

        updateStatement.setString("email", NotificationType.EMAIL.toString())
        updateStatement.setString("sms", NotificationType.SMS.toString())
        updateStatement.setString("customerReceiver", NotificationReceiver.CUSTOMER.toString())
        updateStatement.setString("providerReceiver", NotificationReceiver.PROVIDER.toString())
        updateStatement.setString("messageType", notificationMessageType.toString())
        updateStatement.setLong("providerId", provider.id)

        updateStatement.executeUpdate()
    }

    public void saveWithNotificationVO(CustomerAccount customerAccount, NotificationVO notificationVO) {
        NotificationTrigger trigger = findTriggerByEvent(notificationVO.event, notificationVO.scheduleOffset)
        if (!trigger) throw new RuntimeException("A configuração de notificação ${notificationVO.event} com offset (${notificationVO.scheduleOffset}) não foi encontrada")

        Notification notification = Notification.query([customerAccount: customerAccount, event: notificationVO.event, schedule: notificationVO.schedule]).get()

        if (notification && notification.updateBlocked) throw new BusinessException("A notificação ${notification.id} é obrigatória e não pode ser alterada")

        if (!notification) {
            notification = new Notification()
            notification.customerAccount = customerAccount
            notification.emailEnabledForProvider = false
            notification.emailEnabledForCustomer = false
            notification.smsEnabledForProvider = false
            notification.smsEnabledForCustomer = false
            notification.phoneCallEnabledForCustomer = false
            notification.whatsappEnabledForCustomer = false
            notification.publicId = buildPublicId()
        }

        notification.trigger = trigger
        notification.enabled = true

        for (NotificationTypeVO notificationTypeVO : notificationVO.notificationTypeVOList) {
            String type = StringUtils.convertSnakeToCamelCase(notificationTypeVO.type.toString())
            String receiver = notificationTypeVO.receiver.toString().toLowerCase().capitalize()
            String propertyName = "${ type }EnabledFor${ receiver }"

            if (notification.hasProperty(propertyName)) notification."$propertyName" = notificationTypeVO.enabled
        }

        Boolean isNewOrDirty = !notification.id || notification.isDirty()
        if (!isNewOrDirty) return

        if (UserUtils.actorIsApiKey() || UserUtils.actorIsSystem()) {
            AuditLogListener.withoutAuditLog({ notification.save(flush: true, failOnError: true) })
        } else {
            notification.save(flush: true, failOnError: true)
        }

        notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)
    }

    private String buildPublicId() {
        String token = "not_" + AsaasRandomUtils.secureRandomAlphanumeric(16)

        Boolean tokenAlreadyExists = Notification.query([exists:true, publicId: token, includeDeleted: true]).get().asBoolean()

        if (tokenAlreadyExists) {
            AsaasLogger.warn("NotificationService.buildPublicId >> PublicId gerado para Notification já existe [PublicId: ${token}]")
            return buildPublicId()
        }

        return token
    }
}
