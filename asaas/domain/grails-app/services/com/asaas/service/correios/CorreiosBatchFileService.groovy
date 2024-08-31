package com.asaas.service.correios

import com.asaas.domain.correios.CorreiosBatchFile
import com.asaas.domain.postalservice.PaymentPostalServiceBatch
import com.asaas.domain.postalservice.PaymentPostalServiceBatchItem
import com.asaas.correios.CorreiosBatchFileStatus
import com.asaas.correios.CorreiosBatchFileBuilder
import com.asaas.log.AsaasLogger
import com.asaas.applicationconfig.AsaasApplicationHolder
import grails.util.Environment
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPConnectionClosedException
import org.apache.commons.net.ftp.FTPSClient
import org.springframework.util.StopWatch

import java.util.zip.*

import grails.transaction.Transactional

@Transactional
class CorreiosBatchFileService {

	def correiosMessageService
    def fileService

	public CorreiosBatchFile save(List<Long> paymentPostalServiceBatchIdList){
		try {
			if (paymentPostalServiceBatchIdList.size() == 0) return

            AsaasLogger.info("CorreiosBatchFileService.save() >> ${paymentPostalServiceBatchIdList.size()} PaymentPostalServiceBatch found to generate CorreiosBatchFile")
            StopWatch stopWatch = new StopWatch("CorreiosBatchFileService.save")

			CorreiosBatchFile correiosBatchFile = new CorreiosBatchFile()

            List<Long> batchItemIdList = PaymentPostalServiceBatchItem.query([column: "id", "paymentPostalServiceBatch.id[in]": paymentPostalServiceBatchIdList, sort: "customerAccount.postalCode", order: "asc"]).list()

            stopWatch.start("correiosBatchFileBuilder.execute()")
            CorreiosBatchFileBuilder correiosBatchFileBuilder = new CorreiosBatchFileBuilder(batchItemIdList)
			correiosBatchFileBuilder.execute()
            stopWatch.stop()

            stopWatch.start("correiosBatchFile.createFile")
            correiosBatchFile.createFile(correiosBatchFileBuilder.fileName, correiosBatchFileBuilder.fileContents)
            stopWatch.stop()

            correiosBatchFile.batchNumber = correiosBatchFileBuilder.batchNumber
			correiosBatchFile.status = CorreiosBatchFileStatus.PENDING

            if (correiosBatchFileBuilder.itemsWithError.size() == batchItemIdList.size()) return

            stopWatch.start("correiosBatchFile.addToPostalServiceBatchs")
            for (Long batchId in paymentPostalServiceBatchIdList) {
                PaymentPostalServiceBatch batch = PaymentPostalServiceBatch.get(batchId)
				correiosBatchFile.addToPostalServiceBatchs(batch)
			}
            stopWatch.stop()

            stopWatch.start("correiosBatchFile.save(flush: true)")
            correiosBatchFile.save(flush: true, failOnError: true)
            stopWatch.stop()

            AsaasLogger.info("CorreiosBatchFileService.save() >> ${stopWatch.prettyPrint()}")

			return correiosBatchFile
		} catch (Exception exception) {
            AsaasLogger.error("CorreiosBatchFileService.save() >> Falha ao gerar o conteúdo do arquivo de remessa.", exception)
			return null
		}
	}

