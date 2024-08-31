package com.asaas.service.payment

import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.subscription.Subscription
import com.asaas.log.AsaasLogger
import com.asaas.subscription.SubscriptionStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.transaction.TransactionStatus

@Transactional
class RecurrentPaymentGenerationService {

	def paymentService
    def subscriptionNotificationService
	def subscriptionService

    public void generateNextPaymentForAllCustomers() {
        final Integer subscriptionIdsListLimit = 2500
        Integer queryLimit = subscriptionIdsListLimit
        List<Long> subscriptionIdsList = []

        subscriptionIdsList.addAll(listSubscriptionsIdsWaitingPaymentGenerationWithCustomDays(queryLimit))

        queryLimit = subscriptionIdsListLimit - subscriptionIdsList.size()
        if (queryLimit > 0) {
            List<Integer> listCustomerIdsThatHaveCustomDays = CustomerParameter.query([distinct: "customer.id", name: CustomerParameterName.CUSTOM_DAYS_BEFORE_TO_CREATE_SUBSCRIPTION_PAYMENT]).list()
            if (listCustomerIdsThatHaveCustomDays) {
                subscriptionIdsList.addAll(listSubscriptionsIdsWaitingPaymentGeneration(Subscription.DEFAULT_DAYS_BEFORE_CREATE_PAYMENT, false, ['customerId[notIn]': listCustomerIdsThatHaveCustomDays], queryLimit))
            }
        }

        queryLimit = subscriptionIdsListLimit - subscriptionIdsList.size()
        if (queryLimit > 0) subscriptionIdsList.addAll(listSubscriptionsIdsWaitingPaymentGeneration(Subscription.DEFAULT_DAYS_BEFORE_CREATE_ASAAS_PLAN_PAYMENT, true, [:], queryLimit))

        final Integer flushEvery = 200
        Utils.forEachWithFlushSession(subscriptionIdsList, flushEvery, { Long subscriptionId ->
            generatePaymentForSubscription(subscriptionId)
        })
    }

    private void generatePaymentForSubscription(Long subscriptionId) {
		Boolean generatePaymentSuccess

		Subscription.withNewTransaction{ TransactionStatus status ->
			Subscription subscription = Subscription.get(subscriptionId)

            if (subscription.customerAccount.provider.accountDisabled()) return

			subscription.automaticRoutine = true

            AsaasLogger.info("Gerando proxima cobranca da assinatura [${subscription.id}]")

			try {
                if (!subscriptionService.hasPaymentsToBeCreated(subscription)) {
					subscription.status = SubscriptionStatus.EXPIRED
				} else {
					Calendar dueDate = CustomDateUtils.getInstanceOfCalendar(subscription.nextDueDate)

					Payment payment = paymentService.createRecurrentPayment(subscription, dueDate.getTime(), false, true, [automaticRoutine: true, ignoreCallbackDomainValidation: true])
					if (payment.hasErrors()) {
						throw new Exception(payment.errors.allErrors[0].defaultMessage)
					}
                    subscriptionService.calculateNextDueDateForSubscription(subscription)
				}

				subscription.save(failOnError: true)
                subscriptionNotificationService.notifyCustomerAboutSubscriptionEndingIfNecessary(subscription)

				generatePaymentSuccess = true
			} catch (Exception e) {
                AsaasLogger.error("Erro ao criar automaticamente cobrança recorrente da assinatura  [${subscription.id}]", e)
				status.setRollbackOnly()
			}
		}

		if (generatePaymentSuccess) return

		Utils.withNewTransactionAndRollbackOnError({
			Subscription subscription = Subscription.get(subscriptionId)

			Date lastWeek = CustomDateUtils.sumDays(new Date(), -7)

			if (subscription.nextDueDate.after(lastWeek)) return

			subscription.automaticRoutine = true
			subscriptionService.calculateNextDueDateForSubscription(subscription)

			subscription.save(failOnError: true)
		})
	}

    private List<Long> listSubscriptionsIdsWaitingPaymentGenerationWithCustomDays(Integer queryLimit) {
        List<Long> subscriptionIdsList = []
        List<Map> listCustomerIdsThatHaveCustomDays = CustomerParameter.query([
            columnList: ["customer.id", "numericValue"],
            name: CustomerParameterName.CUSTOM_DAYS_BEFORE_TO_CREATE_SUBSCRIPTION_PAYMENT,
            'numericValue[in]': Subscription.CUSTOM_DAYS_BEFORE_TO_CREATE_SUBSCRIPTION_PAYMENT
        ]).list()

        listCustomerIdsThatHaveCustomDays.groupBy { it.numericValue }.each { BigDecimal numberOfDays, List<Map> customers ->
            List<Long> subscriptionsIdsWaitingPaymentGenerationList = listSubscriptionsIdsWaitingPaymentGeneration(numberOfDays, false, ['customerId[in]': customers.collect { it."customer.id" }], queryLimit)
            subscriptionIdsList.addAll(subscriptionsIdsWaitingPaymentGenerationList)
        }

        return subscriptionIdsList
    }

    private List<Long> listSubscriptionsIdsWaitingPaymentGeneration(BigDecimal daysBeforeToCreatePayment, Boolean isPlan, Map paramsToSearch, Integer queryLimit) {
        final Integer safetyMarginInHoursToAvoidRecentlyUpdatedSubscriptions = -1
        final Integer safetyMarginInDaysToOvercomeExecutionErrors = 8

        Date dueDate = CustomDateUtils.sumDays(new Date(), daysBeforeToCreatePayment.toInteger())
        Date lastUpdatedFinish = CustomDateUtils.sumHours(new Date(), safetyMarginInHoursToAvoidRecentlyUpdatedSubscriptions)
        Date nextDueDateStart = CustomDateUtils.sumDays(new Date(), safetyMarginInDaysToOvercomeExecutionErrors * -1)

        Map search = [
            column: "id",
            'nextDueDate[ge]': nextDueDateStart,
            'nextDueDate[lt]': dueDate,
            'lastUpdated[le]': lastUpdatedFinish,
            status: SubscriptionStatus.ACTIVE,
            excludeWithCustomerAccountDeleted: true,
            'customerGeneralApproval[notIn]': [GeneralApprovalStatus.REJECTED],
            'customerStatus[notIn]': CustomerStatus.DISABLED,
            disableSort: true
        ]

        if (isPlan) {
            search.'plan[isNotNull]' = true
        } else {
            search.planIsNull = true
        }

        search = paramsToSearch + search

        return Subscription.query(search).list([max: queryLimit])
    }
}
