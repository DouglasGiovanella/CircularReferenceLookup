package com.asaas.payment

import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.returnfile.BoletoReturnStatus
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.boleto.BoletoReturnFileItem
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import grails.transaction.Transactional

@Transactional
class BankSlipRegisterService {

	def boletoBatchFileItemService

	public BoletoBatchFileItem registerBankSlip(Payment payment) {
        if (!PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) return

		PaymentBuilder.setBoletoFields(payment, null)
		return saveInBatchFileIfIsRegisteredBoleto(payment)
	}

	public BoletoBatchFileItem saveInBatchFileIfIsRegisteredBoleto(Payment payment) {
        if (payment.registrationStatus == BoletoRegistrationStatus.WAITING_REGISTRATION) {
			return boletoBatchFileItemService.create(payment)
		}
	}

	public Boolean paymentHasBeenUnregistered(Payment payment) {
		return BoletoReturnFileItem.existsReturnItem(payment.getCurrentBankSlipNossoNumero(), payment.boletoBank, BoletoReturnStatus.DELETE_SUCCESSFUL)
	}
}
