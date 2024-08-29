package com.asaas.service.transactionreceipt

import com.asaas.converter.HtmlToPdfConverter
import com.asaas.domain.bill.Bill
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.installment.Installment
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transactionreceipt.TransactionReceipt
import com.asaas.domain.transactionreceipt.TransactionReceiptPaymentCreditCardPartialRefund
import com.asaas.domain.transactionreceipt.TransactionReceiptPixTransaction
import com.asaas.generatereceipt.TransactionReceiptOnDemandValidator
import com.asaas.transactionreceipt.TransactionReceiptType
import grails.transaction.Transactional

@Transactional
class TransactionReceiptService {

    def groovyPageRenderer
    def linkService
    def notificationDispatcherPaymentNotificationOutboxService
    def urlShortenerService

    public TransactionReceipt savePaymentReceived(Payment payment) {
    	return save(payment, TransactionReceiptType.PAYMENT_RECEIVED)
    }

    public TransactionReceipt savePaymentConfirmed(Payment payment) {
    	return save(payment, TransactionReceiptType.PAYMENT_CONFIRMED)
    }

    public TransactionReceipt savePaymentConfirmed(Installment installment) {
    	return save(installment, TransactionReceiptType.PAYMENT_CONFIRMED)
    }

    public TransactionReceipt savePaymentRefunded(Payment payment) {
    	return save(payment, TransactionReceiptType.PAYMENT_REFUNDED)
    }

    public TransactionReceipt savePaymentRefunded(Installment installment) {
    	return save(installment, TransactionReceiptType.PAYMENT_REFUNDED)
    }

    public TransactionReceipt savePaymentDeleted(Installment installment) {
        return save(installment, TransactionReceiptType.PAYMENT_DELETED)
    }

    public TransactionReceipt saveBillScheduled(Bill bill) {
    	return save(bill, TransactionReceiptType.BILL_SCHEDULED)
    }

    public TransactionReceipt saveBillPaid(Bill bill) {
    	return save(bill, TransactionReceiptType.BILL_PAID)
    }

    public TransactionReceipt saveTransferConfirmed(CreditTransferRequest transfer) {
        return save(transfer, TransactionReceiptType.TRANSFER_CONFIRMED)
    }

    public TransactionReceipt savePixTransactionDone(PixTransaction pixTransaction) {
        if (TransactionReceiptOnDemandValidator.isAllowedForPixTransaction(pixTransaction)) return null

        return save(pixTransaction, TransactionReceiptType.PIX_TRANSACTION_DONE)
    }

    public TransactionReceipt savePaymentCreditCardPartialRefund(PaymentRefund paymentRefund) {
        return save(paymentRefund, TransactionReceiptType.PAYMENT_CREDIT_CARD_PARTIAL_REFUND)
    }

    public TransactionReceipt save(InternalTransfer internalTransfer) {
        return save(internalTransfer, TransactionReceiptType.INTERNAL_TRANSFER_DONE)
    }

    public TransactionReceipt save(Object domainInstance, TransactionReceiptType type) {
    	TransactionReceipt transactionReceipt = new TransactionReceipt()

        if (shouldSaveDomainInstanceInTransactionReceipt(type)) transactionReceipt."${TransactionReceipt.getAttributeNameByObjectClass(domainInstance)}" = domainInstance

    	transactionReceipt.type = type
    	transactionReceipt.buildAndSetPublicId()

        if (type == TransactionReceiptType.PAYMENT_RECEIVED && domainInstance instanceof Payment && PaymentUndefinedBillingTypeConfig.equivalentToBoleto(domainInstance)) {
            transactionReceipt.transactionDate = domainInstance.clientPaymentDate
        } else if (type == TransactionReceiptType.TRANSFER_CONFIRMED && domainInstance instanceof CreditTransferRequest) {
            transactionReceipt.transactionDate = domainInstance.getDebitDate()
        } else if (type.isInternalTransferDone()) {
            transactionReceipt.transactionDate = domainInstance.transferDate
        } else if (type.isPixTransactionDone()) {
            transactionReceipt.transactionDate = (domainInstance as PixTransaction).effectiveDate
        } else {
            transactionReceipt.transactionDate = new Date().clearTime()
        }
    	transactionReceipt.save(failOnError: true)

        if (type.isPixTransactionDone()) {
            transactionReceipt.transactionReceiptPixTransaction = saveTransactionReceiptPixTransaction(transactionReceipt, domainInstance)
            transactionReceipt.save(failOnError: true)
        }

        if (type.isPaymentCreditCardPartialRefund()) saveTransactionReceiptCreditCardPartialRefund(transactionReceipt, domainInstance)

        notificationDispatcherPaymentNotificationOutboxService.saveTransactionReceiptCreatedIfNecessary(transactionReceipt)

        return transactionReceipt
    }

    public byte[] buildTransactionReceiptFile(TransactionReceipt transactionReceipt) {
        Map transactionMapInfo = [
            transactionReceipt: transactionReceipt,
            provider: transactionReceipt.getTransactionCustomer(),
            pixTransaction: transactionReceipt.getPixTransaction()
        ]

        String htmlString = groovyPageRenderer.render(template: "/transactionReceipt/pdf", model: transactionMapInfo).decodeHTML()

        return HtmlToPdfConverter.asBytes(htmlString)
    }

    private TransactionReceiptPixTransaction saveTransactionReceiptPixTransaction(TransactionReceipt transactionReceipt, Object domainInstance) {
        TransactionReceiptPixTransaction transactionReceiptPixTransaction = new TransactionReceiptPixTransaction()
        transactionReceiptPixTransaction.pixTransaction = domainInstance
        transactionReceiptPixTransaction.transactionReceipt = transactionReceipt
        transactionReceiptPixTransaction.save(failOnError: true)
        return transactionReceiptPixTransaction
    }

    private TransactionReceiptPaymentCreditCardPartialRefund saveTransactionReceiptCreditCardPartialRefund(TransactionReceipt transactionReceipt, Object domainInstance) {
        TransactionReceiptPaymentCreditCardPartialRefund transactionReceiptPaymentCreditCardPartialRefund = new TransactionReceiptPaymentCreditCardPartialRefund()
        transactionReceiptPaymentCreditCardPartialRefund.transactionReceipt = transactionReceipt
        transactionReceiptPaymentCreditCardPartialRefund.paymentRefund = domainInstance
        transactionReceiptPaymentCreditCardPartialRefund.save(failOnError: true)

        return transactionReceiptPaymentCreditCardPartialRefund
    }

    private Boolean shouldSaveDomainInstanceInTransactionReceipt(TransactionReceiptType type) {
        if (type.isPixTransactionDone()) return false
        if (type.isPaymentCreditCardPartialRefund()) return false

        return true
    }
}
