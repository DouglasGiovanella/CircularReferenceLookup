package com.asaas.service.transferbatchfile

import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.credittransferrequest.CreditTransferRequestTransferBatchFileStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankaccountinfo.BankAccountValidationTransfer
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.credittransferrequest.CreditTransferRequestTransferBatchFile
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.domain.transferbatchfile.TransferBatchFile
import com.asaas.domain.transferbatchfile.TransferBatchFileItem
import com.asaas.domain.transferbatchfile.TransferBatchFileSequence
import com.asaas.domain.transferbatchfile.TransferBatchFileTransmissionAuthorization
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.refundrequest.RefundRequestStatus
import com.asaas.transferbatchfile.bradesco.TransferBatchFileBuilderBradesco
import com.asaas.transferbatchfile.santander.TransferBatchFileBuilderSantander
import com.asaas.transferbatchfile.sicredi.TransferBatchFileBuilderSicredi
import com.asaas.transferbatchfile.TransferBatchFileOperationType
import com.asaas.transferbatchfile.TransferBatchFileTransmitter
import com.asaas.transferbatchfile.TransferBatchFileType
import com.asaas.transferbatchfile.TransferTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.transferbatchfile.TransferBatchFileItemStatus
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

import org.apache.commons.lang.RandomStringUtils

@Transactional
public class TransferBatchFileService {

    def creditTransferRequestAdminService
    def creditTransferRequestService
    def messageService
    def mfaService
    def smsSenderService
    def springSecurityService

	public TransferBatchFile buildBatchFile(Map params, List<Long> transferIdList, Date scheduledDate, Integer transferCount, BigDecimal transferTotalValue) {
		if (!(params.batchFileBankCode in TransferBatchFile.SUPPORTED_BANK_CODES)) throw new RuntimeException("Banco não suportado.")

		if (!transferIdList.size()) throw new BusinessException("Nenhum item selecionado para gerar a remessa.")

		params << ['id[in]':  transferIdList]
		List<CreditTransferRequest> creditTransferRequestList = creditTransferRequestService.listForTransferBatchFile(params.findAll { it.value })

        if (creditTransferRequestList.size() != transferCount) {
			throw new BusinessException("A quantidade de transferências encontradas não corresponde à quantidade selecionada na solicitação da geração da remessa.")
		} else if (creditTransferRequestList*.netValue.sum() != transferTotalValue) {
			throw new BusinessException("O valor das transferências encontradas não corresponde ao valor selecionado na solicitação da geração da remessa.")
		}

		if (creditTransferRequestList.size() == 0) throw new BusinessException("Nenhuma transferência localizada.")

        creditTransferRequestList.each { validateCreditTransferRequestFinancialTransaction(it) }
		try {
			TransferBatchFile transferBatchFile = save(params.batchFileBankCode, scheduledDate, TransferBatchFileType.SCHEDULING, TransferBatchFileOperationType.TRANSFER, true)

			List<Long> creditTransferRequestTransferBatchFileIdList = associateCreditTransferRequestWithTransferBatchFile(creditTransferRequestList.id, transferBatchFile.id)
			return transferBatchFile
		} catch (Exception exception) {
            AsaasLogger.error("TransferBatchFileService.buildBatchFile >> Falha na geração do lote de transferências", exception)
            throw exception
		}
	}

