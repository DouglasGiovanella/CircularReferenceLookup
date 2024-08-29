package com.asaas.service.paymentcampaign

import com.asaas.domain.customer.Customer
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.domain.paymentcampaign.PaymentCampaignFile
import com.asaas.file.FileValidator
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

import org.apache.commons.io.FilenameUtils

@Transactional
class PaymentCampaignFileService {

	def pictureService
	def fileService

	public PaymentCampaignFile save(PaymentCampaign paymentCampaign, Map params) {
		if (!paymentCampaign) return
		if (paymentCampaign.deleted) DomainUtils.addError(paymentCampaign, "Não é possível incluir uma imagem em um link de pagamento removida.")
		PaymentCampaignFile paymentCampaignFile = new PaymentCampaignFile()
		validate(paymentCampaign, paymentCampaignFile, params)
		if (paymentCampaignFile.hasErrors()) {
			return paymentCampaignFile
		}

        paymentCampaignFile.publicId = UUID.randomUUID()
		paymentCampaignFile.paymentCampaign = paymentCampaign
		paymentCampaignFile.picture = pictureService.save(paymentCampaign.customer, params.temporaryFile, params.file)
		paymentCampaignFile.save(failOnError: true, flush: true)

		if (params.main || !paymentCampaign.hasMainCampaignFile()) {
			paymentCampaignFile = setAsMainFile(paymentCampaign.customer, paymentCampaign, paymentCampaignFile.id)
		}

		return paymentCampaignFile
	}

	public PaymentCampaignFile setAsMainFile(Customer customer, PaymentCampaign paymentCampaign, fileId) {
		PaymentCampaignFile file = PaymentCampaignFile.find(customer, fileId)
		if (file.main == true) return file

		PaymentCampaignFile mainFile = PaymentCampaignFile.query([paymentCampaign: paymentCampaign, main: true]).get()
		if (mainFile) {
			mainFile.main = false
			mainFile.save(failOnError: true, flush: true)
		}

		file.main = true
		file.save(failOnError: true, flush: true)

		return file
	}

	public void saveList(PaymentCampaign paymentCampaign, List<Map> fileList) {
		fileList.each { file ->
			TemporaryFile temporaryFile = file.tempFilePublicId ? TemporaryFile.findByPublicId(file.tempFilePublicId) : TemporaryFile.find(paymentCampaign.customer, file.tempFileId)
			file.temporaryFile = temporaryFile
			PaymentCampaignFile paymentCampaignFile = save(paymentCampaign, file)
			if (paymentCampaignFile.hasErrors()) {
                throw new ValidationException(null, paymentCampaignFile.errors)
			}
		}
	}

	public PaymentCampaignFile validate(PaymentCampaign paymentCampaign, PaymentCampaignFile paymentCampaignFile, Map params) {
		List<PaymentCampaignFile> files = PaymentCampaignFile.query([paymentCampaign: paymentCampaign]).list()
		if (files?.size() >= PaymentCampaign.MAX_FILE_QUANTITY) {
            DomainUtils.addError(paymentCampaignFile, Utils.getMessageProperty('paymentCampaign.limite.items', [PaymentCampaign.MAX_FILE_QUANTITY]))
		}

        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(params.file, params.temporaryFile, PaymentCampaignFile.MAX_FILE_SIZE)
        if (!isValid) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(fileValidator, paymentCampaignFile)

			return paymentCampaignFile
        }

        String fileName = params.file ? params.file.getOriginalFilename() : params.temporaryFile.originalName
        String extension = FilenameUtils.getExtension(fileName)
        if (!PaymentCampaignFile.ALLOWED_EXTENSIONS.contains(extension?.toLowerCase())) {
            DomainUtils.addError(paymentCampaignFile, "Arquivos do tipo ${extension} não são permitidos")
        }

		return paymentCampaignFile
	}

	public PaymentCampaignFile delete(Customer customer, fileId) {
		PaymentCampaignFile file = PaymentCampaignFile.find(customer, fileId)
		file.deleted = true
		file.save(failOnError: true)

		return file
	}
}
