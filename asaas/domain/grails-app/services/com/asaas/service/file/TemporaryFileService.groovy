package com.asaas.service.file

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.picture.Picture
import com.asaas.file.FileValidator
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class TemporaryFileService {

	public TemporaryFile save(CommonsMultipartFile file) {
		TemporaryFile temporaryFile = validateSaveParams(file)
		 if (temporaryFile.hasErrors()) {
            return temporaryFile
        }

		save(null, file, false)
    }

    public TemporaryFile save(Customer customer, CommonsMultipartFile file, Boolean buildPreview) {
        final Long maxFileSize = 209715200
		TemporaryFile tempFile

		tempFile = new TemporaryFile(customer: customer, originalName: file.getOriginalFilename(), size: file.getSize(), publicId: TemporaryFile.buildPublicId())
		tempFile.save(flush: true)

        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(file, tempFile, maxFileSize)
        if (!isValid) {
            String errors = fileValidator.errors.collect { it.getMessage() }.join(" - ")
            AsaasLogger.warn("TemporaryFileService.save >> Erro ao validar arquivo temporario. Errors [${errors}] | Enviado por: [${RequestUtils.getCleanReferer()}] | Customer [${customer?.id}] | Arquivo [$tempFile.originalName].", new Throwable())
            throw new BusinessException("Arquivo inválido: ${errors}")
        }

		File temporaryDiskFile = File.createTempFile(tempFile.originalName ?: "temp", ".tmp")
		file.transferTo(temporaryDiskFile)

		tempFile.writeFile(temporaryDiskFile)

        AsaasLogger.info("TemporaryFileService.save >> [CustomerId: ${customer?.id}] [TemporaryFileId: ${tempFile.id}]")

		if (buildPreview) this.buildPreview(customer, tempFile.id)

		return tempFile
    }

	public TemporaryFile validateSaveParams(CommonsMultipartFile file) {
        TemporaryFile temporaryFile = new TemporaryFile()

        String fileName = file?.getOriginalFilename()

        if (!file) {
            DomainUtils.addFieldError(temporaryFile, "file", "required")
        }

        if (file?.getSize() > TemporaryFile.MAX_WIZARD_FILE_SIZE) {
        	Object[] errorArgs = [fileName, TemporaryFile.MAX_WIZARD_FILE_SIZE / TemporaryFile.ONE_MEGABYTE]
            DomainUtils.addFieldError(temporaryFile, "file", "maxSize.exceeded", errorArgs)
        }

        return temporaryFile
    }

	public byte[] buildPreview(Customer customer, Long id) {
		TemporaryFile tempFile = find(customer, id)

		if (!tempFile) return null

		if (tempFile.extension.toLowerCase() in ["png", "jpg", "jpeg", "gif"]) {
			try {
				File temporaryThumbDiskFile = tempFile.getFileThumb()
				if (temporaryThumbDiskFile) return temporaryThumbDiskFile.readBytes()

				File newThumbFile = AsaasApplicationHolder.applicationContext.pictureService.resize(tempFile.getFile(), Picture.MICRO_IMAGE_SIZE, tempFile.extension)
				tempFile.writeFileThumb(newThumbFile)
				return newThumbFile.readBytes()
			} catch (Exception e) {
				return this.class.classLoader.getResourceAsStream("defaultThumbs/file.png").getBytes()
			}
		} else if (tempFile.extension.toLowerCase() == "pdf") {
			return this.class.classLoader.getResourceAsStream("defaultThumbs/pdf.png").getBytes()
		} else {
			return this.class.classLoader.getResourceAsStream("defaultThumbs/file.png").getBytes()
		}
	}

	public void removeFile(TemporaryFile temporaryFile) {
		temporaryFile.deleteFile()
		temporaryFile.deleteFileThumb()
	}

	public Boolean remove(Customer customer, Long id) {
		TemporaryFile temporaryFile = find(customer, id)

		remove(temporaryFile)
	}

	public Boolean removeByPublicId(String publicId) {
		TemporaryFile temporaryFile = TemporaryFile.findByPublicId(publicId)

		remove(temporaryFile)
	}

	public Boolean remove(TemporaryFile temporaryFile) {
		if (temporaryFile) {
			removeFile(temporaryFile)

			temporaryFile.delete()
			return true
		}

		return false
	}

	public TemporaryFile find(Customer customer, Long id) {
		return TemporaryFile.findWhere(customer: customer, id: id)
	}
}
