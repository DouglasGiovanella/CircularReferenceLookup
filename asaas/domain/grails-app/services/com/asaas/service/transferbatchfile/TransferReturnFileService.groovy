package com.asaas.service.transferbatchfile

import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.credittransferrequest.CreditTransferRequestTransferBatchFile
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.domain.transferbatchfile.TransferBatchFile
import com.asaas.domain.transferbatchfile.TransferBatchFileItem
import com.asaas.domain.transferbatchfile.TransferReturnFile
import com.asaas.domain.transferbatchfile.TransferReturnFileItem
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.transferbatchfile.TransferBatchFileItemStatus
import com.asaas.transferbatchfile.bradesco.TransferBatchFileBuilderBradesco
import com.asaas.transferbatchfile.bradesco.TransferReturnFileParserBradesco
import com.asaas.transferbatchfile.bradesco.TransferReturnFileRetrieverBradesco
import com.asaas.transferbatchfile.santander.TransferBatchFileBuilderSantander
import com.asaas.transferbatchfile.santander.TransferReturnFileParserSantander
import com.asaas.transferbatchfile.santander.TransferReturnFileRetrieverSantander
import com.asaas.transferbatchfile.sicredi.TransferBatchFileBuilderSicredi
import com.asaas.transferbatchfile.sicredi.TransferReturnFileRetrieverSicredi
import com.asaas.transferbatchfile.sicredi.TransferReturnFileParserSicredi

import grails.transaction.Transactional
import grails.util.Environment
import java.security.MessageDigest

@Transactional
public class TransferReturnFileService {

    def bankAccountValidationTransferService
    def customerMessageService
    def creditTransferRequestService
    def fileService
    def messageService
    def refundRequestService
    def smsSenderService

	List<String> supportedBankCodes = [SupportedBank.BRADESCO.code, SupportedBank.SANTANDER.code, SupportedBank.SICREDI.code]

	List<SupportedBank> supportedBanksForAutomaticTransmission = [SupportedBank.SICREDI, SupportedBank.SANTANDER, SupportedBank.BRADESCO]

	public void retrieveAndProcessReturnFiles() {
		for (SupportedBank supportedBank : supportedBanksForAutomaticTransmission) {
			retrieveAndProcessReturnFile(supportedBank)
		}
	}

