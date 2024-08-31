package com.asaas.service.paymentdunning

import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.PaymentDunningDocument
import com.asaas.paymentdunning.PaymentDunningDocumentStatus
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class PaymentDunningDocumentService {

    def fileService

    public void delete(PaymentDunning paymentDunning) {
        List<PaymentDunningDocument> paymentDunningDocumentList = PaymentDunningDocument.query([paymentDunning: paymentDunning]).list()

        for (PaymentDunningDocument document in paymentDunningDocumentList) {
            document.deleted = true
            document.save(failOnError: true)
        }
    }

    public void setAsApproved(PaymentDunning paymentDunning) {
        List<PaymentDunningDocument> paymentDunningDocumentList = PaymentDunningDocument.query([paymentDunning: paymentDunning]).list()

        for (PaymentDunningDocument document in paymentDunningDocumentList) {
            document.status = PaymentDunningDocumentStatus.APPROVED
            document.save(failOnError: true)
        }
    }

    public void setAsDenied(PaymentDunning paymentDunning) {
        List<PaymentDunningDocument> paymentDunningDocumentList = PaymentDunningDocument.query([paymentDunning: paymentDunning]).list()

        for (PaymentDunningDocument document in paymentDunningDocumentList) {
            document.status = PaymentDunningDocumentStatus.DENIED
            document.save(failOnError: true)
        }
    }

    public PaymentDunningDocument save(PaymentDunning paymentDunning, Long temporaryFileId) {
        PaymentDunningDocument validatedPaymentDunningDocument = validateDocument(paymentDunning.customer, temporaryFileId)
        if (validatedPaymentDunningDocument.hasErrors()) {
            return validatedPaymentDunningDocument
        }

        AsaasFile asaasFile = fileService.saveDocumentFromTemporary(paymentDunning.customer, temporaryFileId, [deleteTemporaryFile: true])

        PaymentDunningDocument document = new PaymentDunningDocument()
        document.paymentDunning = paymentDunning
        document.file = asaasFile
        document.status = PaymentDunningDocumentStatus.PENDING
        document.save(failOnError: true)

        return document
    }

    private PaymentDunningDocument validateDocument(Customer customer, Long temporaryFileId) {
        PaymentDunningDocument validatePaymentDunningDocument = new PaymentDunningDocument()

        TemporaryFile temporaryFile = TemporaryFile.find(customer, temporaryFileId)

        if (!AsaasFile.DEFAULT_ACCEPTED_DOCUMENTS_TYPES.contains(temporaryFile.getExtension().toLowerCase())) {
            DomainUtils.addError(validatePaymentDunningDocument, "Formato de arquivo inválido. São aceitos apenas arquivos no formato: ${AsaasFile.DEFAULT_ACCEPTED_DOCUMENTS_TYPES.join(", ")}")
        }

        return validatePaymentDunningDocument
    }
}
