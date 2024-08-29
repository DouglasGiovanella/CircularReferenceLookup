package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDocument
import com.asaas.domain.payment.PaymentDocumentType
import com.asaas.exception.BusinessException
import com.asaas.file.FileValidator
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentDocumentService {

    def fileService

    public PaymentDocument save(Payment payment, Map params) {
        PaymentDocument paymentDocument = validateSaveParams(payment, params)

        if (paymentDocument.hasErrors()) {
            return paymentDocument
        }

        paymentDocument = new PaymentDocument()
        paymentDocument.payment = payment
        paymentDocument.file = fileService.saveFile(payment.provider, params.documentFile ?: null, params.temporaryFile ?: null)
        paymentDocument.type = params.type ?: PaymentDocumentType.OTHER
        paymentDocument.availableAfterPayment = Boolean.valueOf(params.availableAfterPayment)
        paymentDocument.publicId = UUID.randomUUID()

        paymentDocument.save(failOnError: true, flush: true)

        return paymentDocument
    }

    public void save(Payment payment, List<Map> documents) {
        documents.each { document ->
            TemporaryFile temporaryFile = TemporaryFile.findByPublicId(document.temporaryPublicId)
            document.temporaryFile = temporaryFile
            PaymentDocument paymentDocument = save(payment, document)
            if (paymentDocument.hasErrors()) {
                throw new Exception("Erro ao salvar o paymentDocument: ${document} ---> Erro: ${paymentDocument.errors}")
            }
        }
    }

    public List<PaymentDocument> list(Payment payment) {
        List<PaymentDocument> documents = PaymentDocument.query([payment: payment]).list()
        return documents
    }

    public void delete(Customer customer, documentId) {
        PaymentDocument paymentDocument = PaymentDocument.find(documentId, customer.id)
        paymentDocument.deleted = true
        paymentDocument.save(failOnError: true)
    }

    public PaymentDocument update(Customer customer, documentId, Map params) {
        PaymentDocument paymentDocument = PaymentDocument.find(documentId, customer.id)

        if (params.name) {
            paymentDocument.file.originalName = params.name
        }

        if (params.type) {
            paymentDocument.type = params.type
        }

        if (!Utils.isEmptyOrNull(params.availableAfterPayment)) {
            paymentDocument.availableAfterPayment = Boolean.valueOf(params.availableAfterPayment)
        }

        paymentDocument.save(failOnError: true)
        return paymentDocument
    }

    public byte[] groupedDownload(String externalToken, Map params) {
        Payment payment = Payment.findByExternalToken(externalToken)
        if (Boolean.valueOf(params.availableAfterPayment) && !payment.canDocumentsAvailableAfterPaymentBeDownloaded()) {
            AsaasLogger.info("Documentos não podem ser baixados. Params: ${params}, Payment: ${payment}")
            throw new BusinessException("Não foi possível realizar o download dos documentos.")
        }

        List<PaymentDocument> documents = PaymentDocument.query([payment: payment, availableAfterPayment: params.availableAfterPayment, type: params.type]).list()

        List<AsaasFile> files = documents.collect { it.file }

        return FileUtils.buildZip(files)
    }

    public PaymentDocument validateSaveParams(Payment payment, Map params) {
        PaymentDocument paymentDocument = new PaymentDocument()
        if (!params.documentFile && !params.temporaryFile) {
            DomainUtils.addFieldError(paymentDocument, "file", "required")
            return paymentDocument
        }

        String fileName = params.documentFile ? params.documentFile.getOriginalFilename() : params.temporaryFile.originalName

        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(params.documentFile, params.temporaryFile, PaymentDocument.MAX_FILE_SIZE)
        if (!isValid) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(fileValidator, paymentDocument)
            return paymentDocument
        }

        if (PaymentDocument.query([payment: payment, originalFileName: fileName, column: 'id']).get()) {
            DomainUtils.addFieldError(paymentDocument, "file", "alreadyExists", fileName)
        }

        return paymentDocument
    }
}
