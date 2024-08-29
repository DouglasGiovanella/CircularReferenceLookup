package com.asaas.service.receivedpaymentbatchfile

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.ReceivedPaymentBatchFile
import com.asaas.domain.payment.ReceivedPaymentBatchFileItem
import com.asaas.payment.ReceivedPaymentBatchFileItemStatus
import com.asaas.utils.MoneyUtils

import java.text.SimpleDateFormat

import org.apache.commons.lang.RandomStringUtils

import grails.transaction.Transactional

@Transactional
class ReceivedPaymentBatchFileService {

	public ReceivedPaymentBatchFile createBatchFile(String fileName, List<Long> paymentIdList, CreditTransferRequest creditTransferRequest) {
		ReceivedPaymentBatchFile receivedPaymentBatchFile = new ReceivedPaymentBatchFile()
		receivedPaymentBatchFile.batchFileName = fileName
		receivedPaymentBatchFile.creditTransferRequest = creditTransferRequest
		receivedPaymentBatchFile.externalToken = buildExternalToken()
		receivedPaymentBatchFile.save(flush: true, failOnError: true)

		associateReceivedPaymentWithBathFile(paymentIdList, receivedPaymentBatchFile, ReceivedPaymentBatchFileItemStatus.PROCESSED)

		if (CustomerParameter.getStringValue(creditTransferRequest.provider, CustomerParameterName.RECEIVED_PAYMENT_BATCH_FILE_LAYOUT) == "A") {
			receivedPaymentBatchFile.fileContents = buildFileContentsLayoutA(receivedPaymentBatchFile)
		}

		return receivedPaymentBatchFile
	}

	public void associateReceivedPaymentWithBathFile(List<Long> paymentIdList, ReceivedPaymentBatchFile receivedPaymentBatchFile, ReceivedPaymentBatchFileItemStatus status) {
		for (Long paymentId in paymentIdList) {
			ReceivedPaymentBatchFileItem receivedPaymentBatchFileItem = new ReceivedPaymentBatchFileItem()
			receivedPaymentBatchFileItem.payment = Payment.get(paymentId)
			receivedPaymentBatchFileItem.receivedPaymentBatchFile = receivedPaymentBatchFile
			receivedPaymentBatchFileItem.status = status
			receivedPaymentBatchFileItem.save(flush: true, failOnError: true)

			receivedPaymentBatchFile.addToItems(receivedPaymentBatchFileItem)
		}
	}

	public String buildFileContentsLayoutA(ReceivedPaymentBatchFile batchFile) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMyyyy")

		StringBuilder fileContents = new StringBuilder()

		for (ReceivedPaymentBatchFileItem item in batchFile.items) {
			fileContents.append(item.payment.publicId.padRight(20))
			fileContents.append(item.payment.externalReference ? item.payment.externalReference.padRight(20) : "".padRight(20))
			fileContents.append(sdf.format(item.payment.paymentDate))
			fileContents.append(sdf.format(item.payment.clientPaymentDate))
			fileContents.append(sdf.format(item.receivedPaymentBatchFile.creditTransferRequest.dateCreated))
			fileContents.append(String.valueOf(MoneyUtils.valueInCents(item.payment.value)).padLeft(10, "0"))
			fileContents.append(String.valueOf(MoneyUtils.valueInCents(item.payment.netValue)).padLeft(10, "0"))
			fileContents.append("\n")
		}

		return fileContents.toString()
	}

	public ReceivedPaymentBatchFile saveFileContents(ReceivedPaymentBatchFile receivedPaymentBatchFile, String fileContents) {
		receivedPaymentBatchFile.fileContents = fileContents
		receivedPaymentBatchFile.save(flush: true, failOnError: true)

		return receivedPaymentBatchFile
	}

	public void delete(CreditTransferRequest transfer) {
		ReceivedPaymentBatchFile file = transfer.getReceivedPaymentBatchFile()
		if (!file) return

		file.deleted = true
		file.save(failOnError: true)

		ReceivedPaymentBatchFileItem.executeUpdate("update ReceivedPaymentBatchFileItem set deleted = true where receivedPaymentBatchFile = :file", [file: file])
	}

	private String buildExternalToken() {
        String token = RandomStringUtils.randomNumeric(12)

        if (externalTokenAlreadyExists(token)) {
            return buildExternalToken()
        } else {
            return token
        }
    }

    private Boolean externalTokenAlreadyExists(String token) {
        ReceivedPaymentBatchFile.withSession { session ->
            def query = session.createSQLQuery("select count(1) from received_payment_batch_file where external_token = :token")
            query.setString("token", token)

            return query.list().get(0) > 0
        }
    }

}
