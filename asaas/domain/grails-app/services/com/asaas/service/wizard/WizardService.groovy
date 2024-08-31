package com.asaas.service.wizard

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.payment.PaymentFeeVO
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.postalservice.PaymentPostalServiceFee
import com.asaas.postalservice.PostalServiceSendingError
import com.asaas.wizard.WizardSendType
import com.asaas.wizard.WizardSummaryVO

import grails.transaction.Transactional

@Transactional
class WizardService {

	def paymentFeeService
	def installmentService

	public Map calculateNetValue(Map params, Customer customer) {
		WizardSummaryVO wizardSummaryVO = WizardSummaryVO.build(customer, params)
		Map response = [:]

		if (wizardSummaryVO.isInstallment()) {
			Map firstInstallment = calculateCostsAndFees(wizardSummaryVO.value, wizardSummaryVO.billingType, wizardSummaryVO.listOfWizardSendType, wizardSummaryVO.installmentCount, wizardSummaryVO.customerAccount, installmentService.calculateNextDueDate(wizardSummaryVO.dueDate, 0), customer)
			Map secondInstallment = calculateCostsAndFees(wizardSummaryVO.value, wizardSummaryVO.billingType, wizardSummaryVO.listOfWizardSendType, wizardSummaryVO.installmentCount, wizardSummaryVO.customerAccount, installmentService.calculateNextDueDate(wizardSummaryVO.dueDate, 1), customer)

			response.installmentNetValue = firstInstallment.netValue + secondInstallment.netValue + (secondInstallment.netValue * (wizardSummaryVO.installmentCount - 2))
			response.netValue = secondInstallment.netValue
			response.fee = secondInstallment.paymentFee
		} else {
			Map costsAndFees = calculateCostsAndFees(wizardSummaryVO.value, wizardSummaryVO.billingType, wizardSummaryVO.listOfWizardSendType, wizardSummaryVO.installmentCount, wizardSummaryVO.customerAccount, wizardSummaryVO.dueDate, customer)
			response.netValue = costsAndFees.netValue
			response.fee = costsAndFees.paymentFee
		}

		response.status = true

		return response
	}

	private Map calculateCostsAndFees(Double value, BillingType billingType, List<WizardSendType> listOfWizardSendType, Integer installmentCount, CustomerAccount customerAccount, Date dueDate, Customer customer) {
		Map costsAndFees = [:]

        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(value, billingType, customer, installmentCount)
        costsAndFees.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        costsAndFees.paymentFee = value - costsAndFees.netValue

		if (listOfWizardSendType.contains(WizardSendType.POSTAL_SERVICE)) {
			List<PostalServiceSendingError> reasons = new PaymentPostalServiceValidator(customer ?: new Customer(), customerAccount.buildAddress(), dueDate).validate()

			if (!reasons) {
				costsAndFees.postalServiceFee = PaymentPostalServiceFee.getFee(customer)
				costsAndFees.netValue -= costsAndFees.postalServiceFee
			}
		}

		return costsAndFees
	}
}
