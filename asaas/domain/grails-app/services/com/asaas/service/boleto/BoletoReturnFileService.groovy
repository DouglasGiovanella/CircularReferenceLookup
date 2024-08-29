package com.asaas.service.boleto

import com.asaas.boleto.asaas.AsaasBoletoReturnFileRetriever
import com.asaas.billinginfo.BillingType
import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.boleto.bb.BancoDoBrasilBoletoReturnFileRetriever
import com.asaas.boleto.bradesco.BradescoBoletoReturnFileRetriever
import com.asaas.boleto.returnfile.BoletoReturnFileItemVO
import com.asaas.boleto.returnfile.BoletoReturnFileStatus
import com.asaas.boleto.returnfile.BoletoReturnFileVO
import com.asaas.boleto.returnfile.BoletoReturnStatus
import com.asaas.boleto.safra.SafraBoletoReturnFileRetriever
import com.asaas.boleto.santander.SantanderBoletoReturnFileRetriever
import com.asaas.boleto.sicredi.SicrediBoletoReturnFileRetriever
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.boleto.BoletoReturnFile
import com.asaas.domain.boleto.BoletoReturnFileItem
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.util.Environment

@Transactional
class BoletoReturnFileService {

	def messageSource
    def paymentConfirmRequestService
	def boletoBatchFileItemService

	public void retrieveAndProcessSantanderReturnFile() {
        BoletoBank santanderBoletoBank = BoletoBank.withOfflineRegistration([bankCode: SupportedBank.SANTANDER.code()]).get()

		retrieveAndProcessReturnFile(santanderBoletoBank)
	}

	public void retrieveAndProcessSafraReturnFile() {
		BoletoBank safraBoletoBank = BoletoBank.query([bankCode: SupportedBank.SAFRA.code(), bankSlipBankCode: SupportedBank.SAFRA.code()]).get()

		retrieveAndProcessReturnFile(safraBoletoBank)
	}

	public void retrieveAndProcessSicrediReturnFile() {
		BoletoBank sicrediBoletoBank = BoletoBank.query([bankCode: SupportedBank.SICREDI.code()]).get()

		retrieveAndProcessReturnFile(sicrediBoletoBank)
	}

	public void retrieveAndProcessBancoDoBrasilReturnFile() {
		BoletoBank bancoDoBrasilBoletoBank = BoletoBank.withOnlineRegistration([bankCode: SupportedBank.BANCO_DO_BRASIL.code()]).get()

		retrieveAndProcessReturnFile(bancoDoBrasilBoletoBank)
	}

    public void retrieveAndProcessBradescoReturnFile() {
        BoletoBank bradescoBoletoBank = BoletoBank.withOnlineRegistration([bankCode: SupportedBank.BRADESCO.code()]).get()

        retrieveAndProcessReturnFile(bradescoBoletoBank)
    }

    public void retrieveAndProcessAsaasReturnFile() {
        BoletoBank asaasBoletoBank = BoletoBank.withOnlineRegistration([bankCode: SupportedBank.ASAAS.code()]).get()

        retrieveAndProcessReturnFile(asaasBoletoBank)
    }

