package com.asaas.service.paymentdepositreceipt

import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceipt
import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceiptFile

import grails.transaction.Transactional

@Transactional
class PaymentDepositReceiptFileService {

	public void save(PaymentDepositReceipt paymentDepositReceipt, String fileHash) {
        PaymentDepositReceiptFile paymentDepositReceiptFile = new PaymentDepositReceiptFile()

        paymentDepositReceiptFile.file = paymentDepositReceipt.invoiceDepositInfo.depositReceipt
        paymentDepositReceiptFile.paymentDepositReceipt = paymentDepositReceipt
        paymentDepositReceiptFile.fileHash = fileHash

        paymentDepositReceiptFile.save(failOnError: true)  
	}
}

