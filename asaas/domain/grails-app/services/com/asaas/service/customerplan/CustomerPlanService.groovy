package com.asaas.service.customerplan

import com.asaas.customer.CustomerParameterName
import com.asaas.customerplan.adapters.CustomerPlanPaymentMethodAdapter
import com.asaas.customerplan.adapters.CustomerPlanUpdateAdapter
import com.asaas.customerplan.adapters.NotifyCanceledCustomerPlanAdapter
import com.asaas.customerplan.enums.CustomerPlanCancelReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.plan.Plan
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.plan.CustomerPlanName
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerPlanService {

    def customerParameterService
    def customerPlanHistoryService
    def customerPlanMembershipService
    def customerPlanNotificationService
    def modulePermissionService

    public CustomerPlan createIfNotExists(Customer customer) {
        return createIfNotExists(customer, null)
    }

    public CustomerPlan createIfNotExists(Customer customer, BigDecimal value) {
        CustomerPlan customerPlanAlreadyExists = CustomerPlan.query([customer: customer]).get()

        if (customerPlanAlreadyExists) return customerPlanAlreadyExists

        Plan plan = selectDefaultPlanForCustomer(customer)

        if (!plan) {
            AsaasLogger.error("CustomerPlanService.createIfNotExists >> Não foi possível encontrar um plano padrão para o customer: ${customer.id}")
            return null
        }

        CustomerPlan customerPlan = new CustomerPlan()
        customerPlan.customer = customer
        customerPlan.plan = plan
        customerPlan.value = value ?: getCustomerPlanValueForCustomer(customer, plan)
        customerPlan.active = true
        customerPlan.save(failOnError: true)

        updateDefaultFeatures(customerPlan, true)

        return customerPlan
    }

    public CustomerPlan update(CustomerPlanUpdateAdapter customerPlanUpdateAdapter) {
        CustomerPlan customerPlan = CustomerPlan.query([customer: customerPlanUpdateAdapter.customer]).get()

        if (!customerPlan) {
            AsaasLogger.error("CustomerPlanService.update >> Customer não possui CustomerPlan ${customerPlanUpdateAdapter.customer.id}")
            return null
        }

        Boolean hasPlanChange =  customerPlan.planId != customerPlanUpdateAdapter.plan.id

        if (hasPlanChange) updateDefaultFeatures(customerPlan, false)

        customerPlanHistoryService.create(customerPlan)
        customerPlan.plan = customerPlanUpdateAdapter.plan
        customerPlan.value = customerPlanUpdateAdapter.value
        customerPlan.endDate = customerPlanUpdateAdapter.endDate
        customerPlan.active = customerPlanUpdateAdapter.active
        customerPlan.save(failOnError: true)

        if (hasPlanChange) updateDefaultFeatures(customerPlan, true)

        return customerPlan
    }

    public Boolean isDefaultPlanForCustomer(Customer customer, CustomerPlanName customerPlanName) {
        Plan currentPlan = selectDefaultPlanForCustomer(customer)
        return CustomerPlanName.convert(currentPlan.name) == customerPlanName
    }

    public void updateCustomerPlanOnPersonTypeChanged(Customer customer) {
        CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod = new CustomerPlanPaymentMethodAdapter(customer)
        if (!customerPlanPaymentMethod.id) {
            setDefaultPlanForCustomerIfNecessary(customer)
            return
        }

        CustomerPlan currentCustomerPlan = CustomerPlan.query([customerId: customer.id]).get()
        String oldCustomerPlanName = currentCustomerPlan.plan.name

        CustomerPlanUpdateAdapter customerPlanUpdateAdapter = new CustomerPlanUpdateAdapter()
        customerPlanUpdateAdapter.customer = customer
        customerPlanUpdateAdapter.plan = currentCustomerPlan.plan
        customerPlanUpdateAdapter.value = currentCustomerPlan.value
        customerPlanUpdateAdapter.active = currentCustomerPlan.active
        customerPlanUpdateAdapter.endDate = new Date()

        update(customerPlanUpdateAdapter)

        Boolean shouldPlanBeRefunded = customerPlanMembershipService.shouldPlanBeRefunded(customerPlanPaymentMethod.dateCreated)
        if (shouldPlanBeRefunded) {
            customerPlanMembershipService.refund(customerPlanPaymentMethod)
        } else {
            setDefaultPlanForCustomerIfNecessary(customer)
        }

        customerPlanMembershipService.cancel(customerPlanPaymentMethod)

        NotifyCanceledCustomerPlanAdapter trackEventCanceledPlanAdapter = new NotifyCanceledCustomerPlanAdapter(customer, CustomerPlanCancelReason.PERSON_TYPE_CHANGED)
        trackEventCanceledPlanAdapter.isRefund = shouldPlanBeRefunded
        trackEventCanceledPlanAdapter.customer = customer
        trackEventCanceledPlanAdapter.canceledPlanName = oldCustomerPlanName
        trackEventCanceledPlanAdapter.scheduledFinishDate = new Date()
        trackEventCanceledPlanAdapter.segmentEventName = "customer_plan_update"
        trackEventCanceledPlanAdapter.segmentAction = "person_type_changed"

        customerPlanNotificationService.notifyCanceledPlan(trackEventCanceledPlanAdapter)
    }

    public CustomerPlan setDefaultPlanForCustomerIfNecessary(Customer customer) {
        CustomerPlan customerPlanAlreadyExists = CustomerPlan.query([customer: customer]).get()

        if (customerPlanAlreadyExists) {
            Plan defaultPlan = selectDefaultPlanForCustomer(customer)
            Boolean hasAlreadyDefaultPlan = customerPlanAlreadyExists.plan.id == defaultPlan.id

            if (hasAlreadyDefaultPlan) {
                if (!customerPlanAlreadyExists.endDate) {
                    AsaasLogger.warn("CustomerPlanService.setDefaultPlanForCustomerIfNecessary() -> Customer informado já tem o plano padrão. [CustomerId: ${customer.id}, PlanId: ${defaultPlan.id}]")
                    return customerPlanAlreadyExists
                }

                Boolean hasOverdueEndDate = CustomDateUtils.setTimeToEndOfDay(new Date()) > customerPlanAlreadyExists.endDate
                if (hasOverdueEndDate) return update(CustomerPlanUpdateAdapter.build(customer, defaultPlan))

                return customerPlanAlreadyExists
            }

            return update(CustomerPlanUpdateAdapter.build(customer, defaultPlan))
        }

        return createIfNotExists(customer)
    }

    public Boolean isPhoneCallCustomerServiceEnabled(Customer customer) {
        return CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_PHONE_CALL_FOR_CUSTOMER_SERVICE)
    }

    public Boolean isCustomNotificationTemplatesEnabled(Customer customer) {
        return CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_CUSTOM_NOTIFICATION_TEMPLATES)
    }

    public Boolean hasDisableChooseCustomerPlan(Customer customer) {
        return CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_CHOOSE_CUSTOMER_PLAN)
    }

    public Boolean hasAccessToCustomerPlanConfig() {
        if (!modulePermissionService.allowed("config")) return false
        Customer customer = UserUtils.getCurrentUser()?.customer
        if (!customer) return false
        if (!customer.cpfCnpj) return false
        if (!customer.personType) return false
        if (customer.personType.isJuridica() && !isAdvancedPlanSubscriptionEnabled(customer)) return false
        if (hasDisableChooseCustomerPlan(customer)) return false

        return true
    }

    public String getDefaultCommercialPlanName(Boolean isLegalPerson) {
        CustomerPlanName planName = CustomerPlanName.getDefaultPlanName(isLegalPerson)
        return Utils.getMessageProperty("customerPlan.${planName.toString()}.label")
    }

    public Plan selectDefaultPlanForCustomer(Customer customer) {
        CustomerPlanName customerPlanName = CustomerPlanName.getDefaultPlanName(customer.isLegalPerson())
        return Plan.query([name: customerPlanName.toString()]).get()
    }

    public BigDecimal getCustomerPlanValueForCustomer(Customer customer, Plan plan) {
        CustomerPlanName customerPlanName = CustomerPlanName.convert(plan.name)

        switch (customerPlanName) {
            case CustomerPlanName.STANDARD:
                return getCustomerPlanStandardValueForCustomer(customer)
            default:
                return plan.value
        }
    }

    public Boolean isAdvancedPlanSubscribed(Customer customer) {
        Plan currentPlan = CustomerPlan.query([
                column: "plan",
                customer: customer,
                disableSort: true
        ]).get() as Plan

        if (!currentPlan) return false

        return CustomerPlanName.convert(currentPlan.name)?.isAdvanced()
    }

    public Boolean isAdvancedPlanSubscriptionEnabled(Customer customer) {
        if (AsaasEnvironment.isSandbox() && !customer.hasUserWithSysAdminRole()) return false
        return CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_ADVANCED_PLAN_SUBSCRIPTION)
    }

    private BigDecimal getCustomerPlanStandardValueForCustomer(Customer customer) {
        if (customer.isLegalPerson()) return CustomerPlan.STANDARD_LEGAL_PERSON_VALUE

        return CustomerPlan.STANDARD_NATURAL_PERSON_VALUE
    }

    private void updateDefaultFeatures(CustomerPlan customerPlan, Boolean enable) {
        CustomerPlanName customerPlanName = CustomerPlanName.convert(customerPlan.plan.name)
        List<CustomerParameterName> defaultCustomerParameterNameList = customerParameterService.getDefaultFeaturesForCustomerPlan(customerPlanName)

        if (defaultCustomerParameterNameList.isEmpty()) return

        defaultCustomerParameterNameList.forEach({ CustomerParameterName parameter ->
            customerParameterService.save(customerPlan.customer, parameter, enable)
        })
    }
}
