package com.asaas.service.customerplan

import com.asaas.customerplan.adapters.CreateMembershipAdapter
import com.asaas.customerplan.adapters.CreditCardAdapter
import com.asaas.customerplan.adapters.CustomerPlanPaymentMethodAdapter
import com.asaas.customerplan.adapters.NotifyCustomerPlanPaymentMethodChangedAdapter
import com.asaas.customerplan.adapters.PaymentMethodInfoAdapter
import com.asaas.customerplan.enums.CustomerPlanPaymentSource
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.payment.Payment
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.product.Cycle
import com.asaas.utils.StringUtils
import grails.transaction.Transactional

@Transactional
class CustomerPlanChangePaymentMethodService {

    def customerPlanMessageService
    def customerPlanMembershipService
    def customerPlanNotificationService
    def customerPlanService
    def subscriptionService

    public void changePaymentMethod(Customer customer, CreditCardAdapter creditCardInfo, CustomerPlanPaymentSource newPaymentSource) {
        CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod = new CustomerPlanPaymentMethodAdapter(customer)

        List<String> validationMessages = validateChangePaymentMethod(customerPlanPaymentMethod, newPaymentSource)
        if (validationMessages.size()) {
            AsaasLogger.warn("CustomerPlanChangePaymentMethodService.changePaymentMethod() >> Erro ao fazer troca do método de pagamento. CustomerId: [${customer.id}], ErrorMessages: [${StringUtils.listItemsWithCommaSeparator(validationMessages)}]")
            throw new BusinessException(StringUtils.formatFormularyValidationMessagesToHtml(validationMessages))
        }

        Date nextDueDate = calculateNextDueDateForCustomerPlan(customerPlanPaymentMethod)
        CustomerPlan customerPlan = CustomerPlan.query([customerId: customer.id]).get()

        customerPlanMembershipService.cancel(customerPlanPaymentMethod)

        CreateMembershipAdapter createMembershipAdapter = new CreateMembershipAdapter()
        createMembershipAdapter.customer = customer
        createMembershipAdapter.plan = customerPlan.plan
        createMembershipAdapter.creditCardInfo = creditCardInfo
        createMembershipAdapter.nextDueDate = nextDueDate

        customerPlanMembershipService.create(createMembershipAdapter, newPaymentSource)

        customerPlanNotificationService.notifyPaymentMethodChanged(new NotifyCustomerPlanPaymentMethodChangedAdapter(customer, customerPlanPaymentMethod.paymentSource, newPaymentSource))

        PaymentMethodInfoAdapter paymentMethodInfoAdapter = new PaymentMethodInfoAdapter()
        paymentMethodInfoAdapter.paymentSource = newPaymentSource

        if (newPaymentSource.isCreditCard()) {
            paymentMethodInfoAdapter.creditCardLastDigits = creditCardInfo.getCreditCardLastDigits()
            paymentMethodInfoAdapter.creditCardBrand = creditCardInfo.getCreditCardBrand()
        }
        customerPlanMessageService.notifyPaymentMethodChanged(customer, paymentMethodInfoAdapter)
    }

    public Boolean canChangePaymentMethod(CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod) {
        if (customerPlanPaymentMethod.paymentSource.isAccountBalance()) return true
        Boolean isInChargebackProcess = Payment.query([exists: true, subscriptionId: customerPlanPaymentMethod.id, statusList: PaymentStatus.chargebackStatusList()]).get().asBoolean()
        return !isInChargebackProcess
    }

    private List<String> validateChangePaymentMethod(CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod, CustomerPlanPaymentSource newPaymentSource) {
        List<String> validationMessages = []

        if (!customerPlanPaymentMethod.id) validationMessages.add("Sua conta não possui uma assinatura de plano ativa.")
        if (!canChangePaymentMethod(customerPlanPaymentMethod)) validationMessages.add("Não é possível alterar o método de pagamento. Entre em contato com nosso suporte.")
        if (newPaymentSource.isAccountBalance() && customerPlanPaymentMethod.paymentSource.isAccountBalance()) validationMessages.add("Método de pagamento já está cadastrado.")

        return validationMessages
    }

    private Date calculateNextDueDateForCustomerPlan(CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod) {
        if (customerPlanPaymentMethod.paymentSource.isAccountBalance()) return customerPlanPaymentMethod.nextDueDate

        Subscription subscription = Subscription.read(customerPlanPaymentMethod.id)

        Date lastConfirmedOrReceivedPaymentDueDate = subscription.getLastConfirmedOrReceivedPaymentDueDate()
        if (lastConfirmedOrReceivedPaymentDueDate) return subscriptionService.calculateNextDueDate(lastConfirmedOrReceivedPaymentDueDate, Cycle.MONTHLY, subscription.expirationDay)

        if (subscription.hasOverduePayments()) return new Date()

        Date firstPendingPaymentDueDate = subscription.getFirstPendingPaymentDueDate()
        if (firstPendingPaymentDueDate) return firstPendingPaymentDueDate

        throw new BusinessException("Sua conta não possui nenhum pagamento para a assinatura.")
    }
}
