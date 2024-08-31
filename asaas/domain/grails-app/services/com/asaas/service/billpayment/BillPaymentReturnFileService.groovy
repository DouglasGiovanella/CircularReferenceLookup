package com.asaas.service.billpayment

import com.asaas.bill.BillStatus
import com.asaas.bill.batchfile.BillPaymentBatchFileItemStatus
import com.asaas.bill.bb.BancoDoBrasilBillPaymentReturnFileRetriever
import com.asaas.bill.returnfile.BillPaymentReturnFileItemStatus
import com.asaas.bill.returnfile.BillPaymentReturnFileItemVO
import com.asaas.bill.returnfile.BillPaymentReturnFileVO
import com.asaas.bill.safra.SafraBillPaymentReturnCodes
import com.asaas.bill.safra.SafraBillPaymentReturnFileRetriever
import com.asaas.domain.bill.BillPaymentBank
import com.asaas.domain.bill.BillPaymentBatchFileItem
import com.asaas.domain.bill.BillPaymentReturnFile
import com.asaas.domain.bill.BillPaymentReturnFileItem
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.util.Environment

@Transactional
class BillPaymentReturnFileService {

	def billPayService
	def billService
	def messageService
    def smsSenderService

	public void retrieveAndProcessReturnFiles() {
		List<BillPaymentBank> billPaymentBankList = BillPaymentBank.executeQuery("from BillPaymentBank where deleted = false")

		for (BillPaymentBank billPaymentBank : billPaymentBankList) {
			retrieveAndProcessReturnFile(billPaymentBank)
		}
	}