	public void retrieveAndProcessReturnFile(SupportedBank supportedBank) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		try {
			AsaasLogger.info("Retrieving transfer return file [${supportedBank}]")

			List<Map> transferReturnFileMapList = retrieveTransferReturnFileMapList(supportedBank)

			if (!transferReturnFileMapList || transferReturnFileMapList.size() == 0) return

			for (Map transferReturnFileMap in transferReturnFileMapList) {
				importReturnFile(transferReturnFileMap.fileContents, transferReturnFileMap.fileName, false)
				moveImportedReturnFile(transferReturnFileMap.fileName, supportedBank)
				AsaasLogger.info("---> Finished processing transfer return file ${transferReturnFileMap.fileName} [${supportedBank}]")
			}
		} catch (Exception e) {
            AsaasLogger.error("Erro ao processar arquivo de retorno de transferências do ${supportedBank}", e)
		}
	}

	private List<Map> retrieveTransferReturnFileMapList(SupportedBank supportedBank) {
		if (supportedBank == SupportedBank.SICREDI) {
			return TransferReturnFileRetrieverSicredi.retrieve()
		} else if (supportedBank == SupportedBank.SANTANDER) {
            return TransferReturnFileRetrieverSantander.retrieve()
        } else if (supportedBank == SupportedBank.BRADESCO) {
            return TransferReturnFileRetrieverBradesco.retrieve()
        }
	}

	public void importReturnFile(String fileContents, String fileName, Boolean isReimport) {
		List<String> returnFileLines = fileContents.readLines()

		String bankCode = getBankCodeFromFileHeader(returnFileLines[0])

		if (!(bankCode in supportedBankCodes)) throw new RuntimeException("Banco não suportado.")

		String fileHash = getFileHash(fileContents)

		Long returnFileId

		TransferReturnFile.withNewTransaction { status ->
			try {
				returnFileId = processReturnFile(bankCode, fileHash, fileContents, fileName, [isReimport: isReimport])
			} catch (Exception e) {
				status.setRollbackOnly()
                AsaasLogger.error("Erro ao processar arquivo de retorno de transferências: ${fileName}", e)
				throw e
			}
		}

		if (returnFileId && !isReimport) {
			List<Long> transferItemIdList

			CreditTransferRequestTransferBatchFile.withNewTransaction {
				transferItemIdList = CreditTransferRequestTransferBatchFile.query([transferReturnFileId: returnFileId, column: "id"]).list()
			}

			for (Long itemId in transferItemIdList) {
				CreditTransferRequestTransferBatchFile.withNewTransaction {
					CreditTransferRequestTransferBatchFile transferItem = CreditTransferRequestTransferBatchFile.get(itemId)
					sendTransferMessage(transferItem)
				}
			}
		}
	}

	public void sendTransferMessage(CreditTransferRequestTransferBatchFile transferItem) {
		try {
            if (!customerMessageService.canSendMessage(transferItem.creditTransferRequest.provider)) return

			if (transferItem.toCreditTransferRequestStatus() == CreditTransferRequestStatus.CONFIRMED) {
				creditTransferRequestService.sendCheckoutReceipt(transferItem.creditTransferRequest)
			} else if (transferItem.toCreditTransferRequestStatus() == CreditTransferRequestStatus.FAILED) {
                String smsMessage = "Sua transferência falhou. Verifique os seus dados comerciais e bancários na área Minha Conta e faça as correções caso seja necessário"
                smsSenderService.send(smsMessage, transferItem.creditTransferRequest.provider.mobilePhone, false, [:])
				sendMessageTransferFailed(transferItem)
			}
		} catch (Exception e) {
			AsaasLogger.error("Erro ao enviar mensagem sobre a transferência do cliente", e)
		}
	}

	public Long processReturnFile(String bankCode, String fileHash, String fileContents, String fileName, Map options) {
		TransferReturnFile transferReturnFile = TransferReturnFile.findByFileHash(fileHash)

		if (!options.isReimport && transferReturnFile) throw new RuntimeException('Este arquivo de retorno já foi importado.')

		if (options.isReimport && !transferReturnFile) throw new RuntimeException('Este arquivo ainda não foi importado no sistema (não marque a opção de reimportação).')

		def parser = buildTransferReturnFileParserInstance(bankCode, fileContents, fileName)

		if (!parser.validate()) throw new RuntimeException('Não é um arquivo de retorno válido.')

		if (!options.isReimport)
			transferReturnFile = saveReturnFile(bankCode, fileContents, fileName, fileHash)

		parser.parse()

		List<Map> parsedData = parser.getParsedData()

		int itemCount = 1
		int itemTotal = parsedData.size()

		TransferReturnFile.withSession { session ->
			for (transferMap in parsedData) {
				AsaasLogger.info("Processing transfer return item  ${itemCount}/${itemTotal}: ${transferMap.operationType} [${transferMap.id}]")

				if (transferMap.containsKey("operationType") && transferMap.operationType.isRefundTransfer()) {
					TransferReturnFileItem transferReturnFileItem = saveItem(transferMap, transferReturnFile)

					processItem(transferReturnFileItem)
                } else if (transferMap.containsKey("operationType") && transferMap.operationType.isBankAccountValidationTransfer()) {
                    bankAccountValidationTransferService.updateTransferReturnFileAndTransferStatus(transferMap.id, transferReturnFile, transferMap.status)
				} else {
					CreditTransferRequestTransferBatchFile transferBatchFileItem = findCreditTransferRequestBatchFileBy(transferMap.id, transferMap.attempt)

					if (transferBatchFileItem) {
						if (transferMap.status == transferBatchFileItem.status) continue

                        if (transferMap.status.isScheduled() && (transferBatchFileItem.status.isAlreadyConfirmed() || transferBatchFileItem.status.isFailedInvalidAccount())) continue

						updateCreditTransferRequestTransferBatchFile(transferBatchFileItem, transferReturnFile, transferMap)

                        if (transferBatchFileItem.toCreditTransferRequestStatus() == CreditTransferRequestStatus.PENDING && transferBatchFileItem.creditTransferRequest.status in [CreditTransferRequestStatus.FAILED, CreditTransferRequestStatus.CONFIRMED]) {
                            continue
                        }

						if (transferBatchFileItem.toCreditTransferRequestStatus() == CreditTransferRequestStatus.CONFIRMED) {
							creditTransferRequestService.confirm(transferBatchFileItem.creditTransferRequest.id, [bypassCheckoutReceipt: true])
						} else if (transferBatchFileItem.toCreditTransferRequestStatus() == CreditTransferRequestStatus.FAILED) {
							creditTransferRequestService.setAsFailed(transferBatchFileItem.creditTransferRequest)
						} else {
							creditTransferRequestService.updateStatus(transferBatchFileItem.creditTransferRequest.id, transferBatchFileItem.toCreditTransferRequestStatus())
						}
					}
				}

				itemCount++

				if (itemCount % 100 == 0) {
					session.flush()
			 		session.clear()
				}
			}
		}

        verifyForProcessingErrors(transferReturnFile.id)

		return transferReturnFile.id
	}

    public Boolean returnFileManualImportAllowed(String fileContents) {
        List<String> returnFileLines = fileContents.readLines()

		String returnFileBankCode = getBankCodeFromFileHeader(returnFileLines[0])

        if (returnFileBankCode in supportedBanksForAutomaticTransmission.collect { it.code }) return false

        return true
    }

	private String getBankCodeFromFileHeader(String fileHeader) {
		String communicationOrCovenantCode = fileHeader.substring(1,9)

		if (communicationOrCovenantCode == TransferBatchFileBuilderBradesco.communicationCode) {
			return SupportedBank.BRADESCO.code
		}

		communicationOrCovenantCode = fileHeader.substring(32, 52)

		if (communicationOrCovenantCode == TransferBatchFileBuilderSantander.communicationCode) {
			return SupportedBank.SANTANDER.code
		} else if (communicationOrCovenantCode.trim() == TransferBatchFileBuilderSicredi.sicrediCovenantCode) {
			return SupportedBank.SICREDI.code
		}

		return null
	}

	private String getFileHash(String fileContents) {
		MessageDigest digest = MessageDigest.getInstance("MD5")
		digest.update(fileContents.bytes);
		return new BigInteger(1,digest.digest()).toString(16).padLeft(32,"0")
	}

	def buildTransferReturnFileParserInstance(String bankCode, String fileContents, String fileName) {
		def instance
		if (bankCode == SupportedBank.BRADESCO.code) {
			instance = new TransferReturnFileParserBradesco(fileContents, fileName)
		} else if (bankCode == SupportedBank.SANTANDER.code) {
			instance = new TransferReturnFileParserSantander(fileContents, fileName)
		} else if (bankCode == SupportedBank.SICREDI.code) {
			instance = new TransferReturnFileParserSicredi(fileContents, fileName)
		}

		if (instance) return instance
		else throw new RuntimeException("Não foi possível importar o arquivo de remessa: banco não suportado")
	}

	private TransferReturnFile saveReturnFile(String bankCode, String fileContents, String fileName, String fileHash) {
        AsaasFile asaasFile = fileService.createFile(fileName, fileContents, "Cp1252")

		TransferReturnFile transferReturnFile = new TransferReturnFile()
		transferReturnFile.bank = Bank.findByCode(bankCode)
		transferReturnFile.fileHash = fileHash
        transferReturnFile.asaasFile = asaasFile
		transferReturnFile.save(flush: true, failOnError: true)

		return transferReturnFile
	}

	public TransferReturnFileItem saveItem(Map itemMap, TransferReturnFile transferReturnFile) {
		TransferReturnFileItem item = new TransferReturnFileItem()

		item.transferReturnFile = transferReturnFile
		item.refundRequest = RefundRequest.get(itemMap.id)
		item.status = Status.PENDING
		item.returnStatus = TransferBatchFileItemStatus.valueOf(itemMap.status.toString())
		item.attempt = itemMap.attempt
		item.returnCodes = itemMap.returnCodes.join(",")
		item.clientInconsistencies = parseClientInconsistenciesToSave(itemMap.clientInconsistencies)

		item.save(flush: true, failOnError: true)

		return item
	}

	public void processItem(TransferReturnFileItem returnItem) {
		TransferBatchFileItem batchItem = TransferBatchFileItem.query([refundRequest: returnItem.refundRequest, sort: "id", order: "desc"]).get()

        returnItem.status = Status.PROCESSED
        returnItem.save(flush: true, failOnError: true)

        if (returnItem.returnStatus.isScheduled() && (batchItem.status.isAlreadyConfirmed() || batchItem.status.isFailedInvalidAccount())) return

		batchItem.status = returnItem.returnStatus
		batchItem.save(flush: true, failOnError: true)

        if (returnItem.refundRequest.status.isPaid()) {
            AsaasLogger.warn("TransferReturnFileService.processItem >>> RefundRequest [${returnItem.refundRequest.id}] já está paga. TransferReturnFileItem: [${returnItem.id}]")
            return
        }

		refundRequestService.updateStatus(returnItem.refundRequest, batchItem.toRefundRequestStatus)
	}

	private CreditTransferRequestTransferBatchFile findCreditTransferRequestBatchFileBy(Long id, Integer attempt) {
		CreditTransferRequestTransferBatchFile creditTransferRequestTransferBatchFile = CreditTransferRequestTransferBatchFile.executeQuery(
			"select c from CreditTransferRequestTransferBatchFile c where c.creditTransferRequest.id = :id and c.attempt = :attempt",
			[id: id, attempt: attempt])[0]

		return creditTransferRequestTransferBatchFile
	}

	private void updateCreditTransferRequestTransferBatchFile(CreditTransferRequestTransferBatchFile creditTransferRequestTransferBatchFile, TransferReturnFile transferReturnFile, Map transferData) {
		creditTransferRequestTransferBatchFile.transferReturnFile = transferReturnFile
		creditTransferRequestTransferBatchFile.status = transferData.status
		creditTransferRequestTransferBatchFile.returnCodes = transferData.returnCodes.join(",")
		creditTransferRequestTransferBatchFile.clientInconsistencies = parseClientInconsistenciesToSave(transferData.clientInconsistencies)
		creditTransferRequestTransferBatchFile.save(flush: true, failOnError: true)
	}

	private String parseClientInconsistenciesToSave(List<String> clientInconsistencies) {
		if (!clientInconsistencies) {
			return ""
		}

		if (clientInconsistencies.size() > 1) {
			clientInconsistencies.removeElement("ZA")
		}
		return clientInconsistencies.unique().join(",")
	}

	private void sendMessageTransferFailed(CreditTransferRequestTransferBatchFile transferItem) {
		String emailMessageToCustomer = '<ul>'
		if (transferItem.clientInconsistencies) {
			emailMessageToCustomer += '<li>' + transferItem.getFailReasonMessage() + '</li>'
		} else {
			emailMessageToCustomer += '<li>Dados bancários inválidos.</li>'
		}
		emailMessageToCustomer += '</ul>'

		messageService.sendCreditTransferRequestFailed(transferItem.creditTransferRequest, emailMessageToCustomer)
	}

	private void moveImportedReturnFile(String fileName, SupportedBank supportedBank) {
        AsaasLogger.info("Moving processed transfer return file ${fileName}")

        if (supportedBank == SupportedBank.SICREDI) {
            TransferReturnFileRetrieverSicredi.moveProcessedFile(fileName)
        } else if (supportedBank == SupportedBank.SANTANDER) {
            TransferReturnFileRetrieverSantander.moveProcessedFile(fileName)
        } else if (supportedBank == SupportedBank.BRADESCO) {
            TransferReturnFileRetrieverBradesco.moveProcessedFile(fileName, null)
        } else {
            throw new RuntimeException("Não foi possível encontrar a classe TransferReturnFileRetriever do banco ${supportedBank} para mover o arquivo de retorno ${fileName}.")
        }
	}

    public void verifyForProcessingErrors(Long transferReturnFileId) {
        List<TransferBatchFile> transferBatchFilesWithProcessingError = CreditTransferRequestTransferBatchFile.processingErrorByTransferReturnFileId([transferReturnFileId: transferReturnFileId]).list()

        List<TransferBatchFile> refundRequestBatchFilesWithProcessingError = TransferBatchFileItem.processingErrorByTransferReturnFileId([transferReturnFileId: transferReturnFileId]).list()

        if (!transferBatchFilesWithProcessingError && !refundRequestBatchFilesWithProcessingError) return

        messageService.sendTransferBatchFileWithProcessingErrorAlert(transferBatchFilesWithProcessingError + refundRequestBatchFilesWithProcessingError)
    }

}
