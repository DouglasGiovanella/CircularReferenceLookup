package com.asaas.service.customerplan

import com.asaas.customerplan.adapters.CustomerPlanPaymentMethodAdapter
import com.asaas.customerplan.adapters.CustomerPlanUpdateAdapter
import com.asaas.customerplan.adapters.NotifyCanceledCustomerPlanAdapter
import com.asaas.customerplan.enums.CustomerPlanCancelReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.payment.Payment
import com.asaas.domain.plan.Plan
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.plan.CustomerPlanName
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerPlanDowngradeService {

    private static final Integer FLUSH_EVERY = 25

    private static final Integer MAX_ITEMS_PER_CYCLE = 1000

    def customerPlanMembershipService
    def customerPlanNotificationService
    def customerPlanService

    public CustomerPlan cancelSubscriptionAndScheduleCustomerPlanDowngrade(Customer customer, NotifyCanceledCustomerPlanAdapter notifyCanceledCustomerPlanAdapter) {
        CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod = new CustomerPlanPaymentMethodAdapter(customer)
        if (!customerPlanPaymentMethod.id) return null

        CustomerPlan currentCustomerPlan = CustomerPlan.query([customerId: customer.id]).get()
        String customerPlanNameToTrack = currentCustomerPlan.plan.name

        Boolean shouldPlanBeRefunded = customerPlanMembershipService.shouldPlanBeRefunded(customerPlanPaymentMethod.dateCreated)

        CustomerPlanUpdateAdapter customerPlanUpdateAdapter = new CustomerPlanUpdateAdapter()
        customerPlanUpdateAdapter.customer = customer
        customerPlanUpdateAdapter.plan = currentCustomerPlan.plan
        customerPlanUpdateAdapter.value = currentCustomerPlan.value
        customerPlanUpdateAdapter.active = currentCustomerPlan.active
        customerPlanUpdateAdapter.endDate = calculateEndDateToDowngradePlan(shouldPlanBeRefunded, notifyCanceledCustomerPlanAdapter.cancelReason, customerPlanPaymentMethod.nextDueDate)

        customerPlanService.update(customerPlanUpdateAdapter)

        if (shouldPlanBeRefunded) customerPlanMembershipService.refund(customerPlanPaymentMethod)

        customerPlanMembershipService.cancel(customerPlanPaymentMethod)

        notifyCanceledCustomerPlanAdapter.customer = customer
        notifyCanceledCustomerPlanAdapter.isRefund = shouldPlanBeRefunded
        notifyCanceledCustomerPlanAdapter.canceledPlanName = customerPlanNameToTrack
        notifyCanceledCustomerPlanAdapter.scheduledFinishDate = customerPlanUpdateAdapter.endDate

        customerPlanNotificationService.notifyCanceledPlan(notifyCanceledCustomerPlanAdapter)

        return currentCustomerPlan
    }

    public void downgradeCustomerPlanWithEndDateForCustomers() {
        List<Long> customerIdsForDowngrade = listCustomerWithCustomerPlanEndDateForDowngrade()

        AsaasLogger.info("CustomerPlanDowngradeService.downgradeCustomerPlanWithEndDateForCustomers() -> Quantidade de Customers para fazer downgrade: ${customerIdsForDowngrade.size()}")

        Utils.forEachWithFlushSession(customerIdsForDowngrade, FLUSH_EVERY, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)
                customerPlanService.setDefaultPlanForCustomerIfNecessary(customer)
            }, [logErrorMessage: "CustomerPlanDowngradeService.downgradeCustomerPlanWithEndDateForCustomers() -> Erro ao fazer downgrade do plano. [CustomerId: ${customerId}]"])
        })
    }

    public List<Long> downgradeCustomerPlanForCustomersWithOverduePaymentsRelatedPlans() {
        List<Long> customerIdsForDowngrade = listCustomersWithOverduePayments(CustomerPlan.DAYS_LIMIT_TO_DOWNGRADE_PLAN_FOR_OVERDUE_PAYMENT)

        AsaasLogger.info("CustomerPlanDowngradeService.downgradeCustomerPlanForCustomersWithOverduePaymentsRelatedPlans() -> Quantidade de Customers para fazer downgrade por inadimplência: ${customerIdsForDowngrade.size()}")

        Utils.forEachWithFlushSession(customerIdsForDowngrade, FLUSH_EVERY, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)
                cancelSubscriptionAndScheduleCustomerPlanDowngrade(customer, new NotifyCanceledCustomerPlanAdapter(customer, CustomerPlanCancelReason.OVERDUE_PAYMENT))
            }, [logErrorMessage: "CustomerPlanDowngradeService.downgradeCustomerPlanForCustomersWithOverduePaymentsRelatedPlans() -> Erro ao fazer downgrade do plano por inadimplência. [CustomerId: ${customerId}]"])
        })

        return customerIdsForDowngrade
    }

    private Date calculateEndDateToDowngradePlan(Boolean isRefund, CustomerPlanCancelReason cancelReason, Date nextDueDate) {
        if (isRefund) return new Date()
        if (cancelReason.isOverduePayment()) return new Date()

        return nextDueDate
    }

    private List<Long> listCustomerWithCustomerPlanEndDateForDowngrade() {
        Date endOfDay = CustomDateUtils.setTimeToEndOfDay(new Date())

        List<Long> customersForDowngrade = CustomerPlan.createCriteria().list(max: MAX_ITEMS_PER_CYCLE) {
            projections {
                property("customer.id", "customerId")
            }

            isNotNull("endDate")
            lt("endDate", endOfDay)
        }

        return customersForDowngrade
    }

    private List<Long> listCustomersWithOverduePayments(Integer overdueDays) {
        Long planId = Plan.query([column: "id", "name[in]": CustomerPlanName.STANDARD.toString()]).get()
        Map queryParameters = [:]
        queryParameters.distinct = "customerAccount.customer.id"
        queryParameters.disableSort = true
        queryParameters."planId[in]" = planId
        queryParameters.status = PaymentStatus.OVERDUE
        queryParameters."dueDate[le]" = CustomDateUtils.sumDays(new Date(), overdueDays * -1)
        List<Long> customerIdsForDowngrade = Payment.query(queryParameters).list(max: MAX_ITEMS_PER_CYCLE)
        return customerIdsForDowngrade
    }
}