	public void retrieveAndProcessReturnFile(BillPaymentBank billPaymentBank) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		try {
            AsaasLogger.info("Retrieving billPaymentReturnFile [${billPaymentBank.bank.name}]")

			List<BillPaymentReturnFileVO> billPaymentReturnFileVOList = retrieveBillPaymentReturnFileVOList(billPaymentBank)

			if (!billPaymentReturnFileVOList || billPaymentReturnFileVOList.size() == 0) {
				return
			}

			for (BillPaymentReturnFileVO billPaymentReturnFileVO : billPaymentReturnFileVOList) {
				processBillPaymentReturnFile(billPaymentReturnFileVO, billPaymentBank)
				moveProcessedFile(billPaymentReturnFileVO, billPaymentBank)
                AsaasLogger.info("---> Finished processing billPaymentReturnFile ${billPaymentReturnFileVO.fileName} [${billPaymentBank.bank.name}]")
			}
		} catch (Exception exception) {
            AsaasLogger.warn("BillPaymentReturnFileService.retrieveAndProcessReturnFile >> Erro ao processar arquivo de retorno para o banco ${billPaymentBank.bank.name}.", exception)
		}
	}

	private List<BillPaymentReturnFileVO> retrieveBillPaymentReturnFileVOList(BillPaymentBank billPaymentBank) {
		List<BillPaymentReturnFileVO> billPaymentReturnFileVOList

		if (billPaymentBank.bank.code == SupportedBank.SAFRA.code()) {
            billPaymentReturnFileVOList = SafraBillPaymentReturnFileRetriever.retrieve(billPaymentBank)
        } else if (billPaymentBank.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) {
            billPaymentReturnFileVOList = BancoDoBrasilBillPaymentReturnFileRetriever.retrieve(billPaymentBank)
		} else {
			throw new Exception("Não foi possível encontrar a classe BillPaymentReturnFileRetriever do banco ${billPaymentBank.bank.name} para obter e processar os arquivos de retorno.")
		}

		return billPaymentReturnFileVOList
	}

	private void processBillPaymentReturnFile(BillPaymentReturnFileVO billPaymentReturnFileVO, BillPaymentBank billPaymentBank) {
		Long billPaymentReturnFileId

		BillPaymentReturnFile.withNewTransaction { status ->
			try {
                AsaasLogger.info("Processing bill payment return file ${billPaymentReturnFileVO.fileName} [${billPaymentBank.bank.name}]")

				if (BillPaymentReturnFile.alreadyProcessed(billPaymentReturnFileVO.fileName)) {
					throw new Exception("Arquivo de retorno ${billPaymentReturnFileVO.fileName} já foi processado.")
				}

				billPaymentReturnFileVO.parseFileContents()

                AsaasLogger.info("Found ${billPaymentReturnFileVO.billPaymentReturnFileItemVOList.size()} items in bill payment return file ${billPaymentReturnFileVO.fileName} [${billPaymentBank.bank.name}]")

				if (billPaymentReturnFileVO.billPaymentReturnFileItemVOList.size() == 0) {
					return
				}

				billPaymentReturnFileId = saveBillPaymentReturnFile(billPaymentReturnFileVO)?.id

				if (!billPaymentReturnFileId) {
					throw new Exception("BillPaymentReturnFile nulo. Ocorreu um erro ao processar o arquivo de retorno ${billPaymentReturnFileVO.fileName}")
				}
			} catch (Exception exception) {
				status.setRollbackOnly()
                AsaasLogger.warn("BillPaymentReturnFileService.processBillPaymentReturnFile >> Erro ao processar arquivo de retorno para ${billPaymentBank.bank.name}", exception)
				throw exception
			}
		}

		if (!billPaymentReturnFileId) {
			return
		}

		BillPaymentReturnFile.withNewTransaction { status ->
			try {
				processFailedItems(billPaymentReturnFileId)
			} catch (Exception exception) {
                AsaasLogger.warn("BillPaymentReturnFileService.processBillPaymentReturnFile >> Erro ao processar itens com falha do retorno de pagamento de contas do banco ${billPaymentBank.bank.name}.", exception)
				throw exception
			}
		}

		BillPaymentReturnFile.withNewTransaction { status ->
			try {
				processPaidItems(billPaymentReturnFileId)
			} catch (Exception exception) {
                AsaasLogger.warn("BillPaymentReturnFileService.processBillPaymentReturnFile >> Erro ao processar itens pagos do retorno de pagamento de contas do banco ${billPaymentBank.bank.name}.", exception)
	        	throw exception
	        }
		}

		BillPaymentReturnFile.withNewTransaction {
			sendReport(billPaymentReturnFileId)
		}
	}

	private BillPaymentReturnFile saveBillPaymentReturnFile(BillPaymentReturnFileVO billPaymentReturnFileVO) {
		BillPaymentReturnFile billPaymentReturnFile = new BillPaymentReturnFile()

		billPaymentReturnFile.billPaymentBank = billPaymentReturnFileVO.billPaymentBank
		billPaymentReturnFile.fileName = billPaymentReturnFileVO.fileName
		billPaymentReturnFile.fileContents = billPaymentReturnFileVO.fileContents

		billPaymentReturnFile.save(flush: true, failOnError: true)

        AsaasLogger.info("Saving BillPaymentReturnFile id [${billPaymentReturnFile.id}] for ${billPaymentReturnFileVO.fileName} [${billPaymentReturnFileVO.billPaymentBank.bank.name}]")

		Integer itemCount = 0
		Integer itemTotal = billPaymentReturnFileVO.billPaymentReturnFileItemVOList.size()

		for (BillPaymentReturnFileItemVO itemVO : billPaymentReturnFileVO.billPaymentReturnFileItemVOList) {
			saveBillPaymentReturnFileItem(itemVO, billPaymentReturnFile)

			itemCount++
            AsaasLogger.info("Saving BillPaymentReturnFileItem ${itemCount}/${itemTotal}")
		}

		return billPaymentReturnFile
	}

	private BillPaymentReturnFileItem saveBillPaymentReturnFileItem(BillPaymentReturnFileItemVO itemVO, BillPaymentReturnFile billPaymentReturnFile) {
        BillPaymentReturnFileItem billPaymentReturnFileItem = new BillPaymentReturnFileItem()

        billPaymentReturnFileItem.billPaymentBatchFileItem = BillPaymentBatchFileItem.get(itemVO.billPaymentBatchFileItemId)
        billPaymentReturnFileItem.returnStatus = itemVO.status
	    billPaymentReturnFileItem.returnCodes = itemVO.returnCodes.join(",")
    	billPaymentReturnFileItem.paidValue = itemVO.status == BillPaymentReturnFileItemStatus.PAID ? itemVO.paidValue : 0
    	billPaymentReturnFileItem.billPaymentReturnFile = billPaymentReturnFile

    	billPaymentReturnFileItem.save(flush: true, failOnError: true)

        billPaymentReturnFile.addToItems(billPaymentReturnFileItem)

        return billPaymentReturnFileItem
	}

	private void processFailedItems(Long billPaymentReturnFileId) {
        AsaasLogger.info("Verifying failed items...")

		BillPaymentReturnFile billPaymentReturnFile = BillPaymentReturnFile.get(billPaymentReturnFileId)

		Set<BillPaymentReturnFileItem> failedItems = billPaymentReturnFile.items.findAll { it.returnStatus == BillPaymentReturnFileItemStatus.FAILED }

		if (!failedItems) return

        AsaasLogger.warn("BillPaymentReturnFile [${billPaymentReturnFile.id}] has not paid items!")

		for (BillPaymentReturnFileItem failedItem : failedItems) {
			failedItem.billPaymentBatchFileItem.status = BillPaymentBatchFileItemStatus.FAILED
			failedItem.billPaymentBatchFileItem.save(flush: true, failOnError: true)
			List<String> billPaymentReturnFileErrorReasons = []
			failedItem.returnCodes.tokenize(",").each { String inconsistencyCode ->
				if (inconsistencyCode in SafraBillPaymentReturnCodes.clientInconsistencyCodes) {
					billPaymentReturnFileErrorReasons.add(Utils.getMessageProperty("billPaymentReturnFile.422.occurrency.${inconsistencyCode}"))
				}
			}

			if (billPaymentReturnFileErrorReasons.size() > 0) {
				billService.setAsFailed(failedItem.billPaymentBatchFileItem.bill, null)
				messageService.sendBillPaymentReturnFileErrorToCustomer(failedItem.billPaymentBatchFileItem.bill, billPaymentReturnFileErrorReasons)
                smsSenderService.send("Seu pagamento falhou: " + billPaymentReturnFileErrorReasons.join(", ").toLowerCase(), failedItem.billPaymentBatchFileItem.bill.customer.mobilePhone, false, [:])
			} else if (failedItem.dateCreated.clearTime() <= failedItem.billPaymentBatchFileItem.bill.scheduleDate.clearTime()) {
				billService.setAsPending(failedItem.billPaymentBatchFileItem.bill)
			}
		}
	}

	private void processPaidItems(Long billPaymentReturnFileId) {
        AsaasLogger.info("Processing paid items...")

		BillPaymentReturnFile billPaymentReturnFile = BillPaymentReturnFile.get(billPaymentReturnFileId)

		Set<BillPaymentReturnFileItem> paidItems = billPaymentReturnFile.items.findAll{ it.returnStatus == BillPaymentReturnFileItemStatus.PAID }

		if (!paidItems) return

		for (BillPaymentReturnFileItem paidItem : paidItems) {
			paidItem.billPaymentBatchFileItem.status = BillPaymentBatchFileItemStatus.PAID
			paidItem.billPaymentBatchFileItem.save(flush: true, failOnError: true)

			if (paidItem.billPaymentBatchFileItem.bill.status == BillStatus.BANK_PROCESSING) {
                billPayService.pay(paidItem.billPaymentBatchFileItem.bill.id, billPaymentReturnFile.billPaymentBank.bank.id)
			}
		}
	}

	private void moveProcessedFile(BillPaymentReturnFileVO billPaymentReturnFileVO, BillPaymentBank billPaymentBank) {
        AsaasLogger.info("Moving processed bill payment return file ${billPaymentReturnFileVO.fileName}")

		if (billPaymentReturnFileVO.billPaymentBank.bank.code == SupportedBank.SAFRA.code()) {
            SafraBillPaymentReturnFileRetriever.moveProcessedFile(billPaymentReturnFileVO.fileName)
        } else if (billPaymentReturnFileVO.billPaymentBank.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) {
            BancoDoBrasilBillPaymentReturnFileRetriever.moveProcessedFile(billPaymentReturnFileVO.fileName)
		} else {
			throw new Exception("Não foi possível encontrar a classe BillPaymentReturnFileRetriever do banco ${billPaymentBank.bank.name} para mover o arquivo de retorno processado.")
		}
	}

	private void sendReport(Long billPaymentReturnFileId) {
		BillPaymentReturnFile billPaymentReturnFile = BillPaymentReturnFile.get(billPaymentReturnFileId)

		messageService.sendBillPaymentReturnFileReport(billPaymentReturnFile)
	}

}
