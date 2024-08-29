package com.asaas.service.stage

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.customer.CustomerStatus
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.customerstageevent.CustomerStageEventStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbusinessopportunityanalysis.CustomerBusinessOpportunityAnalysis
import com.asaas.domain.customerstage.CustomerStage
import com.asaas.domain.customerstage.CustomerStageEvent
import com.asaas.domain.stage.StageEvent
import com.asaas.log.AsaasLogger
import com.asaas.stage.StageCode
import com.asaas.stage.StageEventCycle
import com.asaas.stage.StageEventType
import com.asaas.stage.querybuilder.CustomerStageQueryBuilder
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class CustomerStageEventService {

	def customerMessageService
	def customerStageService
	def financialTransactionService
	def stageEventService

    public void createImmediateCustomerEventsForStage(CustomerStage customerStage) {
		if (customerStage.customer.isBlocked || customerStage.customer.customerRegisterStatus?.generalApproval?.isRejected()) return

		List<StageEvent> immediateEvents = stageEventService.listImmediateEventsForStage(customerStage.stage)

		for (StageEvent stageEvent : immediateEvents) {
			save(customerStage, stageEvent)
		}
    }

    public void createCustomerStageEvent() {
        AsaasLogger.info("CustomerStageEventService.createCustomerStageEvent >> Searching active events")
        List<Long> stageEventIdList = stageEventService.listActiveStageEvents()
        AsaasLogger.info("CustomerStageEventService.createCustomerStageEvent >> Found ${stageEventIdList.size()} events")

        for (Long stageEventId : stageEventIdList) {
            AsaasLogger.info("CustomerStageEventService.createCustomerStageEvent >> Searching customers to process event >> ${stageEventId}")

            for (Long customerStageId : listCustomerStageEventToBeProcessed(stageEventId)) {
                Utils.withNewTransactionAndRollbackOnError({
                    StageEvent stageEvent = StageEvent.get(stageEventId)
                    CustomerStage customerStage = CustomerStage.get(customerStageId)

                    save(customerStage, stageEvent)
                }, [onError: { Exception exception ->
                    AsaasLogger.error("CustomerStageEventService.createCustomerStageEvent -> Erro ao criar CustomerStageEvent [stageEventId: ${stageEventId}, customerStageId: ${customerStageId}]")
                }])
            }
        }
    }

	public void processPendingEvents() {
        final Integer maxItems = 200
        List<Long> pendingEventListId = CustomerStageEvent.executeQuery("select id from CustomerStageEvent where status = :pending", [pending: CustomerStageEventStatus.PENDING, max: maxItems])

        Utils.forEachWithFlushSession(pendingEventListId, 50, { Long id ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerStageEvent customerStageEvent = CustomerStageEvent.get(id)
                Customer customer = customerStageEvent.customerStage.customer

                if (customerStageEvent.stageEvent.type == StageEventType.SMS) {
                    customerMessageService.notifyCustomerStageEventBySms(customer, customerStageEvent.stageEvent)
                } else if (customerStageEvent.stageEvent.type == StageEventType.EMAIL) {
                    customerMessageService.notifyCustomerStageEventByEmail(customer, customerStageEvent.stageEvent)
                }

                customerStageEvent.status = CustomerStageEventStatus.PROCESSED
                customerStageEvent.save(failOnError: true)
            }, [onError: { Exception exception ->
                    AsaasLogger.error("CustomerStageEventService.processPendingEvents -> Erro ao processar CustomerStageEvent [${id}]")
                    failEventIfNotProcessed(id)
                }
            ])
        })
	}

    public void processExpiredCustomerStageEvent() {
        List<StageEvent> stageEventList = stageEventService.listEventsThatHaveExpiration()

        for (StageEvent stageEvent : stageEventList) {
            List<CustomerStageEvent> customerStageEventList = findNotProcessedAndExpiredCustomerStageEvent(stageEvent)

            for (CustomerStageEvent customerStageEvent : customerStageEventList) {
                setCustomerStageEventAsFailed(customerStageEvent)
            }
        }
    }

    public void cancelCustomerStageEvents(CustomerStage customerStage) {
        List<CustomerStage> customerStageEventList = CustomerStageEvent.notProcessed(customerStage, [:]).list()

        for (CustomerStageEvent customerStageEvent : customerStageEventList) {
            setCustomerStageEventAsCancelled(customerStageEvent)
        }
    }

    public void cancelPhoneCallCustomerStageEvents(CustomerStage customerStage) {
        List<CustomerStageEvent> customerStageEventList = CustomerStageEvent.notProcessed(customerStage, ["stageEventType": StageEventType.PHONE_CALL]).list()

        for (CustomerStageEvent customerStageEvent : customerStageEventList) {
            setCustomerStageEventAsCancelled(customerStageEvent)
        }
    }

    private CustomerStageEvent save(CustomerStage customerStage, StageEvent stageEvent) {
        if (!canSave(customerStage, stageEvent)) return

        CustomerStageEvent customerStageEvent = new CustomerStageEvent(customerStage: customerStage, stageEvent: stageEvent)
        customerStageEvent.save(flush: true, failOnError: true)

        return customerStageEvent
    }

    private Boolean canSave(CustomerStage customerStage, StageEvent stageEvent) {
        if (customerStage.stage.code == StageCode.RETAINED) {
            if (financialTransactionService.calculateMonthlyAsaasIncome(customerStage.customer) < 200.00) return false
        }

        final Long firstPaymentSmsStageEventId = 11
        final Long firstPaymentPhoneCallStageEventId = 12

        if ([firstPaymentSmsStageEventId, firstPaymentPhoneCallStageEventId].contains(stageEvent.id)) {
            if (!customerStage.customer.segment) return false

            if (!customerStage.customer.personType) return false

            if (customerStage.customer.segment.isSmall() && customerStage.customer.personType.isFisica()) return false

            if (customerStage.customer.segment.isSmall() && customerStage.customer.isMEI()) return false
        }

        if (stageEvent.type == StageEventType.PHONE_CALL) {
            Boolean hasCustomerBusinessOpportunityAnalysis = CustomerBusinessOpportunityAnalysis.query([exists: true, customerId: customerStage.customer.id]).get().asBoolean()
            if (hasCustomerBusinessOpportunityAnalysis) return false
        }

        return true
    }

    private void failEventIfNotProcessed(Long id) {
        Utils.withNewTransactionAndRollbackOnError({
            CustomerStageEvent customerStageEvent = CustomerStageEvent.get(id)
            customerStageEvent.status = CustomerStageEventStatus.ERROR
            customerStageEvent.save(failOnError: true)
        }, [logErrorMessage: "CustomerStageEventService.failEventIfNotProcessed >> Erro setar erro para o customerStageEvent: [${id}]"])
    }

    private Set<Long> listCustomerStageEventToBeProcessed(Long stageEventId) {
        StageEvent stageEvent = StageEvent.read(stageEventId)

        Set<Long> customerStageEventList = new HashSet<Long>()
        customerStageEventList.addAll(listEventsThatNeverRan(stageEvent))

        if (stageEvent.recurrent) {
            customerStageEventList.addAll(listRecurrentStageEvents(stageEvent))
        }

        return customerStageEventList
    }

    private Set<Long> listEventsThatNeverRan(StageEvent stageEvent) {
        Calendar calendarStartDate = Calendar.getInstance()
        Calendar calendarEndDate = Calendar.getInstance()

        if (stageEvent.startDelayCycleInterval && stageEvent.startDelayCycleInterval > 0) {
            calendarStartDate.add(stageEvent.startDelayCycle.calendarPeriodConstant, stageEvent.startDelayCycleInterval * -2)
            calendarEndDate.add(stageEvent.startDelayCycle.calendarPeriodConstant, stageEvent.startDelayCycleInterval * -1)
        } else {
            calendarStartDate.add(stageEvent.cycle.calendarPeriodConstant, stageEvent.cycleInterval * -2)
            calendarEndDate.add(stageEvent.cycle.calendarPeriodConstant, stageEvent.cycleInterval * -1)
        }

        calendarEndDate = CustomDateUtils.setTimeToEndOfDay(calendarEndDate)
        Date startDate = calendarStartDate.getTime().clearTime()
        Date endDate = calendarEndDate.getTime()

        String sql = CustomerStageQueryBuilder.listEventsThatNeverRan()
        SQLQuery query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery(sql)
        query.setDate("startDate", startDate)
        query.setDate("endDate", endDate)
        query.setString("blockedStatus", CustomerStatus.BLOCKED.toString())
        query.setString("rejectedStatus", GeneralApprovalStatus.REJECTED.toString())
        query.setLong("stageEventId", stageEvent.id)

        return query.list().collect { Utils.toLong(it) }
    }

    private Set<Long> listRecurrentStageEvents(StageEvent stageEvent) {
        Boolean eventExpires = stageEvent.expirationCycle ? true : false
        String sql = CustomerStageQueryBuilder.listRecurrentStageEvents(stageEvent, eventExpires)
        SQLQuery query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setString("customerBlockedStatus", CustomerStatus.BLOCKED.toString())
        query.setLong("stageEventId", stageEvent.id)
        query.setParameterList("awaitingProcessingStatusList", CustomerStageEventStatus.listAwaitingProcessingStatus().collect { it.toString() })
        query.setParameterList("processedStatusList", CustomerStageEventStatus.listProcessedStatus().collect { it.toString() })
        query.setDate("limitDate", getLimitDateForTheNextRecurrency(stageEvent))
        query.setString("customerRejectedStatus", GeneralApprovalStatus.REJECTED.toString())

        if (stageEvent.interruptEventCreationOnFailure) {
            query.setString("failedStatus", CustomerStageEventStatus.FAILED.toString())
        }

        if (eventExpires) {
            query.setDate("eventExpirationDate", getEventExpirationDate(stageEvent))
        }

        return query.list().collect { Utils.toLong(it) }
    }

	private Date getLimitDateForTheNextRecurrency(StageEvent stageEvent) {
		Calendar limitDate = Calendar.getInstance()
		limitDate.add(stageEvent.cycle.calendarPeriodConstant, stageEvent.cycleInterval * -1)
		CustomDateUtils.setTimeToEndOfDay(limitDate)

		return limitDate.getTime()
	}

	private Date getEventExpirationDate(StageEvent stageEvent) {
		Calendar eventExpirationDate

		if (stageEvent.expirationCycle == StageEventCycle.DAILY) {
			eventExpirationDate = CustomDateUtils.todayMinusBusinessDays(stageEvent.expirationCycleInterval)
		} else {
			eventExpirationDate = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime())
			eventExpirationDate.add(stageEvent.expirationCycle.calendarPeriodConstant, stageEvent.expirationCycleInterval * -1)
		}

		return eventExpirationDate.getTime()
	}

	private List<CustomerStageEvent> findNotProcessedAndExpiredCustomerStageEvent(StageEvent stageEvent) {
		return CustomerStageEvent.executeQuery("""from CustomerStageEvent cse
                                                 where cse.stageEvent = :stageEvent
											       and cse.status in (:scheduled, :pending)
                                                   and cse.statusDate < :expirationDate""",
												   [stageEvent: stageEvent, scheduled: CustomerStageEventStatus.SCHEDULED, pending: CustomerStageEventStatus.PENDING, expirationDate: getCreatedEventExpirationDate(stageEvent)])
	}

	private Date getCreatedEventExpirationDate(StageEvent stageEvent) {
		Calendar createdEventExpirationDate = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime())

		if (stageEvent.createdEventExpirationCycle == StageEventCycle.DAILY) {
			createdEventExpirationDate = CustomDateUtils.todayMinusBusinessDays(stageEvent.createdEventExpirationCycleInterval)
		} else {
			createdEventExpirationDate = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime())
			createdEventExpirationDate.add(stageEvent.createdEventExpirationCycle.calendarPeriodConstant, stageEvent.createdEventExpirationCycleInterval * -1)
		}

		return createdEventExpirationDate.getTime()
	}

	private void setCustomerStageEventAsFailed(CustomerStageEvent customerStageEvent) {
		customerStageEvent.status = CustomerStageEventStatus.FAILED
		customerStageEvent.save(flush: true, failOnError: true)

		customerStageService.processEventExpired(customerStageEvent.customerStage.customer)
	}

	private void setCustomerStageEventAsCancelled(CustomerStageEvent customerStageEvent) {
		customerStageEvent.status = CustomerStageEventStatus.CANCELLED
		customerStageEvent.save(flush: true, failOnError: true)
	}
}
