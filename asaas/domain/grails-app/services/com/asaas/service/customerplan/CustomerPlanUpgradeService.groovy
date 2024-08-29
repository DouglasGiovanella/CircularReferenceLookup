package com.asaas.service.customerplan

import com.asaas.customerplan.adapters.CreateMembershipAdapter
import com.asaas.customerplan.adapters.CreditCardAdapter
import com.asaas.customerplan.adapters.CustomerPlanUpdateAdapter
import com.asaas.customerplan.enums.CustomerPlanPaymentSource
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.plan.Plan
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerPlanUpgradeService {

    def customerPlanMembershipService
    def customerPlanNotificationService
    def customerPlanService
    def recurrentChargedFeeConfigForPlanService

    public void upgrade(Customer customer, Plan plan, CreditCardAdapter creditCardInfo, CustomerPlanPaymentSource paymentSource) {
        CustomerPlan customerPlanValidation = validateUpgrade(customer, plan, paymentSource)

        if (customerPlanValidation.hasErrors()) {
            List<String> validationMessages = DomainUtils.getValidationMessages(customerPlanValidation.getErrors())
            AsaasLogger.warn("CustomerPlanUpgradeService.upgrade() >> Erro ao fazer upgrade do plano. CustomerId: [${customer.id}], ErrorMessages: [${DomainUtils.getValidationMessagesAsString(customerPlanValidation.getErrors())}]")
            throw new BusinessException(StringUtils.formatFormularyValidationMessagesToHtml(validationMessages))
        }

        customerPlanService.update(CustomerPlanUpdateAdapter.build(customer, plan))

        CreateMembershipAdapter createMembershipAdapter = new CreateMembershipAdapter()
        createMembershipAdapter.customer = customer
        createMembershipAdapter.plan = plan
        createMembershipAdapter.creditCardInfo = creditCardInfo
        createMembershipAdapter.nextDueDate = new Date()

        customerPlanMembershipService.create(createMembershipAdapter, paymentSource)

        customerPlanNotificationService.notifyContractedPlan(customer, plan.name)
    }

    private CustomerPlan validateUpgrade(Customer customer, Plan plan, CustomerPlanPaymentSource paymentSource) {
        CustomerPlan customerPlanValidation = new CustomerPlan()

        if (customerPlanService.hasDisableChooseCustomerPlan(customer)) DomainUtils.addError(customerPlanValidation, Utils.getMessageProperty("default.featureNotAllowed.message"))

        Long customerPlanId = CustomerPlan.query([column: "plan.id", customerId: customer.id]).get()
        Boolean hasPlanAlready = customerPlanId == plan.id

        if (hasPlanAlready) DomainUtils.addError(customerPlanValidation, "Você já possui o plano informado.")

        Plan defaultPlanForCustomer = customerPlanService.selectDefaultPlanForCustomer(customer)
        Boolean isUpgradeToFreePlan = defaultPlanForCustomer.id == plan.id

        if (isUpgradeToFreePlan) DomainUtils.addError(customerPlanValidation, "O plano informado para contratação já é seu plano padrão.")

        if (paymentSource.isAccountBalance() && !recurrentChargedFeeConfigForPlanService.hasEnoughBalance(customer.id, plan.value)) {
            DomainUtils.addError(customerPlanValidation, "Seu saldo em conta é insuficiente para pagamento do Plano.")
        }

        return customerPlanValidation
    }
}
