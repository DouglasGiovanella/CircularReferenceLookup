package com.asaas.service.bankslip

import com.asaas.domain.bankslip.BankSlipRegisterNotification
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BankSlipRegisterNotificationService {

    def linkService
	def messageService
    def smsSenderService

	public BankSlipRegisterNotification updateNotificationScheduledDateIfNecessary(Payment payment) {
		BankSlipRegisterNotification bankSlipRegisterNotification = BankSlipRegisterNotification.query([payment: payment, sent: false]).get()

		if (!bankSlipRegisterNotification) return null

		if (PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) {
			bankSlipRegisterNotification.scheduledDate = payment.getEstimatedBankSlipRegisterTime()
		} else {
			bankSlipRegisterNotification.deleted = true
		}

		bankSlipRegisterNotification.save(failOnError: true)

		return bankSlipRegisterNotification
	}

	public BankSlipRegisterNotification saveOrUpdate(Payment payment, Map params) {
		if (params.mobilePhone) params.mobilePhone = PhoneNumberUtils.sanitizeNumber(params.mobilePhone)

		BankSlipRegisterNotification bankSlipRegisterNotification = validateSaveParams(params)

		if (bankSlipRegisterNotification.hasErrors()) return bankSlipRegisterNotification

		bankSlipRegisterNotification = BankSlipRegisterNotification.query([payment: payment]).get()

		if (!bankSlipRegisterNotification) bankSlipRegisterNotification = new BankSlipRegisterNotification()

		bankSlipRegisterNotification.payment = payment
		bankSlipRegisterNotification.sent = false
		bankSlipRegisterNotification.email = params.email
		bankSlipRegisterNotification.mobilePhone = params.mobilePhone
		bankSlipRegisterNotification.name = params.name ?: payment.customerAccount.name
		bankSlipRegisterNotification.scheduledDate = payment.getEstimatedBankSlipRegisterTime()
		bankSlipRegisterNotification.save(failOnError: true)

		return bankSlipRegisterNotification
	}

	private BankSlipRegisterNotification validateSaveParams(Map params) {
		BankSlipRegisterNotification bankSlipRegisterNotification = new BankSlipRegisterNotification()

		if (!params.email && !params.mobilePhone) DomainUtils.addError(bankSlipRegisterNotification, "Informe celular ou email.")
		if (params.email && !Utils.emailIsValid(params.email)) DomainUtils.addError(bankSlipRegisterNotification, "O email informado é inválido.")
		if (params.mobilePhone && !PhoneNumberUtils.validateMobilePhone(params.mobilePhone)) DomainUtils.addError(bankSlipRegisterNotification, "O celular informado é inválido.")

		return bankSlipRegisterNotification
	}

	public void sendPendingNotifications() {
		List<Long> pendingNotifications = BankSlipRegisterNotification.query([column: 'id', sent: false, "scheduledDate[le]": new Date()]).list()

		for (Long bankSlipRegisterNotificationId : pendingNotifications) {
			Boolean success

			Utils.withNewTransactionAndRollbackOnError({
				BankSlipRegisterNotification bankSlipRegisterNotification = BankSlipRegisterNotification.get(bankSlipRegisterNotificationId)

				if (!bankSlipRegisterNotification.payment.isPending() && !bankSlipRegisterNotification.payment.isOverdue()) {
					success = true

					bankSlipRegisterNotification.deleted = true
					bankSlipRegisterNotification.save(failOnError: true)
					return
				}

				bankSlipRegisterNotification.sent = true
				bankSlipRegisterNotification.save(failOnError: true)

				if (bankSlipRegisterNotification.mobilePhone){
                    String smsMessage = "Olá ${bankSlipRegisterNotification.name}! O boleto da sua cobrança para ${bankSlipRegisterNotification.payment.provider.buildTradingName()} já foi registrado. Veja aqui: ${linkService.viewInvoiceShort(bankSlipRegisterNotification.payment, [sentBySms: true])}"
                    smsSenderService.send(smsMessage, bankSlipRegisterNotification.mobilePhone, true, [:])
                }
				if (bankSlipRegisterNotification.email) messageService.notifyBankSlipRegistrationSuccess(bankSlipRegisterNotification.payment, bankSlipRegisterNotification.email, bankSlipRegisterNotification.name)

				success = true
			}, [errorEmailSubject: "Erro ao enviar BankSlipRegisterNotification [${bankSlipRegisterNotificationId}]"])

			if (success) continue

			Utils.withNewTransactionAndRollbackOnError({
				BankSlipRegisterNotification bankSlipRegisterNotification = BankSlipRegisterNotification.get(bankSlipRegisterNotificationId)
				bankSlipRegisterNotification.deleted = true
				bankSlipRegisterNotification.save(failOnError: true)
			})
		}
	}
}
