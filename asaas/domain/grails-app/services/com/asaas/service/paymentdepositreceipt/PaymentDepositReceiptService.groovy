package com.asaas.service.paymentdepositreceipt

import com.asaas.billinginfo.BillingType
import com.asaas.depositreceiptbuilder.DepositReceiptBuilder
import com.asaas.domain.bank.Bank
import com.asaas.domain.invoicedepositinfo.InvoiceDepositInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceipt
import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceiptFile
import com.asaas.paymentdepositreceiptrejectreason.PaymentDepositReceiptRejectReason
import com.asaas.paymentdepositreceiptstatus.PaymentDepositReceiptStatus
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentDepositReceiptService {

	def paymentDepositReceiptFileService

	def messageService

	public void save(InvoiceDepositInfo invoiceDepositInfo) {
        String fileHash = Utils.buildHashWithSHA256(invoiceDepositInfo.depositReceipt.getFileBytes())

        Map parsedParams = [invoiceDepositInfo: invoiceDepositInfo,
                            payment: invoiceDepositInfo.payment,
                            bank: invoiceDepositInfo.bank,
                            billingType: invoiceDepositInfo.billingType,
                            name: invoiceDepositInfo.name,
                            cpfCnpj: invoiceDepositInfo.cpfCnpj,
                            duplicateFile: PaymentDepositReceiptFile.query([exists: true, fileHash: fileHash]).get().asBoolean()
                            ]

        PaymentDepositReceipt paymentDepositReceipt = new PaymentDepositReceipt()

        paymentDepositReceipt.properties = parsedParams
        paymentDepositReceipt.save(failOnError: true)

		paymentDepositReceiptFileService.save(paymentDepositReceipt, fileHash)
	}

    public PaymentDepositReceipt update(Long id, Map params) {
        Map parsedParams = parseParams(params)

        PaymentDepositReceipt paymentDepositReceipt = PaymentDepositReceipt.get(id)

        PaymentDepositReceipt validatedPaymentDepositReceipt = validateUpdate(parsedParams)
        if (validatedPaymentDepositReceipt.hasErrors()) return validatedPaymentDepositReceipt

        paymentDepositReceipt.properties = parsedParams
        paymentDepositReceipt.save(failOnError: true)

        return paymentDepositReceipt
    }

    public void reject(PaymentDepositReceipt paymentDepositReceipt, String reason) {
		if (!reason) {
			DomainUtils.addError(paymentDepositReceipt, "O motivo é obrigatório.")
			return
		}

		if (!paymentDepositReceipt.editAllowed()) {
			DomainUtils.addError(paymentDepositReceipt, "Não é possível rejeitar comprovantes com a situação diferente de aguardando análise ou aguardando conciliação.")
			return
		}

		paymentDepositReceipt.analysisDate = new Date()
		paymentDepositReceipt.analyst = UserUtils.getCurrentUser()
		paymentDepositReceipt.status = PaymentDepositReceiptStatus.DOCUMENT_REJECTED
		paymentDepositReceipt.rejectReason = PaymentDepositReceiptRejectReason.convert(reason)
		paymentDepositReceipt.save(flush: true, failOnError: true)

		messageService.sendTransferOrDepositRejectReasonToCustomerAccountIfPossible(paymentDepositReceipt)
		messageService.sendTransferOrDepositRejectReasonToCustomer(paymentDepositReceipt)

		return
	}

	public void setConciliatedValue(Long id, BigDecimal conciliatedValue) {
		PaymentDepositReceipt paymentDepositReceipt = PaymentDepositReceipt.get(id)

		if (paymentDepositReceipt.isConciliated()) return

		paymentDepositReceipt.conciliatedValue = conciliatedValue
		paymentDepositReceipt.save(failOnError: true)
	}

	public PaymentDepositReceipt setConciliatedValueToDefault(Long id) {
		PaymentDepositReceipt paymentDepositReceipt = PaymentDepositReceipt.get(id)

		if (paymentDepositReceipt.isConciliated()) return paymentDepositReceipt

		paymentDepositReceipt.conciliatedValue = paymentDepositReceipt.value
		paymentDepositReceipt.save(failOnError: true)

		return paymentDepositReceipt
	}

    public void cancelIfExists(Payment payment) {
        PaymentDepositReceipt paymentDepositReceipt = PaymentDepositReceipt.query([payment: payment]).get()
        if (!paymentDepositReceipt) return

        paymentDepositReceipt.status = PaymentDepositReceiptStatus.CANCELED
        paymentDepositReceipt.save(failOnError: true)
    }

    private PaymentDepositReceipt validateUpdate(Map parsedParams) {
        PaymentDepositReceipt paymentDepositReceipt = new PaymentDepositReceipt()

        if (!parsedParams.documentDate) DomainUtils.addError(paymentDepositReceipt, "Informe a data do comprovante.")
        if (parsedParams.documentDate > new Date()) DomainUtils.addError(paymentDepositReceipt, "Não é permitido incluir comprovantes com lançamentos futuros. Caso o comprovante tenha data maior que hoje o mesmo deve ser rejeitado.")

        if (!parsedParams.value) DomainUtils.addError(paymentDepositReceipt, "Informe o valor do comprovante.")

        return paymentDepositReceipt
    }

	private Map parseParams(Map params) {
        Map parsedDocumentNumberParams = DepositReceiptBuilder.parseDocumentNumberParams(params)

		Map parsedParams = [
			bank: Bank.ignoreAsaasBank([code: params.bankCode]).get(),
			billingType: BillingType.convert(params.billingType),
            documentDate: DepositReceiptBuilder.buildDocumentDate(params.bankCode, params.documentTime, params.documentDate),
			value: Utils.toBigDecimal(params.value),
			conciliatedValue: Utils.toBigDecimal(params.value),
			name: params.name,
			cpfCnpj: params.cpfCnpj,
            observations: params.observations,
			documentNumber: DepositReceiptBuilder.buildDocumentNumber(parsedDocumentNumberParams),
            terminal: params.terminal,
            transactionNumber: params.transactionNumber,
			agency: DepositReceiptBuilder.buildAgency(params.agency),
            agencyDigit: params.agencyDigit,
            account: DepositReceiptBuilder.buildAccount(params.account, params.bankCode),
            accountDigit: params.accountDigit,
			status: PaymentDepositReceiptStatus.AWAITING_CONCILIATION,
			analysisDate: new Date(),
			analyst: UserUtils.getCurrentUser()
        ]

		return parsedParams
	}
}
