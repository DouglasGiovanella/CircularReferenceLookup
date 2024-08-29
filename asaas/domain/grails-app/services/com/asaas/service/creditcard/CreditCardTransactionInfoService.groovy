package com.asaas.service.creditcard

import com.asaas.creditcard.CapturedCreditCardTransactionVo
import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.payment.Payment

import grails.transaction.Transactional

@Transactional
class CreditCardTransactionInfoService {

	public CreditCardTransactionInfo save(Payment payment, Integer installmentNumber, CapturedCreditCardTransactionVo transactionVo) {
        CreditCardTransactionInfo transactionInfo = CreditCardTransactionInfo.query([paymentId: payment.id]).get()
        if (!transactionInfo) {
            transactionInfo = new CreditCardTransactionInfo()
        }

		transactionInfo.payment = payment
		transactionInfo.installment = payment.installment
		transactionInfo.installmentNumber = installmentNumber
		transactionInfo.transactionIdentifier = transactionVo.transactionIdentifier
		transactionInfo.uniqueSequentialNumber = transactionVo.uniqueSequentialNumber
		transactionInfo.acquirer = transactionVo.acquirer
		transactionInfo.gateway = transactionVo.gateway
		transactionInfo.mcc = transactionVo.mcc
        transactionInfo.acquirerFee = transactionVo.acquirerFee
        transactionInfo.save(failOnError: true)

		return transactionInfo
	}

    public CreditCardTransactionInfo updateAcquirerInfo(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        CreditCardTransactionInfo creditCardTransactionInfo = CreditCardTransactionInfo.query([paymentId: creditCardAcquirerOperation.payment.id]).get()
        creditCardTransactionInfo.creditDate = creditCardAcquirerOperation.operationDate
        creditCardTransactionInfo.save(failOnError: true)
    }
}