	public void sendFileToCorreiosFtp() {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		CorreiosBatchFile.withNewTransaction { transaction ->
			List<Long> correiosBatchFileIdList = CorreiosBatchFile.query([column: "id", status: CorreiosBatchFileStatus.PENDING]).list()

			if (correiosBatchFileIdList.size() == 0) return

			AsaasLogger.info("CorreiosBatchFileService.sendFileToCorreiosFtp() >> ${correiosBatchFileIdList.size()} CorreiosBatchFile found to send to Correios FTP")

			for (Long id in correiosBatchFileIdList) {
				CorreiosBatchFile.withNewTransaction { status ->
					CorreiosBatchFile correiosBatchFile = CorreiosBatchFile.get(id)

					try {
                        FTPSClient ftpsClient = new FTPSClient(true)
                        ftpsClient.connect(AsaasApplicationHolder.config.asaas.ftp.correios.batchfile.server)
                        ftpsClient.login(AsaasApplicationHolder.config.asaas.ftp.correios.batchfile.username, AsaasApplicationHolder.config.asaas.ftp.correios.batchfile.password)
                        ftpsClient.enterLocalPassiveMode()

                        File zipFile = buildZipFile(correiosBatchFile)
                        String zipFileName = buildFileName(correiosBatchFile.batchNumber, ".zip")
                        fileService.createFile(null, zipFile, zipFileName)

                        ftpsClient.setFileType(FTPClient.BINARY_FILE_TYPE)
                        ftpsClient.changeWorkingDirectory(AsaasApplicationHolder.config.asaas.ftp.correios.batchfile.directory)
                        ftpsClient.storeFile(zipFileName, new FileInputStream(zipFile))
                        ftpsClient.disconnect()

						correiosBatchFile.status = CorreiosBatchFileStatus.SENT
						correiosBatchFile.save(failOnError: true)

				        sendReports(correiosBatchFile)
					} catch (FTPConnectionClosedException ftpConnectionClosedException) {
                        status.setRollbackOnly()
                        AsaasLogger.error("CorreiosBatchFileService.sendFileToCorreiosFtp() >> Falha no envio do arquivo dos Correios. Erro na conexão com o FTP.", ftpConnectionClosedException)
					} catch (Exception exception) {
						status.setRollbackOnly()
                        AsaasLogger.error("CorreiosBatchFileService.sendFileToCorreiosFtp() >> Falha ao gerar o arquivo dos Correios para ser enviado ao servidor FTP.", exception)
					}
				}
			}
		}
	}

	private File buildZipFile(CorreiosBatchFile correiosBatchFile) {
		File file = File.createTempFile(correiosBatchFile.asaasFile.originalName, ".zip")
		ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))

		addDataFile(correiosBatchFile, out)
		addProviderImages(correiosBatchFile, out)
		addBankImage(out)

		out.close()

		return file
	}

	private void addDataFile(CorreiosBatchFile correiosBatchFile, ZipOutputStream out) {
		InputStream input = new FileInputStream(correiosBatchFile.asaasFile.getFile())
		addToZipFile(buildFileName(correiosBatchFile.batchNumber, ".txt"), input, out)
	}

	private void addProviderImages(CorreiosBatchFile correiosBatchFile, ZipOutputStream out) {
		List<Long> providerIds = new ArrayList<Long>()

		for (Long id : correiosBatchFile.postalServiceBatchs.findAll{ it }.customer.id) {
			if (providerIds.contains(id)) continue

			providerIds.add(id)

			String fileName = "codigo_cliente_${id.toString()}.png"

			InputStream input = this.class.classLoader.getResourceAsStream("boletoLogos/${fileName}")

			if (!input) continue

			addToZipFile(fileName, input, out)
		}
	}

	private void addBankImage(ZipOutputStream out) {
		String[] banks = ["748", "33", "341", "1", "237", "422", "630", "461"]

		for (String bank : banks) {
			String fileName = "codigo_banco_${bank}.png"

			InputStream input = this.class.classLoader.getResourceAsStream("boletoLogos/${fileName}")

			if (!input) {
                AsaasLogger.info("CorreiosBatchFileService.addBankImage >> Logo do banco ${bank} não carregada")

                continue
            }

			addToZipFile(fileName, input, out)
		}
	}

	private void addToZipFile(String fileName, InputStream input, ZipOutputStream out) {
		byte[] buffer = new byte[1024]

		out.putNextEntry(new ZipEntry(fileName))

		for (int read = input.read(buffer); read > -1; read = input.read(buffer)) {
            out.write(buffer, 0, read)
        }

        input.close()
        out.closeEntry()
	}

	private static String buildFileName(Long batchNumber, String extension) {
		return "asaas_" + batchNumber + "_" + new Date().format("yyyyMMdd") + extension
	}

	private void sendReports(CorreiosBatchFile correiosBatchFile) {
		Integer totalOfItens = 0

		for (PaymentPostalServiceBatch batch : correiosBatchFile.postalServiceBatchs) {
			totalOfItens += batch.items.size()
		}

		correiosMessageService.sendNotificationAboutFileSentToCorreiosFtp(buildFileName(correiosBatchFile.batchNumber, ".zip"), totalOfItens)
	}
}