    public TransferBatchFile processFileCreation() {
        List<Long> transferBatchFileIdList = TransferBatchFile.query([column: "id", awaitingFileCreation: true, nullFileContents: true]).list()

        for (Long transferBatchFileId : transferBatchFileIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                TransferBatchFile transferBatchFile = TransferBatchFile.get(transferBatchFileId)

                if (transferBatchFile.operationType.isTransfer()) {
                    createBatchFile(transferBatchFile)
                } else if (transferBatchFile.operationType.isRefundTransfer()) {
                    createRefundBatchFile(transferBatchFile)
                }

                messageService.notifyTransferBatchFileCreationDone(transferBatchFile)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    AsaasLogger.error("Erro ao processar a criação do arquivo de remessa de transferencias [${transferBatchFileId}]", exception)
                }
            ])
        }
    }

	public TransferBatchFile buildRefundBatchFile(List<Long> idList) {
		String bankCode = SupportedBank.BRADESCO.code

		if (!idList.size()) throw new BusinessException("Nenhum item selecionado para gerar a remessa.")

		try {
			List<RefundRequest> refundRequestList = RefundRequest.readyToTransfer([idList: idList]).list()

			TransferBatchFile transferBatchFile = save(bankCode, new Date().clearTime(), TransferBatchFileType.SCHEDULING, TransferBatchFileOperationType.REFUND_TRANSFER, true)

			saveTransferBatchFileItems(transferBatchFile, refundRequestList)

			return transferBatchFile
        } catch (Exception exception) {
            AsaasLogger.error("TransferBatchFileService.buildRefundBatchFile >> Falha na geração do lote de estorno de transferências", exception)
            throw exception
        }
	}

	public List<TransferBatchFileItem> saveTransferBatchFileItems(TransferBatchFile transferBatchFile, List<RefundRequest> refundRequestList) {
		List<TransferBatchFileItem> transferBatchFileItemList = []

		Integer itemCount = 0
		Integer itemTotal = refundRequestList.size()

		for (RefundRequest refundRequest in refundRequestList) {
			TransferBatchFileItem transferBatchFileItem = new TransferBatchFileItem([transferBatchFile: transferBatchFile, refundRequest: refundRequest, status: TransferBatchFileItemStatus.AWAITING_FILE_CREATION]).save(flush: true, failOnError: true)

            Date scheduledDate = transferBatchFile.scheduledDate.clone()
            refundRequest.paymentDate = scheduledDate.clearTime()
			refundRequest.status = RefundRequestStatus.BANK_PROCESSING
			refundRequest.save(flush: true, failOnError: true)

			transferBatchFileItemList.add(transferBatchFileItem)

			itemCount++
            AsaasLogger.info("Saving item [RefundRequest: ${refundRequest.id}] ${itemCount} / ${itemTotal}")
		}

		return transferBatchFileItemList
	}

	public List<Long> associateCreditTransferRequestWithTransferBatchFile(List<Long> creditTransferRequesIdList, Long transferBatchFileId) {
		List<Long> creditTransferRequestTransferBatchFileIdList = []

		TransferBatchFile transferBatchFile = TransferBatchFile.get(transferBatchFileId)
        Integer flushEvery = 100

        Utils.forEachWithFlushSession(creditTransferRequesIdList, flushEvery, { Long creditTransferRequestId ->
            CreditTransferRequest creditTransferRequest = CreditTransferRequest.get(creditTransferRequestId)

            if (transferAlreadyPendingOrSuccessful(creditTransferRequest)) throw new RuntimeException("Transferência ${creditTransferRequest.id} já agendada ou efetuada.")

            CreditTransferRequestTransferBatchFile creditTransferRequestTransferBatchFile = new CreditTransferRequestTransferBatchFile()
            creditTransferRequestTransferBatchFile.creditTransferRequest = creditTransferRequest
            creditTransferRequestTransferBatchFile.transferBatchFile = transferBatchFile
            creditTransferRequestTransferBatchFile.status = CreditTransferRequestTransferBatchFileStatus.AWAITING_FILE_CREATION
            creditTransferRequestTransferBatchFile.transferTransactionType = getTransferTransactionType(transferBatchFile.bank.code, creditTransferRequestTransferBatchFile.creditTransferRequest)
            creditTransferRequestTransferBatchFile.attempt = getCurrentTransferAttempt(creditTransferRequest)
            creditTransferRequestTransferBatchFile.save(failOnError: true)

            Date scheduledDate = transferBatchFile.scheduledDate.clone()
            creditTransferRequest.transferDate = scheduledDate.clearTime()
            creditTransferRequest.status = CreditTransferRequestStatus.BANK_PROCESSING
            creditTransferRequest.save(failOnError: true)

            creditTransferRequestTransferBatchFileIdList.add(creditTransferRequestTransferBatchFile.id)
        })

		return creditTransferRequestTransferBatchFileIdList
	}

	public Boolean canCancelTransferBatchFile(TransferBatchFile transferBatchFile, List<CreditTransferRequestTransferBatchFile> creditTransferRequestTransferBatchFileList) {
		if (transferBatchFile.transmissionDate) return false

		Integer transferInBankProcessingCounter = 0
		Integer transferScheduledCounter = 0

		for (creditTransferRequestTransferBatchFile in creditTransferRequestTransferBatchFileList) {
			if (creditTransferRequestTransferBatchFile.status == CreditTransferRequestTransferBatchFileStatus.BANK_PROCESSING)
				transferInBankProcessingCounter++

			if (creditTransferRequestTransferBatchFile.status == CreditTransferRequestTransferBatchFileStatus.SCHEDULED && creditTransferRequestTransferBatchFile.transferTransactionType != TransferTransactionType.DOC)
				transferScheduledCounter++
		}

		if (transferInBankProcessingCounter == 0 && transferScheduledCounter == 0) {
			return false
		} else if (transferScheduledCounter > 0 && !transferDateAllowedToCancel(transferBatchFile.scheduledDate)) {
			return false
		} else {
			return true
		}
	}

	public TransferBatchFile cancelBatchFile(Long id) {
		TransferBatchFile transferBatchFile = TransferBatchFile.findById(id)

		if (!transferBatchFile) throw new ResourceNotFoundException("Remessa não encontrada.")

		List<CreditTransferRequestTransferBatchFile> creditTransferRequestTransferBatchFileList = CreditTransferRequestTransferBatchFile.findAllByTransferBatchFile(transferBatchFile)

		if (!canCancelTransferBatchFile(transferBatchFile, creditTransferRequestTransferBatchFileList)) throw new RuntimeException("Remessa não pode ser cancelada.")

		List<CreditTransferRequestTransferBatchFile> cancellationCreditTransferRequestTransferBatchFileList = []

		for (creditTransferRequestTransferBatchFile in creditTransferRequestTransferBatchFileList) {
			if (creditTransferRequestTransferBatchFile.status in [CreditTransferRequestTransferBatchFileStatus.SCHEDULED, CreditTransferRequestTransferBatchFileStatus.BANK_PROCESSING]) {
				cancellationCreditTransferRequestTransferBatchFileList.add(creditTransferRequestTransferBatchFile)
			}
		}

		TransferBatchFile cancellationBatchFile = buildCancellationBatchFile(cancellationCreditTransferRequestTransferBatchFileList, CreditTransferRequestTransferBatchFileStatus.AWAITING_CANCELLATION)

		transferBatchFile.cancellationBatchFile = cancellationBatchFile
		transferBatchFile.save(flush: true, failOnError: true)

		return cancellationBatchFile
	}

	public void cancelBatchFileNotSentToBank(Long id) {
		TransferBatchFile transferBatchFile = TransferBatchFile.get(id)

		List<CreditTransferRequestTransferBatchFile> transferBatchFileItemList = CreditTransferRequestTransferBatchFile.findAllByTransferBatchFile(transferBatchFile)

		if (!canCancelTransferBatchFile(transferBatchFile, transferBatchFileItemList)) throw new RuntimeException("Remessa não pode ser cancelada.")

		for (transferBatchFileItem in transferBatchFileItemList) {
			if (transferBatchFileItem.status == CreditTransferRequestTransferBatchFileStatus.BANK_PROCESSING) {
                CreditTransferRequest creditTransferRequest = creditTransferRequestAdminService.authorize(transferBatchFileItem.creditTransferRequest)
                if (creditTransferRequest.hasErrors()) throw new RuntimeException(creditTransferRequest.errors.allErrors[0].defaultMessage)

				transferBatchFileItem.status = CreditTransferRequestTransferBatchFileStatus.CANCELLED
			} else {
				throw new RuntimeException("Status inválido para a transferência [${transferBatchFileItem.creditTransferRequest.id}]")
			}
		}

		transferBatchFile.cancelled = true
		transferBatchFile.save(flush: true, failOnError: true)
	}

	public TransferBatchFile buildCancellationBatchFile(List<CreditTransferRequestTransferBatchFile> cancellationCreditTransferRequestTransferBatchFileList, CreditTransferRequestTransferBatchFileStatus status) {
		String bankCode = cancellationCreditTransferRequestTransferBatchFileList[0].transferBatchFile.bank.code

		TransferBatchFile cancellationBatchFile = save(bankCode, new Date(), TransferBatchFileType.CANCELLATION, TransferBatchFileOperationType.TRANSFER, false)

		for (cancellationCreditTransferRequestTransferBatchFile in cancellationCreditTransferRequestTransferBatchFileList) {
			cancellationCreditTransferRequestTransferBatchFile.cancellationBatchFile = cancellationBatchFile
			cancellationCreditTransferRequestTransferBatchFile.status = status
			cancellationCreditTransferRequestTransferBatchFile.save(flush: true, failOnError: true)

			creditTransferRequestService.updateStatus(cancellationCreditTransferRequestTransferBatchFile.creditTransferRequest.id, cancellationCreditTransferRequestTransferBatchFile.toCreditTransferRequestStatus())
		}

		def builder = buildTransferBatchFileBuilderInstance(bankCode, cancellationCreditTransferRequestTransferBatchFileList, cancellationBatchFile.batchNumber, TransferBatchFileOperationType.TRANSFER)

		builder.buildCancellationFile()
		cancellationBatchFile.fileContents = builder.getFileContents()
		cancellationBatchFile.fileName = builder.getfileName()
		cancellationBatchFile.save(flush: true, failOnError: true)

		return cancellationBatchFile
	}

	public TransferBatchFile cancelBatchFileItem(CreditTransferRequestTransferBatchFile creditTransferRequestTransferBatchFile) {
		if (!canCancelTransferBatchFileItem(creditTransferRequestTransferBatchFile)) {
			throw new RuntimeException("Esta transferência não pode ser cancelada.")
		}

		return buildCancellationBatchFile([creditTransferRequestTransferBatchFile], CreditTransferRequestTransferBatchFileStatus.AWAITING_CANCELLATION)
	}

	public Boolean transferDateAllowedToCancel(Date transferScheduledDate) {
		if (transferScheduledDate == null) return false

		Calendar now = CustomDateUtils.getInstanceOfCalendar();
		Calendar today =  CustomDateUtils.getInstanceOfCalendar(new Date().clearTime());

		if (transferScheduledDate.clearTime().before(today.getTime()) || transferScheduledDate.clearTime() == today.getTime()) return false

		Calendar limitDateHour = CustomDateUtils.getInstanceOfCalendar(new Date().clearTime());
		limitDateHour.set(Calendar.HOUR, 19)

		return now.before(limitDateHour)
	}

	public Boolean canCancelTransferBatchFileItem(CreditTransferRequestTransferBatchFile creditTransferRequestTransferBatchFile) {
		if (creditTransferRequestTransferBatchFile) {
			if (creditTransferRequestTransferBatchFile.status == CreditTransferRequestTransferBatchFileStatus.SCHEDULED &&
				creditTransferRequestTransferBatchFile.transferTransactionType == TransferTransactionType.DOC &&
				transferDateAllowedToCancel(creditTransferRequestTransferBatchFile.transferBatchFile.scheduledDate)) {
				return true
			}
		}

		return false
	}

	public void approveTransmission(Long id) {
		TransferBatchFile transferBatchFile = TransferBatchFile.get(id)
        User currentUser = UserUtils.getCurrentUser()

        BusinessValidation validatedTransmissionApproval = transferBatchFile.transmissionCanBeApproved(currentUser)
        if (!validatedTransmissionApproval.isValid()) throw new BusinessException(validatedTransmissionApproval.getFirstErrorMessage())

        transferBatchFile.transmissionApproved = true
        transferBatchFile.save(failOnError: true)
	}

    public TransferBatchFile authorizeTransmission(Long id, String mfaCode) {
        TransferBatchFile transferBatchFile = TransferBatchFile.lock(id)
        User currentUser = UserUtils.getCurrentUser()

        if (!AdminUserPermissionUtils.transmitTransferBatchFile(currentUser)) throw new BusinessException("Usuário sem permissão para transmitir remessas.")

        BusinessValidation validatedTransmissionAuthorization = transferBatchFile.transmissionCanBeAuthorized(currentUser)
        if (!validatedTransmissionAuthorization.isValid()) throw new BusinessException(validatedTransmissionAuthorization.getFirstErrorMessage())

        saveTransmissionAuthorization(transferBatchFile, currentUser, mfaCode)

        if (transferBatchFile.canBeTransmitted(currentUser).isValid()) {
            transmit(transferBatchFile)
        }

        return transferBatchFile
    }

    private TransferBatchFileTransmissionAuthorization saveTransmissionAuthorization(TransferBatchFile transferBatchFile, User currentUser, String mfaCode) {
        mfaService.authorizeUserActionWithTotp(currentUser, mfaCode)

        TransferBatchFileTransmissionAuthorization authorization = new TransferBatchFileTransmissionAuthorization()
        authorization.transferBatchFile = transferBatchFile
        authorization.authorizer = currentUser
        authorization.save(failOnError: true, flush: true)
        return authorization
    }

	public void requestTransmissionAuthorizationToken(Long transferBatchFileId) {
		if (!AdminUserPermissionUtils.transmitTransferBatchFile(springSecurityService.currentUser)) throw new BusinessException("O usuário [${springSecurityService.currentUser.username}] não tem permissão para transmitir remessas.")

		TransferBatchFile transferBatchFile = TransferBatchFile.get(transferBatchFileId)

		TransferBatchFileTransmissionAuthorization authorization = TransferBatchFileTransmissionAuthorization.findOrCreateWhere(transferBatchFile: transferBatchFile)

		String authorizationCode = RandomStringUtils.randomNumeric(6)

		authorization.authorizationCode = authorizationCode.encodeAsSHA256()

		authorization.save(failOnError: true)

        String message = "Codigo de autorizacao da remessa [${transferBatchFile.id}]: ${authorizationCode}"
        String phone = getUserMobilePhone(springSecurityService.currentUser)
        smsSenderService.send(message, phone, true, [isSecret: true])
	}

	public String getUserMobilePhone(User user) {
		if (user.username == "jeferson@asaas.com.br") {
			return "47991447979"
		} else if (user.username == "diego@asaas.com.br") {
			return "47996585657"
		} else if (user.username == "fernando@asaas.com.br") {
			return "47991844140"
		} else if (user.username == "piero@asaas.com.br") {
            return "47988287217"
        }
	}

	public void setAsTransmitted(TransferBatchFile transferBatchFile) {
		if (!transferBatchFile || transferBatchFile.transmissionDate) return

		transferBatchFile.transmissionDate = new Date()
		transferBatchFile.save(flush: true, failOnError: true)
	}

	public void setAsTransmitted(Long id) {
		TransferBatchFile transferBatchFile = TransferBatchFile.get(id)
		setAsTransmitted(transferBatchFile)
	}

    public void verifyTransmittedBatchFiles() {
        if (!AsaasEnvironment.isProduction()) return

        final Integer minutesAfterFileTransmission = 15

        List<TransferBatchFile> transferBatchFileList = TransferBatchFile.query(['dateCreated[ge]': new Date().clearTime(), disableSort: true]).list()

        for (TransferBatchFile transferBatchFile in transferBatchFileList) {
            if (!TransferBatchFile.SYSTEMIC_TRANSMISSION_BANKS.contains(transferBatchFile.bank.code)) continue

            if (!transferBatchFile.transmissionDate) continue

            if (CustomDateUtils.calculateDifferenceInMinutes(transferBatchFile.transmissionDate, new Date()) < minutesAfterFileTransmission) continue

            if (!TransferBatchFileTransmitter.remoteFileExists(transferBatchFile, false)) {
                AsaasLogger.error("[Remessa de transferências: arquivo remoto não encontrado] Remessa transmitida [${transferBatchFile.id}] ${transferBatchFile.fileName} (${transferBatchFile.bank?.name})")
            }
        }
    }

    public void verifyTransfersWithoutReturn() {
        final Integer minutesToAlertTransferWithoutReturn = 180

        List<TransferBatchFile> transferBatchFileList = TransferBatchFile.query(['dateCreated[ge]': new Date().clearTime()]).list()

        for (TransferBatchFile transferBatchFile in transferBatchFileList) {
            if (!transferBatchFile.transmissionDate) continue

            if (CustomDateUtils.calculateDifferenceInMinutes(transferBatchFile.transmissionDate, new Date()) < minutesToAlertTransferWithoutReturn) continue

            Integer numberOfTransfersWithoutReturn = 0

            if (transferBatchFile.operationType.isTransfer()) {
                numberOfTransfersWithoutReturn = CreditTransferRequestTransferBatchFile.query([transferBatchFileId: transferBatchFile.id, status: CreditTransferRequestTransferBatchFileStatus.BANK_PROCESSING]).count()
            } else if (transferBatchFile.operationType.isRefundTransfer()) {
                numberOfTransfersWithoutReturn = TransferBatchFileItem.query([transferBatchFile: transferBatchFile, status: TransferBatchFileItemStatus.BANK_PROCESSING]).count()
            }

            if (numberOfTransfersWithoutReturn > 0) {
                AsaasLogger.error("[Transferências sem retorno] Total: ${numberOfTransfersWithoutReturn} itens. Remessa [${transferBatchFile.id}] ${transferBatchFile.fileName} (${transferBatchFile.bank?.name})")
            }
        }
    }

    public void verifyNotConfirmedTransfers() {
        Date yesterday = CustomDateUtils.getYesterday()

        List<TransferBatchFile> transferBatchFileList = TransferBatchFile.query(['dateCreated[ge]': yesterday.clearTime(), 'dateCreated[le]': CustomDateUtils.setTimeToEndOfDay(yesterday)]).list()

        for (TransferBatchFile transferBatchFile in transferBatchFileList) {
            Integer numberOfScheduledTransfers = 0

            if (transferBatchFile.operationType.isTransfer()) {
                numberOfScheduledTransfers = CreditTransferRequestTransferBatchFile.query([transferBatchFileId: transferBatchFile.id, status: CreditTransferRequestTransferBatchFileStatus.SCHEDULED]).count()
            } else if (transferBatchFile.operationType.isRefundTransfer()) {
                numberOfScheduledTransfers = TransferBatchFileItem.query([transferBatchFile: transferBatchFile, status: TransferBatchFileItemStatus.SCHEDULED]).count()
            }

            if (numberOfScheduledTransfers > 0) {
                AsaasLogger.error("[Transferências não confirmadas] Total: ${numberOfScheduledTransfers} itens. Remessa [${transferBatchFile.id}] ${transferBatchFile.fileName} (${transferBatchFile.bank?.name})")
            }
        }
    }

    public void transmit(TransferBatchFile transferBatchFile) {
        Boolean operationTypeNeedsValidationToTransmit = (transferBatchFile.operationType.isTransfer() || transferBatchFile.operationType.isRefundTransfer())
        if (operationTypeNeedsValidationToTransmit) validateTransmission(transferBatchFile)

        Boolean sendResult = TransferBatchFileTransmitter.send(transferBatchFile)

        setAsTransmitted(transferBatchFile)

        if (!sendResult) throw new RuntimeException("Erro ao transmitir arquivo para o banco.")
    }

    def buildTransferBatchFileBuilderInstance(String bankCode, List transferItemList, Integer batchNumber, TransferBatchFileOperationType operationType) {
		def instance

		if (bankCode == SupportedBank.BRADESCO.code) {
			instance = new TransferBatchFileBuilderBradesco(transferItemList, batchNumber)
		}

		if (bankCode == SupportedBank.SANTANDER.code) {
			instance = new TransferBatchFileBuilderSantander(transferItemList, batchNumber)
		}

		if (bankCode == SupportedBank.SICREDI.code) {
			instance = new TransferBatchFileBuilderSicredi(transferItemList, batchNumber)
		}

		if (instance) return instance

		throw new RuntimeException("Não foi possível gerar o arquivo de remessa: banco não suportado")
	}

    private TransferBatchFile save(String bankCode, Date scheduledDate, TransferBatchFileType type, TransferBatchFileOperationType operationType, Boolean awaitingFileCreation) {
        TransferBatchFile transferBatchFile = new TransferBatchFile()
        transferBatchFile.bank = Bank.findWhere(code: bankCode)
        transferBatchFile.batchNumber = getNextTransferBatchNumber(bankCode)
        transferBatchFile.scheduledDate = scheduledDate
        transferBatchFile.type = type
        transferBatchFile.operationType = operationType
        transferBatchFile.createdBy = UserUtils.getCurrentUser()
        transferBatchFile.awaitingFileCreation = awaitingFileCreation
        transferBatchFile.save(failOnError: true)

        return transferBatchFile
    }

    private void validateTransmission(TransferBatchFile transferBatchFile) {
        User currentUser = UserUtils.getCurrentUser()

        if (!AdminUserPermissionUtils.transmitTransferBatchFile(currentUser)) throw new BusinessException("Usuário sem permissão para transmitir remessas.")

        BusinessValidation validatedTransmission = transferBatchFile.canBeTransmitted(currentUser)
        if (!validatedTransmission.isValid()) throw new BusinessException(validatedTransmission.getFirstErrorMessage())
    }

    private void updateBatchFileWithFileNameAndContents(Long transferBatchFileId, String fileContents, String fileName) {
		TransferBatchFile transferBatchFile = TransferBatchFile.get(transferBatchFileId)
		transferBatchFile.fileContents = fileContents
		transferBatchFile.fileName = fileName
        transferBatchFile.awaitingFileCreation = false
		transferBatchFile.save(flush: true, failOnError: true)
	}

    private TransferTransactionType getTransferTransactionType(String bankCode, CreditTransferRequest creditTransferRequest) {
		if (bankCode == creditTransferRequest.transfer.destinationBankAccount.bank.code) return TransferTransactionType.TRANSFER
		if (creditTransferRequest.isDOC()) return TransferTransactionType.DOC
		return TransferTransactionType.TED
	}

	private Integer getCurrentTransferAttempt(CreditTransferRequest creditTransferRequest) {
		String sqlQuery = "select count(id) from CreditTransferRequestTransferBatchFile c where c.creditTransferRequest = :creditTransferRequest"
		Integer currentAttempt = CreditTransferRequestTransferBatchFile.executeQuery(sqlQuery, [creditTransferRequest: creditTransferRequest])[0]

		return currentAttempt + 1
	}

	private Boolean transferAlreadyPendingOrSuccessful(creditTransferRequest) {
		String sqlQuery = "select count(id) from CreditTransferRequestTransferBatchFile c where c.creditTransferRequest = :creditTransferRequest and status in :status"
		Integer attempt = CreditTransferRequestTransferBatchFile.executeQuery(sqlQuery, [creditTransferRequest: creditTransferRequest, status: [CreditTransferRequestTransferBatchFileStatus.BANK_PROCESSING, CreditTransferRequestTransferBatchFileStatus.SCHEDULED, CreditTransferRequestTransferBatchFileStatus.CONFIRMED]])[0]

		return attempt > 0
	}

	private Integer getNextTransferBatchNumber(String bankCode) {
		Bank bank = Bank.findWhere(code: bankCode)

		if (bankCode == SupportedBank.BRADESCO.code) {
			return TransferBatchFile.countByBankAndDate([bank: bank]).get() + 1
		} else {
			return TransferBatchFileSequence.next(bankCode)
		}

		return 0
	}

    private void createBatchFile(TransferBatchFile transferBatchFile) {
        List<CreditTransferRequestTransferBatchFile> creditTransferRequestTransferBatchFileList = CreditTransferRequestTransferBatchFile.query([transferBatchFileId: transferBatchFile.id, status: CreditTransferRequestTransferBatchFileStatus.AWAITING_FILE_CREATION]).list()

        def builder = buildTransferBatchFileBuilderInstance(transferBatchFile.bank.code, creditTransferRequestTransferBatchFileList, transferBatchFile.batchNumber, transferBatchFile.operationType)
        builder.buildFile()

        updateBatchFileWithFileNameAndContents(transferBatchFile.id, builder.getFileContents(), builder.getfileName())

        for (CreditTransferRequestTransferBatchFile creditTransferRequestTransferBatchFile in creditTransferRequestTransferBatchFileList) {
            creditTransferRequestTransferBatchFile.status = CreditTransferRequestTransferBatchFileStatus.BANK_PROCESSING
            creditTransferRequestTransferBatchFile.save(failOnError: true)
        }
    }

    private void createRefundBatchFile(TransferBatchFile transferBatchFile) {
        List<TransferBatchFileItem> transferBatchFileItemList = TransferBatchFileItem.query([transferBatchFile: transferBatchFile, status: TransferBatchFileItemStatus.AWAITING_FILE_CREATION]).list()

        def builder = buildTransferBatchFileBuilderInstance(transferBatchFile.bank.code, transferBatchFileItemList, transferBatchFile.batchNumber, transferBatchFile.operationType)
        builder.buildFile()

        updateBatchFileWithFileNameAndContents(transferBatchFile.id, builder.getFileContents(), builder.getfileName())

        for (TransferBatchFileItem transferBatchFileItem in transferBatchFileItemList) {
            transferBatchFileItem.status = TransferBatchFileItemStatus.BANK_PROCESSING
            transferBatchFileItem.save(failOnError: true)
        }
    }

    private void validateCreditTransferRequestFinancialTransaction(CreditTransferRequest creditTransferRequest) {
        Boolean hasFinancialTransaction = FinancialTransaction.query([creditTransferRequest: creditTransferRequest, exists: true]).get().asBoolean()
        if (!hasFinancialTransaction) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.validateCreditTransferRequestFinancialTransaction() -> CreditTransferRequest não possui vinculo com a FinancialTransaction. [creditTransferRequest.id: ${creditTransferRequest.id}]")
            throw new BusinessException("A transferência ${creditTransferRequest.id} não foi lançada no extrato")
        }
    }
}
