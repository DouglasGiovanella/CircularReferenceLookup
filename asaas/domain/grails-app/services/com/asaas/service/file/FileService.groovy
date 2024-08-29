package com.asaas.service.file

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.exception.BusinessException
import com.asaas.file.FileManager
import com.asaas.file.FileManagerFactory
import com.asaas.file.FileManagerType
import com.asaas.file.FileValidator
import com.asaas.log.AsaasLogger
import com.asaas.utils.FileUtils
import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class FileService {

	def temporaryFileService

	public AsaasFile saveFile(Customer customer, CommonsMultipartFile file, TemporaryFile temporaryFile) {
        String newFileOriginalName = temporaryFile?.originalName ?: file?.getOriginalFilename()
		Long newFileSize = temporaryFile?.size != null ? temporaryFile.size : file.getSize()

        validateFile(file, temporaryFile, customer.id, newFileOriginalName)

		AsaasFile asaasFile = new AsaasFile(customer: customer, originalName: newFileOriginalName, size: newFileSize, publicId: buildPublicId())
		asaasFile.save(failOnError: true)

        if (temporaryFile) {
            asaasFile.writeFile(temporaryFile.getFile())
            File temporaryDiskThumbFile = temporaryFile.getFileThumb()
            if (temporaryDiskThumbFile) asaasFile.writeFileThumb(temporaryDiskThumbFile)
        } else {
            FileUtils.withDeletableTempFile(file, { File tempFile -> asaasFile.writeFile(tempFile) })
        }

		return asaasFile
	}

	public AsaasFile saveDocument(Customer customer, CommonsMultipartFile file, TemporaryFile temporaryFile) {
		String newFileOriginalName = temporaryFile?.originalName ?: file?.getOriginalFilename()
		Long newFileSize = temporaryFile?.size != null ? temporaryFile.size : file.getSize()

        validateFile(file, temporaryFile, customer.id, newFileOriginalName)

        AsaasFile asaasFile = new AsaasFile(customer: customer, originalName: newFileOriginalName, size: newFileSize, publicId: buildPublicId())
		asaasFile.save(failOnError: true)

        if (temporaryFile) {
            asaasFile.writeDocument(temporaryFile.getFile())
            File temporaryDiskThumbFile = temporaryFile.getFileThumb()
            if (temporaryDiskThumbFile) asaasFile.writeDocumentThumb(temporaryDiskThumbFile)
        } else {
            FileUtils.withDeletableTempFile(file, { File tempFile -> asaasFile.writeDocument(tempFile) })
        }

		return asaasFile
	}

	public AsaasFile createFile(Customer customer, File file, String name) {
		AsaasFile asaasFile = new AsaasFile(customer: customer, originalName: name, size: file.length(), publicId: buildPublicId())
		asaasFile.save(failOnError: true)
		asaasFile.writeFile(file)
		return asaasFile
	}

    public AsaasFile createDocument(Customer customer, File file, String name) {
        AsaasFile asaasFile = new AsaasFile(customer: customer, originalName: name, size: file.length(), publicId: buildPublicId())

        asaasFile.save(failOnError: true)
        asaasFile.writeDocument(file)
        return asaasFile
    }

	public File getDiskFile(TemporaryFile temporaryFile, CommonsMultipartFile file) {
		if (temporaryFile) return temporaryFile.getFile()

		File diskFile = File.createTempFile("temp", ".tmp")
		file.transferTo(diskFile)

		return diskFile
	}

	public AsaasFile saveFileFromTemporary(Customer customer, Long temporaryFileId) {
		TemporaryFile temporaryFile = TemporaryFile.find(customer, temporaryFileId)
        return saveFileFromTemporary(customer, temporaryFile)
	}

    public AsaasFile saveFileFromTemporary(Customer customer, TemporaryFile temporaryFile) {
        temporaryFile.asaasFile = saveFile(customer, null, temporaryFile)
        temporaryFile.save(failOnError: true)

        removeTemporaryFile(temporaryFile)

        return temporaryFile.asaasFile
    }

	public AsaasFile saveDocumentFromTemporary(Customer customer, Long temporaryFileId, Map options) {
		TemporaryFile temporaryFile = TemporaryFile.find(customer, temporaryFileId)

        AsaasLogger.info("fileService.saveDocumentFromTemporary >> [CustomerId: ${customer.id}] [temporaryFileId: ${temporaryFileId}] [temporaryFile: ${temporaryFile?.id}]")

		temporaryFile.asaasFile = saveDocument(customer, null, temporaryFile)
		temporaryFile.save(failOnError: true)

		if (options.deleteTemporaryFile) removeTemporaryFile(temporaryFile)

		return temporaryFile.asaasFile
	}

    public AsaasFile saveAsaasFileCopy(Customer customer, AsaasFile originAsaasFile, String originDirectory, String destinationDirectory) {
        AsaasFile asaasFile = new AsaasFile(customer: customer, originalName: originAsaasFile.originalName, size: originAsaasFile.size, publicId: buildPublicId())
        asaasFile.save(failOnError: true)
        asaasFile.copyFile(originAsaasFile.buildFileFullPath(originDirectory), destinationDirectory)

        return asaasFile
    }

	public void removeTemporaryFileList(Customer customer, List<Long> temporaryFileIdList) {
		if (!temporaryFileIdList) return

		for (Long temporaryFileId : temporaryFileIdList) {
			TemporaryFile temporaryFile = TemporaryFile.find(customer, temporaryFileId)
			removeTemporaryFile(temporaryFile)
		}
	}

	public void removeTemporaryFile(TemporaryFile temporaryFile) {
		temporaryFile.deleted = true
		temporaryFile.save(failOnError: true)

		temporaryFileService.removeFile(temporaryFile)
	}

    public File getHeimdallFile(String path) {
        FileManager fileManager = FileManagerFactory.getFileManager(FileManagerType.HEIMDALL, path)
        File file = fileManager.read()
        return file
    }

	private String buildPublicId() {
		String token = RandomStringUtils.randomAlphanumeric(64)

		if (publicIdAlreadyExists(token)) {
			return buildPublicId()
		} else {
			return token
		}
	}

	private Boolean publicIdAlreadyExists(String token) {
		def query = AsaasApplicationHolder.applicationContext.sessionFactory.currentSession.createSQLQuery("select count(id) from asaas_file where public_id = :token")
		query.setString("token", token)

		return query.list().get(0)
	}

	public byte[] buildFilePreview(Long id) {
		AsaasFile asaasFile = AsaasFile.findWhere(id: id)
		if (!asaasFile) return null
		return getFilePreviewBytes(asaasFile)
	}

	public byte[] buildDocumentPreview(Long id) {
		AsaasFile asaasFile = AsaasFile.findWhere(id: id)
		if (!asaasFile) return null
		return getDocumentPreviewBytes(asaasFile)
	}

	public byte[] getFilePreviewBytes(AsaasFile asaasFile) {
		byte[] file

		if (asaasFile.extension.toLowerCase() in ["png", "jpg", "jpeg", "gif"]) file = asaasFile.getFileThumb()?.readBytes()

		return file ?: getDefaultPreviewBytes(asaasFile.extension)
	}

	public byte[] getDocumentPreviewBytes(AsaasFile asaasFile) {
		byte[] file

		if (asaasFile.extension.toLowerCase() in ["png", "jpg", "jpeg", "gif"]) file = asaasFile.getDocumentThumb()?.readBytes()

		return file ?: getDefaultPreviewBytes(asaasFile.extension)
	}

	private byte[] getDefaultPreviewBytes(String fileExtension) {
		if (fileExtension.toLowerCase() == "pdf") return this.class.classLoader.getResourceAsStream("defaultThumbs/pdf.png").getBytes()

		return this.class.classLoader.getResourceAsStream("defaultThumbs/file.png").getBytes()
	}

	public AsaasFile findByPublicId(String publicId) {
		if (!publicId) return null

		return AsaasFile.executeQuery("from AsaasFile where publicId = :publicId", [publicId: publicId])[0]
	}

	public AsaasFile createFile(String fileName, String fileContent) {
        return createFile(fileName, fileContent, 'UTF-8')
    }

    public AsaasFile createFile(String fileName, String fileContent, String charsetName) {
		AsaasFile file = createEmpty(fileName)

        FileUtils.withDeletableTempFile(fileContent, charsetName, { File tempFile -> file.writeFile(tempFile) })

		file.size = fileContent.size()
		file.save(flush: true, failOnError: true)

        return file
	}

	private AsaasFile createEmpty(String fileName) {
		AsaasFile file = new AsaasFile()
		file.originalName = fileName
		file.publicId = buildPublicId()
		file.size = 0
		return file.save(flush: true, failOnError: true)
	}

    private void validateFile(CommonsMultipartFile file, TemporaryFile temporaryFile, Long customerId, String newFileOriginalName) {
        final Long maxFileSize = 209715200 // bytes ou 200 megabytes
        FileValidator fileValidator = new FileValidator()
        Boolean isValid = fileValidator.validate(file, temporaryFile, maxFileSize)
        if (!isValid) {
            String errors = fileValidator.errors.collect { it.getMessage() }.join(" - ")
            AsaasLogger.warn("FileService.saveFile >> Erro ao validar arquivo. Erros [${errors}] | Customer [${customerId}] | Arquivo [$newFileOriginalName].", new Throwable())
            throw new BusinessException("Arquivo inválido: ${errors}")
        }
    }
}
