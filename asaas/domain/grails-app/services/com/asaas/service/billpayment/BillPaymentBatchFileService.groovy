package com.asaas.service.billpayment

import com.asaas.asyncaction.AsyncActionType
import com.asaas.bill.BillStatus
import com.asaas.bill.batchfile.BillPaymentAction
import com.asaas.bill.batchfile.BillPaymentBatchFileBuilderBase
import com.asaas.bill.batchfile.BillPaymentBatchFileItemStatus
import com.asaas.bill.bb.BancoDoBrasilBillPaymentBatchFileBuilder
import com.asaas.bill.bb.BancoDoBrasilBillPaymentBatchFileTransmitter
import com.asaas.bill.safra.SafraBillPaymentBatchFileBuilder
import com.asaas.bill.safra.SafraBillPaymentBatchFileTransmitter
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillPaymentBank
import com.asaas.domain.bill.BillPaymentBatchFile
import com.asaas.domain.bill.BillPaymentBatchFileItem
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BillPaymentBatchFileService {

    def billPayService
    def messageService
    def pushNotificationRequestBillService
    def asyncActionService

	public BillPaymentBatchFile buildBatchFile(Long billPaymentBankId, List<Long> billIdList) {
		if (!billIdList.size()) throw new BusinessException("Nenhum item selecionado para gerar a remessa.")
		BillPaymentBatchFile billPaymentBatchFile
		BillPaymentBatchFile.withNewTransaction { status ->
			try {
				BillPaymentBank billPaymentBank

				if (billPaymentBankId) {
					billPaymentBank = BillPaymentBank.get(billPaymentBankId)
				} else {
					billPaymentBank = BillPaymentBank.query([bankCode: SupportedBank.SAFRA.code]).get()
				}

                AsaasLogger.info("Building billPaymentBatchFile [${billPaymentBank.bank.name}]")
				billPaymentBatchFile = new BillPaymentBatchFile()
				billPaymentBatchFile.billPaymentBank = billPaymentBank
				billPaymentBatchFile.batchValue = 0
				billPaymentBatchFile.save(flush: true, failOnError: true)

				List<BillPaymentBatchFileItem> billPaymentBatchFileItemList = saveItems(billPaymentBatchFile, billIdList)

				BillPaymentBatchFileBuilderBase batchFileBuilder

				if (billPaymentBank.bank.code == SupportedBank.SAFRA.code) {
					batchFileBuilder = new SafraBillPaymentBatchFileBuilder(billPaymentBank, billPaymentBatchFileItemList)
				} else if (billPaymentBank.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) {
                    batchFileBuilder = new BancoDoBrasilBillPaymentBatchFileBuilder(billPaymentBank, billPaymentBatchFileItemList)
                }

				batchFileBuilder.buildFile()

				billPaymentBatchFile.fileName = batchFileBuilder.fileName
				billPaymentBatchFile.fileContents = batchFileBuilder.fileContents
				billPaymentBatchFile.batchNumber = batchFileBuilder.batchNumber
				billPaymentBatchFile.batchValue = batchFileBuilder.batchValue
				billPaymentBatchFile.save(flush: true, failOnError: true)

                AsaasLogger.info("Finished building billPaymentBatchFile ${billPaymentBatchFile.fileName} [${billPaymentBank.bank.name}]")
			} catch (Exception e) {
				status.setRollbackOnly()
				throw e
			}
		}

		messageService.sendBillPaymentBatchFileCreatedOrSent(billPaymentBatchFile.id, "Remessa de pagamento de contas criada com sucesso")

		return billPaymentBatchFile
	}

	public BillPaymentBatchFile transmit(Long billPaymentBatchFileId) {
		BillPaymentBatchFile billPaymentBatchFile = BillPaymentBatchFile.get(billPaymentBatchFileId)

        AsaasLogger.info("Transmitting billPaymentBatchFile ${billPaymentBatchFile.fileName} [${billPaymentBatchFile.billPaymentBank.bank.name}]")

		if (billPaymentBatchFile.status in [Status.SENT, Status.AUTHORIZED]) {
			throw new Exception("Esta remessa já foi transmitida ao banco.")
		}

		if (billPaymentBatchFile.getItemsCount() == 0) {
			throw new Exception("Esta remessa não pode ser transmitida porque não possui itens para pagamento.")
		}

		Boolean transmissionResult

		if (billPaymentBatchFile.billPaymentBank.bank.code == SupportedBank.SAFRA.code()) {
            transmissionResult = SafraBillPaymentBatchFileTransmitter.transmit(billPaymentBatchFile)
        } else if (billPaymentBatchFile.billPaymentBank.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) {
            transmissionResult = BancoDoBrasilBillPaymentBatchFileTransmitter.transmit(billPaymentBatchFile)
		} else {
			throw new Exception("Não foi possível encontrar a classe BillPaymentBatchFileTransmitter do banco ${billPaymentBatchFile.billPaymentBank.bank.name} para transmitir o arquivo de remessa.")
		}

		if (transmissionResult) {
			billPaymentBatchFile.status = Status.SENT
			billPaymentBatchFile.transmissionDate = new Date()
			billPaymentBatchFile.save(flush: true, failOnError: true)

			setBatchFileItemsAsScheduled(billPaymentBatchFile)
		} else {
			throw new Exception("Falha na transmissão do arquivo. Erro ao salvar o arquivo no FTP.")
		}

		messageService.sendBillPaymentBatchFileCreatedOrSent(billPaymentBatchFileId, "Remessa de pagamento de contas enviada com sucesso")

		return billPaymentBatchFile
	}

	public BillPaymentBatchFile cancelBatchFile(Long billPaymentBatchFileId) {
		BillPaymentBatchFile.withNewTransaction { status ->
			try {
				BillPaymentBatchFile billPaymentBatchFile = BillPaymentBatchFile.get(billPaymentBatchFileId)

				if (!billPaymentBatchFile.canCancel()) {
					throw new Exception("Esta remessa de pagamento de contas não pode ser cancelada.")
				}

				billPaymentBatchFile.status = Status.CANCELLED
				billPaymentBatchFile.save(flush: true, failOnError: true)

				for (BillPaymentBatchFileItem item in billPaymentBatchFile.items) {
					item.status = BillPaymentBatchFileItemStatus.CANCELLED
					item.save(flush: true, failOnError: true)

					if (item.bill.status == BillStatus.BANK_PROCESSING) {
						item.bill.status = BillStatus.PENDING
                        item.bill.statusReason = null
						item.bill.save(flush: true, failOnError: true)
                        pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_PENDING, item.bill)
					}
				}

				return billPaymentBatchFile
			} catch (Exception exception) {
				status.setRollbackOnly()
                AsaasLogger.error("BillPaymentBatchFileService.cancelBatchFile >> Erro ao cancelar remessa de pagamento de contas. [billPaymentBatchFileId: ${billPaymentBatchFileId}]", exception)
				throw exception
			}
		}
	}

    public void processSetBillPaymentBatchFileAsPaidAsyncAction() {
        final Integer maxItems = 500
        final Integer minItemsPerThread = 250

        Map pendingAsyncActionData = asyncActionService.getPending(AsyncActionType.SET_BILL_PAYMENT_BATCH_FILE_AS_PAID)

        if (!pendingAsyncActionData) return

        BillPaymentBatchFile billPaymentBatchFile = BillPaymentBatchFile.read(pendingAsyncActionData.billPaymentBatchFileId)

        AsaasLogger.info("Setting as paid: billPaymentBatchFile [${billPaymentBatchFile.id}] ${billPaymentBatchFile.fileName} [${billPaymentBatchFile.billPaymentBank.bank.name}]")

        List<Long> scheduledBillPaymentBatchFileItemList = BillPaymentBatchFileItem.query([column: 'id', billPaymentBatchFile: billPaymentBatchFile, status: BillPaymentBatchFileItemStatus.SCHEDULED]).list(max: maxItems)

        if (!scheduledBillPaymentBatchFileItemList) {
            Utils.withNewTransactionAndRollbackOnError({
                billPaymentBatchFile = BillPaymentBatchFile.get(pendingAsyncActionData.billPaymentBatchFileId)
                billPaymentBatchFile.status = Status.AUTHORIZED
                billPaymentBatchFile.save(failOnError: true)

                messageService.sendBillPaymentBatchFileSetAsPaid(billPaymentBatchFile)
                asyncActionService.delete(pendingAsyncActionData.asyncActionId)
            }, [logErrorMessage: "BillPaymentBatchFileService.processSetBillPaymentBatchFileAsPaidAsyncAction >> Erro ao atualizar status de BillPaymentBatchFile [ID: ${pendingAsyncActionData.billPaymentBatchFileId}]"])

            return
        }

        ThreadUtils.processWithThreadsOnDemand(scheduledBillPaymentBatchFileItemList, minItemsPerThread, { List<Long> billPaymentBatchFileItemIdList ->
            for (Long billPaymentBatchFileItemId : billPaymentBatchFileItemIdList) {
                Utils.withNewTransactionAndRollbackOnError({
                    BillPaymentBatchFileItem billPaymentBatchFileItem = BillPaymentBatchFileItem.get(billPaymentBatchFileItemId)
                    if (billPaymentBatchFileItem.status != BillPaymentBatchFileItemStatus.SCHEDULED) {
                        return
                    }

                    billPaymentBatchFileItem.status = BillPaymentBatchFileItemStatus.AUTHORIZED
                    billPaymentBatchFileItem.save(failOnError: true)

                    if (billPaymentBatchFileItem.bill.status == BillStatus.BANK_PROCESSING) {
                        AsaasLogger.info("Setting bill [${billPaymentBatchFileItem.bill.id}] as paid")
                        billPayService.pay(billPaymentBatchFileItem.bill.id, billPaymentBatchFileItem.billPaymentBatchFile.billPaymentBank.bank.id)
                    }
                }, [logErrorMessage: "BillPaymentBatchFileService.processSetBillPaymentBatchFileAsPaidAsyncAction >> Erro ao atualizar status de BillPaymentBatchFileItem [ID: ${billPaymentBatchFileItemId}]"])
            }
        })
    }

	public void setAsPaid(Long billPaymentBatchFileId) {
         asyncActionService.saveSetBillPaymentBatchFileAsPaid(billPaymentBatchFileId)

         BillPaymentBatchFile billPaymentBatchFile = BillPaymentBatchFile.get(billPaymentBatchFileId)
         billPaymentBatchFile.status = Status.PROCESSING_PAYMENT
         billPaymentBatchFile.save(failOnError: true)
	}

	public void verifyNotSetAsPaid() {
		Date endDate30MinutesAgo = CustomDateUtils.sumMinutes(new Date(), -30)

		List<BillPaymentBatchFile> billPaymentBatchFileList = BillPaymentBatchFile.query(['dateCreated[ge]': new Date().clearTime(), 'dateCreated[le]': endDate30MinutesAgo, status: Status.SENT]).list()

		for (BillPaymentBatchFile billPaymentBatchFile in billPaymentBatchFileList) {
			messageService.sendNotSetAsPaidBillPaymentBatchFileAlert(billPaymentBatchFile.id)
		}
	}

    private List<BillPaymentBatchFileItem> saveItems(BillPaymentBatchFile billPaymentBatchFile, List<Long> billIdList) {
        List<Bill> readyForPaymentList = Bill.forPaymentToday([idList: billIdList, asaasBankSlip: false]).list()

        List<BillPaymentBatchFileItem> itemList = []

        for (Bill bill in readyForPaymentList) {
            AsaasLogger.info("Creating billPaymentBatchFileItem for bill [${bill.id}]")

            BillPaymentBatchFileItem item = new BillPaymentBatchFileItem()
            item.bill = bill
            item.action = BillPaymentAction.CREATE
            item.billPaymentBatchFile = billPaymentBatchFile
            item.save(flush: true, failOnError: true)

            bill.status = BillStatus.BANK_PROCESSING
            bill.statusReason = null
            bill.save(flush: true, failOnError: true)

            pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_BANK_PROCESSING, bill)

            itemList.add(item)
        }

        return itemList
    }

    private void setBatchFileItemsAsScheduled(BillPaymentBatchFile billPaymentBatchFile) {
        for (BillPaymentBatchFileItem item in billPaymentBatchFile.items) {
            item.status = BillPaymentBatchFileItemStatus.SCHEDULED
            item.save(flush: true, failOnError: true)
        }
    }
}
