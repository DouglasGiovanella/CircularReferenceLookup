package com.asaas.service.test.financialtransaction

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.integration.acquiring.AcquiringEnabledManager

class RefundRecalculateTestService {

    def customerAccountService
    def paymentAfterConfirmEventProcessingService
    def paymentAfterCreditEventService
    def paymentRefundService
    def paymentSandboxService
    def paymentService
    def refundRequestService

    public BigDecimal executeScenarios(Customer customer) {
        AcquiringEnabledManager acquiringEnabledManager = new AcquiringEnabledManager()
        Boolean acquiringEnabled = acquiringEnabledManager.isEnabled()
        if (acquiringEnabled) acquiringEnabledManager.disable()

        BigDecimal currentBalance = 0

        CustomerAccount customerAccount = createNewCustomerAccount(customer)

        Payment paymentBoleto = createAndConfirmPayment(customer, BillingType.BOLETO, customerAccount)
        currentBalance += paymentBoleto.netValue
        currentBalance -= refundBoleto(customer, paymentBoleto)

        Payment paymentCreditCard = createAndConfirmPayment(customer, BillingType.MUNDIPAGG_CIELO, customerAccount)
        currentBalance += paymentCreditCard.netValue
        currentBalance -= refundCreditCard(paymentCreditCard)

        Payment paymentPartialCreditCard = createAndConfirmPayment(customer, BillingType.MUNDIPAGG_CIELO, customerAccount)
        currentBalance += paymentPartialCreditCard.netValue
        currentBalance -= refundPartialCreditCard(paymentPartialCreditCard)

        if (acquiringEnabled) acquiringEnabledManager.enable()

        return currentBalance
    }

    private Payment createAndConfirmPayment(Customer customer, BillingType billingType, CustomerAccount customerAccount) {
        Payment payment = createNewPayment(customerAccount, billingType)

        paymentSandboxService.confirmPaymentWithMockedData(payment.id, customer.id)
        paymentAfterConfirmEventProcessingService.processPaymentAfterConfirmEvent(payment)

        if (payment.isCreditCard()) paymentSandboxService.receiveCreditCardWithMockedData(payment.id, customer.id)

        paymentAfterCreditEventService.processPaymentAfterCreditEvent(payment)

        return payment
    }

    private BigDecimal refundBoleto(Customer customer, Payment payment) {
        RefundRequest refundRequest = refundRequestService.save(payment.id, customer)

        BigDecimal valueDebited = payment.value + refundRequest.fee
        return valueDebited
    }

    private BigDecimal refundPartialCreditCard(Payment payment) {
        BigDecimal valueToRefund = payment.value / 2
        paymentRefundService.refund(payment, [value: valueToRefund, refundOnAcquirer:false])

        return valueToRefund
    }

    private BigDecimal refundCreditCard(Payment payment) {
        paymentRefundService.refund(payment, [refundOnAcquirer:false])

        return payment.netValue
    }

    private CustomerAccount createNewCustomerAccount(Customer customer) {
        Map saveCustomerAccountData = [
            cpfCnpj: "234.269.580-23",
            name: "Test CustomerAccount",
            mobilePhone: "47900000000",
            email: "recalculate_test@asaas.com.br"
        ]
        return customerAccountService.save(customer, null, saveCustomerAccountData)
    }

    private Payment createNewPayment(CustomerAccount customerAccount, BillingType billingType) {
        final BigDecimal paymentValue = 100.0
        Date today = new Date().clearTime()

        Map params = [:]
        params.value = paymentValue
        params.netValue = paymentValue
        params.billingType = billingType
        params.dueDate = today
        params.customerAccount = customerAccount

        return paymentService.save(params, false)
    }

}
