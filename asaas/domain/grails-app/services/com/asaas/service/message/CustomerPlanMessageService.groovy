package com.asaas.service.message

import com.asaas.customerplan.adapters.PaymentMethodInfoAdapter
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional
import java.text.SimpleDateFormat

@Transactional
class CustomerPlanMessageService {

    def customerMessageService
    def grailsApplication
    def messageService

    public void notifyPaymentMethodChanged(Customer customer, PaymentMethodInfoAdapter paymentMethodInfo) {
        if (!customerMessageService.canSendMessage(customer)) return

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd 'de' MMMM 'de' yyyy HH:mm:ss z", new Locale("pt", "BR"))
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT-3"))
        String changeDate = simpleDateFormat.format(new Date())

        String emailBody = messageService.buildTemplate(
            "/mailTemplate/customerPlan/paymentMethodChanged",
            [
                paymentMethod: buildPaymentMethodMessage(paymentMethodInfo),
                changeDate: changeDate
            ]
        )

        String emailSubject = "Um novo meio de pagamento foi cadastrado no seu plano"
        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, customer, emailSubject, emailBody)
    }

    private String buildPaymentMethodMessage(PaymentMethodInfoAdapter paymentMethodInfo) {
        if (paymentMethodInfo.paymentSource.isAccountBalance()) return "Saldo em conta"

        return "Cartão de crédito ${paymentMethodInfo.creditCardBrand.toString()} final ${paymentMethodInfo.creditCardLastDigits}"
    }
}
