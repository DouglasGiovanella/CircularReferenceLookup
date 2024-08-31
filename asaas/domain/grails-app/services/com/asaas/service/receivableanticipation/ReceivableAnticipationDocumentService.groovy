package com.asaas.service.receivableanticipation

import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationDocument
import com.asaas.file.FileValidator
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentType
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentVO
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import org.apache.commons.io.FilenameUtils

@Transactional
class ReceivableAnticipationDocumentService {

	def fileService

    public ReceivableAnticipationDocument validateDocument(Customer customer, ReceivableAnticipationDocumentVO documentVO) {
        ReceivableAnticipationDocument validatedReceivableAnticipationDocument = new ReceivableAnticipationDocument()

        TemporaryFile temporaryFile = documentVO.temporaryFileId ? TemporaryFile.find(customer, documentVO.temporaryFileId) : null

        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(documentVO.file, temporaryFile, ReceivableAnticipationDocument.MAX_FILE_SIZE)
        if (!isValid) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(fileValidator, validatedReceivableAnticipationDocument)
            return validatedReceivableAnticipationDocument
        }

        String originalName = temporaryFile ? temporaryFile.originalName : documentVO.file.getOriginalFilename()
        String fileExtension = FilenameUtils.getExtension(originalName).toLowerCase()
        if (!ReceivableAnticipationDocument.ACCEPTED_FILE_TYPES.contains(fileExtension)) {
            DomainUtils.addError(validatedReceivableAnticipationDocument, "Formato de arquivo inválido. São aceitos apenas arquivos no formato: ${ReceivableAnticipationDocument.ACCEPTED_FILE_TYPES.join(", ")}")
        }

        return validatedReceivableAnticipationDocument
    }

	public void saveDocuments(Customer customer, ReceivableAnticipation receivableAnticipation, List<ReceivableAnticipationDocumentVO> listOfReceivableAnticipationDocumentVO) {
        for (ReceivableAnticipationDocumentVO receivableAnticipationDocumentVO : listOfReceivableAnticipationDocumentVO) {
			AsaasFile asaasFile

			if (receivableAnticipationDocumentVO.temporaryFileId) {
				asaasFile = fileService.saveDocumentFromTemporary(customer, receivableAnticipationDocumentVO.temporaryFileId, [deleteTemporaryFile: false])
			} else {
				asaasFile = fileService.saveDocument(customer, receivableAnticipationDocumentVO.file, null)
			}

            save(receivableAnticipation, asaasFile)
        }
    }

    public ReceivableAnticipationDocument save(ReceivableAnticipation receivableAnticipation, AsaasFile asaasFile) {
    	ReceivableAnticipationDocument receivableAnticipationDocument = new ReceivableAnticipationDocument()
        receivableAnticipationDocument.receivableAnticipation = receivableAnticipation
        receivableAnticipationDocument.file = asaasFile
        receivableAnticipationDocument.save(flush: true, failOnError: true)

        return receivableAnticipationDocument
    }

	public ReceivableAnticipationDocument find(Customer customer, String filePublicId) {
		String hql = "from ReceivableAnticipationDocument rad where rad.receivableAnticipation.customer = :customer and rad.file.publicId = :publicId"
		ReceivableAnticipationDocument receivableAnticipationDocument = ReceivableAnticipationDocument.executeQuery(hql, [customer: customer, publicId: filePublicId])[0]

		return receivableAnticipationDocument
	}

	public ReceivableAnticipationDocument findAdmin(String filePublicId) {
		String hql = "from ReceivableAnticipationDocument rad where rad.file.publicId = :publicId"
		ReceivableAnticipationDocument receivableAnticipationDocument = ReceivableAnticipationDocument.executeQuery(hql, [publicId: filePublicId])[0]

		return receivableAnticipationDocument
	}

	public byte[] readBytes(String filePublicId) {
		AsaasFile file = fileService.findByPublicId(filePublicId)

        return file?.getDocumentBytes()
	}

    public void setAsaasIssuedInvoiceAsAnticipationDocument(ReceivableAnticipation anticipation) {
        Invoice invoice = anticipation.payment.getAuthorizedInvoice()
        if (!invoice) throw new RuntimeException("Não foi possível localizar a nota fiscal da antecipação [${anticipation.id}].")

        File file = invoice.pdfFile.getFileInMemory()
        AsaasFile asaasFile = fileService.createDocument(anticipation.customer, file, invoice.pdfFile.originalName)

        ReceivableAnticipationDocument anticipationDocument = save(anticipation, asaasFile)

        anticipationDocument.type = ReceivableAnticipationDocumentType.NFSE
        anticipationDocument.save(failOnError: true)
    }
}
