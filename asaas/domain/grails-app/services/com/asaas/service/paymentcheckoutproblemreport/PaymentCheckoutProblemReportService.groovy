package com.asaas.service.paymentcheckoutproblemreport

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentCheckoutProblem
import com.asaas.domain.paymentcheckoutproblemreport.PaymentCheckoutProblemReport
import com.asaas.utils.DomainUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentCheckoutProblemReportService {

    def customerMessageService
    def messageService

    public PaymentCheckoutProblemReport save(Long paymentId, Map params) {
		PaymentCheckoutProblemReport validateFields = validateFields(params)
		if (validateFields.hasErrors()) return validateFields

		Payment payment = Payment.get(paymentId)

        params.observation = StringUtils.removeAllEmojis(params.observation)

		PaymentCheckoutProblemReport paymentCheckoutProblemReport = new PaymentCheckoutProblemReport()
		paymentCheckoutProblemReport.observation = params.observation
        paymentCheckoutProblemReport.name = params.name
        paymentCheckoutProblemReport.email = params.email
        paymentCheckoutProblemReport.phone = params.phone
        paymentCheckoutProblemReport.payment = payment
        paymentCheckoutProblemReport.paymentCheckoutProblem = PaymentCheckoutProblem.valueOf(params.itemReportProblem)
        paymentCheckoutProblemReport.save()

        if (paymentCheckoutProblemReport.hasErrors()) return paymentCheckoutProblemReport

		params.itemReportProblem = Utils.getMessageProperty("invoice.report.problem." + paymentCheckoutProblemReport.paymentCheckoutProblem)

		if (shouldNotifyAccountManager(payment.provider, paymentCheckoutProblemReport.paymentCheckoutProblem)) {
			messageService.sendReportProblem(params, payment)
		}

        customerMessageService.sendReportPaymentCheckoutProblemToCustomer(params, payment)

		return paymentCheckoutProblemReport
	}

	private PaymentCheckoutProblemReport validateFields(Map params) {
		PaymentCheckoutProblemReport paymentCheckoutProblemReport = new PaymentCheckoutProblemReport()

		if (!params.itemReportProblem) DomainUtils.addError(paymentCheckoutProblemReport, "Informe o problema que mais se encaixa com o que aconteceu.")

		if (!params.email) DomainUtils.addError(paymentCheckoutProblemReport, "Informe o seu endereço de email.")

		if (!params.phone) DomainUtils.addError(paymentCheckoutProblemReport, "Informe o seu telefone.")

		return paymentCheckoutProblemReport
	}

    private Boolean shouldNotifyAccountManager(Customer customer, PaymentCheckoutProblem paymentCheckoutProblem) {
   		return (!CustomerParameter.getValue(customer, CustomerParameterName.NOTIFY_PROVIDER_ONLY_ON_PAYMENT_INVOICE_PROBLEM_REPORT) && paymentCheckoutProblem.shouldNotifyAccountManager())
   	}
}