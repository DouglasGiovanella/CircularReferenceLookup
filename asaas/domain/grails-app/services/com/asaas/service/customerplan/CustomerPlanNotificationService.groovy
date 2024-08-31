package com.asaas.service.customerplan

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.customerplan.adapters.NotifyCanceledCustomerPlanAdapter
import com.asaas.customerplan.adapters.NotifyCustomerPlanPaymentMethodChangedAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.plan.Plan
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig
import com.asaas.payment.PaymentStatus
import com.asaas.product.Cycle
import com.asaas.recurrentchargedfeeconfig.enums.RecurrentChargedFeeConfigStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class CustomerPlanNotificationService {

    private static final Integer DAYS_LIMIT_TO_NOTIFY_OVERDUE_PAYMENT = 1

    def asaasSegmentioService
    def createCampaignEventMessageService

    public void notifyOverduePayment(Long customerId) {
        Customer customer = Customer.read(customerId)
        createCampaignEventMessageService.saveForCustomerPlanOverduePayment(customer)
    }

    public void notifyPaymentMethodChanged(NotifyCustomerPlanPaymentMethodChangedAdapter adapter) {
        createCampaignEventMessageService.saveForCustomerPlanPaymentMethodChanged(adapter)
    }

    public void notifyContractedPlan(Customer customer, String planName) {
        createCampaignEventMessageService.saveForCustomerPlanContracted(customer, planName)
    }

    public void notifyCanceledPlan(NotifyCanceledCustomerPlanAdapter notifyCanceledPlanAdapter) {
        notifyCanceledPlanToHubspot(notifyCanceledPlanAdapter)
        notifyCanceledPlanToSegment(notifyCanceledPlanAdapter)
    }

    public List<Long> listCustomersToNotifyOverduePayment() {
        Map queryParameters = [:]
        queryParameters."planId[in]" = Plan.getCustomerPaymentPlanId()
        queryParameters.status = PaymentStatus.OVERDUE
        queryParameters.dueDate = CustomDateUtils.sumDays(new Date(), DAYS_LIMIT_TO_NOTIFY_OVERDUE_PAYMENT * -1)
        queryParameters.distinct = "customerAccount.customer.id"
        queryParameters.disableSort = true
        return Payment.query(queryParameters).list()
    }

    public List<Long> listCustomersToNotifyOverdueRecurrentChargedFeeConfig() {
        Map queryParameters = [:]
        queryParameters.distinct = "customer.id"
        queryParameters.nextDueDate = CustomDateUtils.sumDays(new Date(), DAYS_LIMIT_TO_NOTIFY_OVERDUE_PAYMENT * -1)
        queryParameters.type = ChargedFeeType.CONTRACTED_CUSTOMER_PLAN
        queryParameters.status = RecurrentChargedFeeConfigStatus.ACTIVE
        queryParameters.cycle = Cycle.MONTHLY
        queryParameters.disableSort = true
        return RecurrentChargedFeeConfig.query(queryParameters).list()
    }

    private void notifyCanceledPlanToHubspot(NotifyCanceledCustomerPlanAdapter notifyCanceledPlanAdapter) {
        createCampaignEventMessageService.saveForCustomerPlanCanceled(notifyCanceledPlanAdapter)
    }

    private void notifyCanceledPlanToSegment(NotifyCanceledCustomerPlanAdapter notifyCanceledPlanAdapter) {
        if (notifyCanceledPlanAdapter.cancelReason.isUserAction()) {
            notifyCanceledPlanAdapter.segmentEventName = "customer_plan_unsubscribe_modal"
            notifyCanceledPlanAdapter.segmentAction = "canceled_by_customer"
        }

        if (notifyCanceledPlanAdapter.cancelReason.isOverduePayment()) {
            notifyCanceledPlanAdapter.segmentEventName = "customer_plan_update"
            notifyCanceledPlanAdapter.segmentAction = "downgrade_for_overdue_payment"
        }

        if (notifyCanceledPlanAdapter.cancelReason.isPersonTypeChanged()) {
            notifyCanceledPlanAdapter.segmentEventName = "customer_plan_update"
            notifyCanceledPlanAdapter.segmentAction = "person_type_changed"
        }

        asaasSegmentioService.track(notifyCanceledPlanAdapter.customer.id, notifyCanceledPlanAdapter.segmentEventName, notifyCanceledPlanAdapter.buildEventDataMap())
    }
}
