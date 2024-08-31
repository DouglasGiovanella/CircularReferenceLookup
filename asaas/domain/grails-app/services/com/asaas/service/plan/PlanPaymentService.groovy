package com.asaas.service.plan

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.plan.Plan
import com.asaas.domain.planpayment.PlanPayment
import com.asaas.domain.subscription.Subscription
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.debit.DebitType

import grails.transaction.Transactional

@Transactional
class PlanPaymentService {

	def asaasSegmentioService

	def financialTransactionService

	def invoiceItemService

	def debitService

	public List<PlanPayment> listAvailable(Customer customer) {
		return PlanPayment.executeQuery("from PlanPayment pp where pp.customer = :customer and pp.freeBoletosUsed < pp.plan.freeBoletoAmount and endDate > :now and pp.deleted = false order by endDate", [customer: customer, now: new Date()])
	}

	public PlanPayment save(Customer customer, Plan plan, Payment payment, Date endDate) {
		PlanPayment planPayment = new PlanPayment(customer: customer, plan: plan, payment: payment, endDate: endDate)
		planPayment.save(flush: true, failOnError: true)

		return planPayment
    }

	public PlanPayment consumePlanPayment(Payment payment) {
		if (!payment.billingType in BillingType.equivalentToBoleto()) {
            AsaasLogger.error("PlanPaymentService.consumePlanPayment >> PlanPayment can only be applied to Boleto Payment. Payment >> ${payment.id}")
			throw new RuntimeException("Desconto de planos é aplicável somente a cobranças do tipo boleto.")
		}

		PlanPayment planPayment = listAvailable(payment.provider)[0]

		if (!planPayment) {
            AsaasLogger.error("PlanPaymentService.consumePlanPayment >> Customer ${payment.provider.id} does not have PlanPayment to be consumed.")
			throw new RuntimeException("O fornecedor ${payment.provider} não possui boletos grátis decorrentes de plano.")
		}

		planPayment.freeBoletosUsed++

		payment.appliedPlanPayment = planPayment

		return planPayment
	}

	public void processPlanPaymentIfNecessary(Payment payment) {
		if (!payment.provider.isAsaasProvider()) return

        Subscription subscription = payment.subscription

		if (!subscription?.plan) return

		Plan plan = subscription.plan

        AsaasLogger.info("PlanPaymentService.processPlanPaymentIfNecessary >> Registering Plan Payment! Payment >> ${payment.id} >> Customer >> ${payment.customerAccount.customer.providerName}")

		Calendar planEndDate = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime())

        Date lastPlanPaymentEndDate = PlanPayment.query([column: "endDate",
                                                         paymentCustomerAccountId: payment.customerAccountId,
                                                         paymentSubscriptionId: subscription.id,
                                                         sort: "endDate",
                                                         order: "desc"]).get()

		if (lastPlanPaymentEndDate && lastPlanPaymentEndDate.clearTime().after(new Date().clearTime())) {
			planEndDate.setTime(lastPlanPaymentEndDate.clearTime())
		} else {
			planEndDate.setTime(new Date().clearTime())
		}

        planEndDate = CustomDateUtils.getInstanceOfCalendar(CustomDateUtils.addCycle(planEndDate, plan.cycle))

		planEndDate.set(Calendar.HOUR, 23)
		planEndDate.set(Calendar.MINUTE, 59)

		PlanPayment planPayment = save(payment.customerAccount.customer, plan, payment, planEndDate.getTime())

		subscription.nextDueDate = planEndDate.getTime()
		subscription.expirationDay = planEndDate.get(Calendar.DAY_OF_MONTH)
		subscription.save(flush: true, failOnError: true)

		List<Payment> paymentList = listReceivedTodayAndWithoutAppliedPlanPayment(payment.customerAccount.customer)
		for (Payment paymentToApplyPlan in paymentList) {
			if (Plan.customerHasValidPlanPayment(paymentToApplyPlan.customerAccount.provider)) {
                AsaasLogger.info("PlanPaymentService.processPlanPaymentIfNecessary >> Applying PlanPayment to Payment >> ${paymentToApplyPlan.id} >> Value >> ${paymentToApplyPlan.value} >> NetValue >> ${paymentToApplyPlan.netValue}")
				paymentToApplyPlan.automaticRoutine = true
				consumePlanPayment(paymentToApplyPlan)
				paymentToApplyPlan.netValue = paymentToApplyPlan.value

				paymentToApplyPlan.save(flush: true, failOnError: true)

				financialTransactionService.deletePaymentFee(paymentToApplyPlan)
			}
		}

		invoiceItemService.saveForPlanPayment(planPayment)

        try {
            def dataMap = [type: payment.billingType == BillingType.BOLETO ? 'boleto' : 'cartao']
            asaasSegmentioService.track(payment.provider.id, "Logged :: Planos :: Pagamento :: Confirmado", dataMap)
        } catch (Exception exception) {
            AsaasLogger.error("PlanPaymentService.processPlanPaymentIfNecessary >> Erro ao gerar o track", exception)
        }
	}

	private List<Payment> listReceivedTodayAndWithoutAppliedPlanPayment(Customer customer) {
        String hql = "from Payment p where p.provider = :customer and p.paymentDate = :today and status = :received and p.appliedPlanPayment is null and billingType in :billingType and freePaymentPromotionalCode is null and p.anticipated = false order by p.id"
		return Payment.executeQuery(hql, [customer: customer, today: new Date().clearTime(), received: PaymentStatus.RECEIVED, billingType: BillingType.equivalentToBoleto()])
	}

	public void refund(Payment payment) {
		PlanPayment planPayment = payment.getCreatedPlanPayment()

		if (planPayment.freeBoletosUsed) {
			debitService.save(planPayment.customer, planPayment.freeBoletosUsed * planPayment.plan.boletoFee, DebitType.OTHER, "Taxa de ${planPayment.freeBoletosUsed} boleto(s) referente ao estorno do pagamento do plano ${planPayment.plan.name}", null)
		}

		planPayment.deleted = true
		planPayment.save(failOnError: true)
	}
}