	public void retrieveAndProcessReturnFile(BoletoBank boletoBank) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		try {
			List<BoletoReturnFileVO> boletoReturnFileVOList = retrieveBoletoReturnFileVOList(boletoBank)

			if (!boletoReturnFileVOList || boletoReturnFileVOList.size() == 0) return

			for (BoletoReturnFileVO boletoReturnFileVO : boletoReturnFileVOList) {
				if (BoletoReturnFile.alreadyProcessed(boletoReturnFileVO.fileName)) {
					AsaasLogger.error("Arquivo de retorno de boletos já foi processado: ${boletoReturnFileVO.fileName} [${boletoReturnFileVO.bank.name}]")
					continue
				}
				processBoletoReturnFile(boletoReturnFileVO)
				moveProcessedFile(boletoReturnFileVO)
			}
        } catch (Exception exception) {
            AsaasLogger.error("BoletoReturnFileService.retrieveAndProcessReturnFile >> Erro ao processar arquivo de retorno. [boletoBank: ${boletoBank.id}, bank: ${boletoBank.bank.name}]", exception)
        }
	}

	private List<BoletoReturnFileVO> retrieveBoletoReturnFileVOList(BoletoBank boletoBank) {
        switch (boletoBank.bank.code) {
            case SupportedBank.SICREDI.code():
                return SicrediBoletoReturnFileRetriever.retrieve(boletoBank)
                break

            case SupportedBank.SANTANDER.code():
                return SantanderBoletoReturnFileRetriever.retrieve(boletoBank)
                break

            case SupportedBank.SAFRA.code():
                return SafraBoletoReturnFileRetriever.retrieve(boletoBank)
                break

            case SupportedBank.BANCO_DO_BRASIL.code():
                return BancoDoBrasilBoletoReturnFileRetriever.retrieve(boletoBank)
                break

            case SupportedBank.BRADESCO.code():
                return BradescoBoletoReturnFileRetriever.retrieve(boletoBank)
                break

            case SupportedBank.ASAAS.code():
                return AsaasBoletoReturnFileRetriever.retrieve(boletoBank)

            default:
                throw new Exception("Não foi possível encontrar a classe BoletoReturnFileRetriever do banco ${boletoBank.bank.name} para obter e processar os arquivos de retorno.")
        }
	}

	public void processBoletoReturnFile(BoletoReturnFileVO boletoReturnFileVO) {
		Long boletoReturnFileId

		BoletoReturnFile.withNewTransaction { status ->
			try {
				boletoReturnFileVO.parseFileContents()

				// Remove o BoletoBank pois o convênio 6802621 do Santander não é registrado
				if (boletoReturnFileVO.bank.code == SupportedBank.SANTANDER.code() && Integer.parseInt(boletoReturnFileVO.headerInfo.covenant) == Integer.parseInt("6802621")) {
					boletoReturnFileVO.boletoBank = null
				}

				boletoReturnFileId = saveBoletoReturnFile(boletoReturnFileVO)?.id

				if (!boletoReturnFileId) throw new Exception("BoletoReturnFile nulo. Ocorreu um erro ao processar o arquivo ${boletoReturnFileVO.fileName}")
			} catch (Exception exception) {
                AsaasLogger.error("BoletoReturnFileService.processBoletoReturnFile >> Erro ao processar arquivo de retorno de boletos: ${boletoReturnFileVO.fileName}", exception)
                throw exception
			}
		}

		Payment.withNewTransaction { status ->
			try {
				processPaidItems(boletoReturnFileId)
			} catch (Exception exception) {
                AsaasLogger.error("BoletoReturnFileService.processBoletoReturnFile >> Erro ao criar itens de confirmação de boleto do arquivo ${boletoReturnFileVO.fileName}", exception)
                throw exception
            }
		}

        Utils.withNewTransactionAndRollbackOnError({
            BoletoReturnFile boletoReturnFile = BoletoReturnFile.get(boletoReturnFileId)
            boletoReturnFile.importStatus = BoletoReturnFileStatus.COMPLETE
            boletoReturnFile.save(flush: true, failOnError: true)
        }, [logErrorMessage: "BoletoReturnFileService.saveBoletoReturnFile >> erro ao atualizar importStatus para COMPLETE [${boletoReturnFileId}]"])
	}

	private BoletoReturnFile saveBoletoReturnFile(BoletoReturnFileVO boletoReturnFileVO) {
        BoletoReturnFile boletoReturnFile = BoletoReturnFile.query([fileName: boletoReturnFileVO.fileName, boletoBank: boletoReturnFileVO.boletoBank, dateCreated: new Date().clearTime()]).get()

        if (boletoReturnFile?.importStatus == BoletoReturnFileStatus.COMPLETE) {
            throw new Exception("Arquivo de retorno de boletos [${boletoReturnFile.fileName}] já foi importado completamente")
        }

        if (!boletoReturnFile) {
            Utils.withNewTransactionAndRollbackOnError({
                boletoReturnFile = new BoletoReturnFile(boletoBank: boletoReturnFileVO.boletoBank, bank: boletoReturnFileVO.bank, fileName: boletoReturnFileVO.fileName)
                boletoReturnFile.importStatus = BoletoReturnFileStatus.PARTIAL
                boletoReturnFile.save(flush: true, failOnError: true)
            })
        }

        Long boletoReturnFileId = boletoReturnFile.id
        BoletoReturnFileStatus importStatus = boletoReturnFile.importStatus

        Integer numberOfThreads = 6
        if ([Payment.ASAAS_ONLINE_BOLETO_BANK_ID, Payment.BRADESCO_ONLINE_BOLETO_BANK_ID].contains(boletoReturnFileVO.boletoBank.id)) numberOfThreads = 16

        Utils.processWithThreads(boletoReturnFileVO.boletoReturnFileItemVOList, numberOfThreads, { List<BoletoReturnFileItemVO> items ->
            Utils.forEachWithFlushSession(items, 100, { BoletoReturnFileItemVO item ->
                Utils.withNewTransactionAndRollbackOnError({
                    saveBoletoReturnFileItem(item, boletoReturnFileId, importStatus)
                }, [
                    logErrorMessage: "BoletoReturnFileService.saveBoletoReturnFile >> erro ao salvar item hash [${boletoReturnFileVO.fileName}_${item.itemIdentifier}]",
                    onError: { Exception exception -> throw exception }
                ])
            })
        })

		return boletoReturnFile
	}

    private BoletoReturnFileItem saveBoletoReturnFileItem(BoletoReturnFileItemVO item, Long boletoReturnFileId, BoletoReturnFileStatus importStatus) {
        if (importStatus == BoletoReturnFileStatus.PARTIAL && item.itemIdentifier) {
            BoletoReturnFileItem boletoReturnFileItem = BoletoReturnFileItem.query([itemHash: item.itemIdentifier]).get()
            if (boletoReturnFileItem) {
                AsaasLogger.info("BoletoReturnFileService.saveBoletoReturnFileItem : item [${item.itemIdentifier}] já importado")
                return boletoReturnFileItem
            }
        }

        BoletoReturnFile boletoReturnFile = BoletoReturnFile.load(boletoReturnFileId)

        BoletoReturnFileItem boletoReturnFileItem = new BoletoReturnFileItem()

        boletoReturnFileItem.nossoNumero = item.nossoNumero
        boletoReturnFileItem.returnStatus = item.returnStatus
        boletoReturnFileItem.ocurrenceDate = item.ocurrenceDate
        boletoReturnFileItem.creditAvailableDate = item.creditAvailableDate
        boletoReturnFileItem.value = item.value
        boletoReturnFileItem.paidValue = item.paidValue
        boletoReturnFileItem.returnCode = item.returnCode
        boletoReturnFileItem.returnReason = item.returnReason
        boletoReturnFileItem.boletoReturnFile = boletoReturnFile
        boletoReturnFileItem.receiverBankCode = item.receiverBankCode
        boletoReturnFileItem.receiverAgency = item.receiverAgency
        boletoReturnFileItem.status = Status.PENDING
        boletoReturnFileItem.paymentChannel = item.paymentChannel

        if (item.itemIdentifier) {
            boletoReturnFileItem.itemHash = item.itemIdentifier
        }

        boletoReturnFileItem.save(flush: true, failOnError: true)

        return boletoReturnFileItem
	}

    public void processRegistrationStatusUpdate() {
        Long boletoReturnFileId = BoletoReturnFile.query([dateCreated: new Date().clearTime(), column: "id", "registrationStatusPendingToProcessToday[exists]": true]).get()

        if (!boletoReturnFileId) return

        updateBoletoRegistrationStatus(boletoReturnFileId, 2500)
    }

	public void updateBoletoRegistrationStatus(Long boletoReturnFileId, Integer itemLimit) {
		BoletoReturnFile boletoReturnFile = BoletoReturnFile.get(boletoReturnFileId)

		if (!boletoReturnFile.boletoBank) return

		List<Long> returnFileItemIdsWithoutBatchFileItem = []
		List<Long> returnFileItemIdsWithRegistrationStatusUpdateError = []

		List<Long> pendingBoletoReturnFileItemIds = []

        pendingBoletoReturnFileItemIds.addAll(BoletoReturnFileItem.query([column: 'id', boletoReturnFileId: boletoReturnFileId, returnStatus: BoletoReturnStatus.CREATE_SUCCESSFUL, status: Status.PENDING]).list([max: itemLimit]))

        if (pendingBoletoReturnFileItemIds.size() < itemLimit) {
            pendingBoletoReturnFileItemIds.addAll(BoletoReturnFileItem.query([column: 'id', boletoReturnFileId: boletoReturnFileId, returnStatus: BoletoReturnStatus.CREATE_REJECTED, status: Status.PENDING]).list([max: (itemLimit - pendingBoletoReturnFileItemIds.size())]))
        }

        if (pendingBoletoReturnFileItemIds.size() < itemLimit) {
		    pendingBoletoReturnFileItemIds.addAll(BoletoReturnFileItem.query([column: 'id', boletoReturnFileId: boletoReturnFileId, returnStatus: BoletoReturnStatus.DELETE_SUCCESSFUL, status: Status.PENDING]).list([max: (itemLimit - pendingBoletoReturnFileItemIds.size())]))
        }

        Utils.forEachWithFlushSession(pendingBoletoReturnFileItemIds, 100, { Long itemId ->
            Utils.withNewTransactionAndRollbackOnError({
				BoletoReturnFileItem item = BoletoReturnFileItem.get(itemId)

                item.boletoBatchFileItem = item.boletoReturnFile.boletoBank ? findBoletoBatchFileItem(item.returnStatus, item.nossoNumero, item.boletoReturnFile.boletoBank) : null

 				item.status = Status.PROCESSED
				item.save(flush: true, failOnError: true)

				if (!item.boletoBatchFileItem && item.returnStatus in [BoletoReturnStatus.CREATE_SUCCESSFUL, BoletoReturnStatus.CREATE_REJECTED]) {
					returnFileItemIdsWithoutBatchFileItem.add(itemId)
					return
				}

                switch (item.returnStatus) {
                    case BoletoReturnStatus.DELETE_SUCCESSFUL:
                        updatePaymentRegistrationStatus(item, BoletoRegistrationStatus.NOT_REGISTERED)
                        break
                    case BoletoReturnStatus.CREATE_SUCCESSFUL:
                        updatePaymentRegistrationStatus(item, BoletoRegistrationStatus.SUCCESSFUL)
                        break
                    case BoletoReturnStatus.CREATE_REJECTED:
                        updatePaymentRegistrationStatus(item, BoletoRegistrationStatus.FAILED)
                        boletoBatchFileItemService.deleteNotSentItemsOfFailedRegistration(item.boletoBatchFileItem.payment)
                        break
                }
            }, [onError: { returnFileItemIdsWithRegistrationStatusUpdateError.add(itemId) }])
        })

		if (returnFileItemIdsWithoutBatchFileItem.size()) {
			processReturnFileItemsWithoutBatchFileItem(boletoReturnFile, returnFileItemIdsWithoutBatchFileItem)
		}

		if (returnFileItemIdsWithRegistrationStatusUpdateError.size()) {
			AsaasLogger.warn("BoletoReturnFileService.updateBoletoRegistrationStatus >> Existem boletos com erro na atualização de status de registro. [boletoReturnFileId: ${boletoReturnFileId}]")
		}
	}

	private void updatePaymentRegistrationStatus(BoletoReturnFileItem returnItem, BoletoRegistrationStatus boletoRegistrationStatus) {
		Payment paymentToUpdateRegistrationStatus

		if (returnItem.boletoBatchFileItem) {
			paymentToUpdateRegistrationStatus = returnItem.boletoBatchFileItem.payment
		} else {
			paymentToUpdateRegistrationStatus = Payment.uniqueByBoletoBank(returnItem.nossoNumero, returnItem.boletoReturnFile.boletoBank).get()
		}

		if (!paymentToUpdateRegistrationStatus || returnItem.nossoNumero != paymentToUpdateRegistrationStatus.getCurrentBankSlipNossoNumero()) {
			return
		}

		paymentToUpdateRegistrationStatus.updateRegistrationStatus(boletoRegistrationStatus)
	}

	private void processReturnFileItemsWithoutBatchFileItem(BoletoReturnFile boletoReturnFile, List<Long> returnFileItemIdsWithoutBatchFileItem) {
		List<Long> failedReturnItemIds = []

		List<BoletoReturnStatus> boletoReturnStatusToSkip = [
			BoletoReturnStatus.ACTION_REJECTED,
			BoletoReturnStatus.PAID,
			BoletoReturnStatus.PAID_UNREGISTERED,
			BoletoReturnStatus.BOLETO_BANK_FEE,
			BoletoReturnStatus.DDA_NOT_RECOGNIZED,
			BoletoReturnStatus.DDA_RECOGNIZED,
			BoletoReturnStatus.DELETE_SUCCESSFUL
		]

		for (Long itemId : returnFileItemIdsWithoutBatchFileItem) {
			BoletoReturnFileItem item = BoletoReturnFileItem.get(itemId)

			if (item.returnStatus in boletoReturnStatusToSkip) {
				continue
			}

			Payment payment = Payment.uniqueByBoletoBank(item.nossoNumero, item.boletoReturnFile.boletoBank).get()

			if (payment && payment.deleted) {
				if (item.returnStatus == BoletoReturnStatus.CREATE_SUCCESSFUL) {
					boletoBatchFileItemService.delete(payment)
				}
			} else {
				failedReturnItemIds.add(itemId)
			}
		}

		if (failedReturnItemIds.size() > 0) {
			AsaasLogger.warn("BoletoReturnFileService.processReturnFileItemsWithoutBatchFileItem >> Existem itens com erro de processamento. [boletoReturnFileId: ${boletoReturnFile.id}]")
		}
	}

	public void processPaidItems(Long boletoReturnFileId) {
		BoletoReturnFile boletoReturnFile = BoletoReturnFile.get(boletoReturnFileId)

		List<BoletoReturnFileItem> paidItems = BoletoReturnFileItem.query([boletoReturnFileId: boletoReturnFileId, returnStatusList: BoletoReturnStatus.paid(), status: Status.PENDING]).list()

		if (!paidItems) return

		sendItemsToApproval(boletoReturnFile, paidItems)

        final Integer maxNumberOfItemsToUpdate = 5000

        for (List<BoletoReturnFileItem> paidItemSubList : paidItems.collate(maxNumberOfItemsToUpdate)) {
            BoletoReturnFileItem.executeUpdate("update BoletoReturnFileItem set status = :processed, version = version + 1, lastUpdated = :now where id in (:paidItemSubList)", [processed: Status.PROCESSED, paidItemSubList: paidItemSubList.collect { it.id }, now: new Date()])
        }
	}

	private void sendItemsToApproval(BoletoReturnFile boletoReturnFile, List<BoletoReturnFileItem> boletoReturnFileItems) {
        List<Map> paidItems = boletoReturnFileItems.collect { BoletoReturnFileItem it ->
            [nossoNumero: it.nossoNumero, value: it.paidValue, date: it.ocurrenceDate, creditDate: it.creditAvailableDate, receiverBankCode: it.receiverBankCode, receiverAgency: it.receiverAgency]
        }

		paymentConfirmRequestService.saveList(BillingType.BOLETO, boletoReturnFile.bank, boletoReturnFile.boletoBank, paidItems, boletoReturnFile.id)
    }

    private BoletoBatchFileItem findBoletoBatchFileItem(BoletoReturnStatus returnStatus, String nossoNumero, BoletoBank boletoBank) {
        BoletoAction boletoAction = getBoletoAction(returnStatus)

        if (!boletoAction) return null

		BoletoBatchFileItem boletoBatchFileItem = BoletoBatchFileItem.findCorrespondingReturnItem(nossoNumero, boletoAction, boletoBank)

		if (!boletoBatchFileItem && boletoBank.bank.code == SupportedBank.SAFRA.code()) {
			BoletoBank otherSafraBoletoBank = BoletoBank.query([bankCode: SupportedBank.SAFRA.code(), "id[ne]": boletoBank.id]).get()
			boletoBatchFileItem = BoletoBatchFileItem.findCorrespondingReturnItem(nossoNumero, boletoAction, otherSafraBoletoBank)
		}

		return boletoBatchFileItem
    }

    private BoletoAction getBoletoAction(BoletoReturnStatus returnStatus) {
        switch (returnStatus) {
		case [BoletoReturnStatus.UPDATE_SUCCESSFUL, BoletoReturnStatus.UPDATE_REJECTED]:
			return BoletoAction.UPDATE
			break
		case [BoletoReturnStatus.CREATE_SUCCESSFUL, BoletoReturnStatus.CREATE_REJECTED]:
			return BoletoAction.CREATE
			break
		case [BoletoReturnStatus.DELETE_SUCCESSFUL, BoletoReturnStatus.DELETE_REJECTED]:
			return BoletoAction.DELETE
			break
        }
    }

    private void moveProcessedFile(BoletoReturnFileVO boletoReturnFileVO) {
        String bankCode = boletoReturnFileVO.bank.code

        switch (bankCode) {
            case SupportedBank.SICREDI.code():
                SicrediBoletoReturnFileRetriever.moveProcessedFile(boletoReturnFileVO.fileName, bankCode)
                break

            case SupportedBank.SANTANDER.code():
                SantanderBoletoReturnFileRetriever.moveProcessedFile(boletoReturnFileVO.fileName, bankCode)
                break

            case SupportedBank.SAFRA.code():
                SafraBoletoReturnFileRetriever.moveProcessedFile(boletoReturnFileVO.fileName, bankCode)
                break

            case SupportedBank.BANCO_DO_BRASIL.code():
                BancoDoBrasilBoletoReturnFileRetriever.moveProcessedFile(boletoReturnFileVO.fileName, bankCode)
                break

            case SupportedBank.BRADESCO.code():
                BradescoBoletoReturnFileRetriever.moveProcessedFile(boletoReturnFileVO.fileName, bankCode)
                break

            case SupportedBank.ASAAS.code():
                AsaasBoletoReturnFileRetriever.moveProcessedFile(boletoReturnFileVO.fileName, bankCode)
                break

            default:
                throw new Exception("Não foi possível encontrar a classe BoletoReturnFileRetriever do banco ${boletoReturnFileVO.bank.name} para mover o arquivo de retorno processado.")
        }
    }

	public void verifyIfReturnFilesWereReceivedToday() {
        List<Long> boletoBankWithReturnFileIdList = [Payment.SANTANDER_BOLETO_BANK_ID, Payment.SANTANDER_ONLINE_BOLETO_BANK_ID, Payment.BB_BOLETO_BANK_ID, Payment.BRADESCO_ONLINE_BOLETO_BANK_ID, Payment.ASAAS_ONLINE_BOLETO_BANK_ID]
        List<BoletoBank> boletoBankList = BoletoBank.query(["id[in]": boletoBankWithReturnFileIdList]).list()

		for (BoletoBank boletoBank in boletoBankList) {
			if (!BoletoReturnFile.byBoletoBankAndDate(boletoBank, new Date()).get()) {
                AsaasLogger.error("BoletoReturnFileService.verifyIfReturnFilesWereReceivedToday >> Arquivo de retorno de boletos não recebido [bankCode: ${boletoBank.bank.code}, bankName: ${boletoBank.bank.name}]")
			}
		}
	}

}
