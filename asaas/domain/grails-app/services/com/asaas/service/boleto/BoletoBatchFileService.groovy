package com.asaas.service.boleto

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.boleto.RegisteredBoletoValidator
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.boleto.batchfile.BoletoBatchFileBuilderBase
import com.asaas.boleto.batchfile.BoletoBatchFileStatus
import com.asaas.boleto.bb.BancoDoBrasilBoletoBatchFileBuilder
import com.asaas.boleto.bb.BancoDoBrasilBoletoBatchFileSender
import com.asaas.boleto.bradesco.BradescoBoletoBatchFileBuilder
import com.asaas.boleto.bradesco.BradescoBoletoBatchFileSender
import com.asaas.boleto.safra.SafraBoletoBatchFileBuilder
import com.asaas.boleto.safra.SafraBoletoBatchFileSender
import com.asaas.boleto.santander.SantanderBoletoBatchFileBuilder
import com.asaas.boleto.santander.SantanderBoletoBatchFileSender
import com.asaas.boleto.sicredi.SicrediBoletoBatchFileBuilder
import com.asaas.boleto.sicredi.SicrediBoletoBatchFileSender
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoBatchFile
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.exception.BatchFileException
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.util.Environment

@Transactional
class BoletoBatchFileService {

	def messageService
	def boletoBatchFileItemService

	public void buildBoletoBatchFiles() {
		buildSicrediBoletoBatchFile()
		buildSantanderBoletoBatchFile()
		buildSafraBoletoBatchFileWithSafraBankSlip()
		buildBancoDoBrasilBoletoBatchFile()
	}

	public void buildSantanderBoletoBatchFile() {
	    Long santanderBoletoBankId = BoletoBank.withOfflineRegistration([bankCode: SupportedBank.SANTANDER.code()]).get()?.id
	    buildAndProcessBatchFile(santanderBoletoBankId)
	}

    public void buildSantanderOnlineBoletoBatchFile() {
        Long santanderOnlineBoletoBankId = BoletoBank.withOnlineRegistration([bankCode: SupportedBank.SANTANDER.code()]).get()?.id
        buildAndProcessBatchFile(santanderOnlineBoletoBankId)
    }

	public void buildSafraBoletoBatchFileWithItauBankSlip() {
		Long safraBoletoBankIdWithItauBankSlip = BoletoBank.query([bankCode: SupportedBank.SAFRA.code(), bankSlipBankCode: SupportedBank.ITAU.code()]).get()?.id
		buildAndProcessBatchFile(safraBoletoBankIdWithItauBankSlip)
	}

	public void buildSafraBoletoBatchFileWithSafraBankSlip() {
		Long safraBoletoBankIdWithSafraBankSlip = BoletoBank.query([bankCode: SupportedBank.SAFRA.code(), bankSlipBankCode: SupportedBank.SAFRA.code()]).get()?.id
		buildAndProcessBatchFile(safraBoletoBankIdWithSafraBankSlip)
	}

	public void buildSicrediBoletoBatchFile() {
		BoletoBank sicrediBoletoBank = BoletoBank.query([bankCode: SupportedBank.SICREDI.code()]).get()
		buildAndProcessBatchFile(sicrediBoletoBank?.id)
	}

	public void buildBancoDoBrasilBoletoBatchFile() {
		BoletoBank bbBoletoBank = BoletoBank.withOnlineRegistration([bankCode: SupportedBank.BANCO_DO_BRASIL.code()]).get()
		buildAndProcessBatchFile(bbBoletoBank?.id)
	}

    public void buildBradescoOnlineBoletoBatchFile() {
        BoletoBank bradescoBoletoBank = BoletoBank.withOnlineRegistration([bankCode: SupportedBank.BRADESCO.code()]).get()
        buildAndProcessBatchFile(bradescoBoletoBank?.id)
    }

	public void buildAndProcessBatchFile(Long boletoBankId) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

        if (!boletoBankId) return

		Long boletoBatchFileId

		BoletoBatchFile.withNewTransaction { transaction ->
			try {
				BoletoBank boletoBank = BoletoBank.get(boletoBankId)

				List<BoletoBatchFileItem> boletoList = listPendingItems(boletoBank, null)

                AsaasLogger.info("${boletoBank.bank.name} : ${boletoList.size()} BoletoBatchFileItem")

				List<BoletoBatchFileItem> validBoletos = []
				List<BoletoBatchFileItem> invalidBoletos = []

				for (BoletoBatchFileItem item in boletoList) {
					if (item.action == BoletoAction.DELETE) {
						validBoletos.add(item)
						continue
					}

					if (RegisteredBoletoValidator.validate(item.payment)) {
						validBoletos.add(item)
					} else {
						invalidBoletos.add(item)
					}
				}

                AsaasLogger.info("${boletoBank.bank.name} : ${validBoletos.size()} valid items, ${invalidBoletos.size()} invalid items")

				boletoBatchFileItemService.proccessInvalidItems(invalidBoletos)

				if (!validBoletos) return

				boletoBatchFileId = buildAndSaveBoletoBatchFile(boletoBank, validBoletos).id
			} catch (Exception exception) {
				transaction.setRollbackOnly()
                AsaasLogger.error("BoletoBatchFileService.buildAndProcessBatchFile >>> Ocorreu um erro ao gerar o arquivo de remessa de boletos registrados para o [boletoBankId: ${boletoBankId}]", exception)
			}
		}

		if (!boletoBatchFileId) return

		BoletoBatchFile.withNewTransaction { transaction ->
			try {
				sendBoletoBatchFileToBank(boletoBatchFileId)
			} catch (Exception exception) {
				transaction.setRollbackOnly()
                AsaasLogger.error("BoletoBatchFileService.buildAndProcessBatchFile >>> Ocorreu um erro ao enviar o arquivo de remessa de boletos registrados para o [boletoBankId: ${boletoBankId}]", exception)
			}
		}
	}

	public void verifyIfBatchFilesWereCreated() {
		List<BoletoBank> enabledBoletoBankList = BoletoBank.batchEnabled().list()

        for (BoletoBank boletoBank : enabledBoletoBankList) {
			verifyFileCreation(boletoBank)
		}
	}

    public void verifyIfBatchFilesWereSent() {
        List<BoletoBank> enabledBoletoBankList = BoletoBank.batchEnabled().list()

        for (BoletoBank boletoBank : enabledBoletoBankList) {
			verifyFileSend(boletoBank)
		}
    }

	private BoletoBatchFile buildAndSaveBoletoBatchFile(BoletoBank boletoBank, List<BoletoBatchFileItem> boletoList) {
		def boletoBatchFileBuilder

        switch (boletoBank.bank.code) {
            case SupportedBank.SICREDI.code():
                boletoBatchFileBuilder = new SicrediBoletoBatchFileBuilder(boletoBank, boletoList)
                break

            case SupportedBank.SANTANDER.code():
                boletoBatchFileBuilder = new SantanderBoletoBatchFileBuilder(boletoBank, boletoList)
                break

            case SupportedBank.SAFRA.code():
                boletoBatchFileBuilder = new SafraBoletoBatchFileBuilder(boletoBank, boletoList)
                break

            case SupportedBank.BANCO_DO_BRASIL.code():
                boletoBatchFileBuilder = new BancoDoBrasilBoletoBatchFileBuilder(boletoBank, boletoList)
                break

            case SupportedBank.BRADESCO.code():
                boletoBatchFileBuilder = new BradescoBoletoBatchFileBuilder(boletoBank, boletoList)
                break

            default:
                throw new Exception("Não foi possível encontrar a classe BoletoBatchFileBuilder do banco ${boletoBank.bank.name}.")
        }

		boletoBatchFileBuilder.buildFile()

		if (!boletoBatchFileBuilder.validateLayout()) {
			throw new BatchFileException("Layout inválido no arquivo de remessa do banco ${boletoBank.bank.name}.", boletoBatchFileBuilder.fileContents)
		}

		BoletoBatchFile boletoBatchFile = saveBoletoBatchFile(boletoBatchFileBuilder)

		boletoList.removeAll(boletoBatchFileBuilder.itemsWithError.findAll{ it }.boletoBatchFileItem)

		boletoBatchFileItemService.setItemsAsSent(boletoBatchFile, boletoList)

		processItemsWithError(boletoBatchFileBuilder.itemsWithError)

		return boletoBatchFile
	}

	private void sendBoletoBatchFileToBank(Long boletoBatchFileId) {
		BoletoBatchFile boletoBatchFile = BoletoBatchFile.get(boletoBatchFileId)

		if (boletoBatchFile.items.size() == 0) return

		Boolean sendResult

		if (boletoBatchFile.boletoBank.bank.code == SupportedBank.SICREDI.code()) sendResult = SicrediBoletoBatchFileSender.send(boletoBatchFile)
		else if (boletoBatchFile.boletoBank.bank.code == SupportedBank.SANTANDER.code()) sendResult = SantanderBoletoBatchFileSender.send(boletoBatchFile)
		else if (boletoBatchFile.boletoBank.bank.code == SupportedBank.SAFRA.code()) sendResult = SafraBoletoBatchFileSender.send(boletoBatchFile)
		else if (boletoBatchFile.boletoBank.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) sendResult = BancoDoBrasilBoletoBatchFileSender.send(boletoBatchFile)
        else if (boletoBatchFile.boletoBank.bank.code == SupportedBank.BRADESCO.code()) sendResult = BradescoBoletoBatchFileSender.send(boletoBatchFile)
		else throw new Exception("Não foi possível encontrar a classe BoletoBatchFileSender do banco ${boletoBatchFile.boletoBank.bank.name} para enviar o arquivo de remessa.")

		if (sendResult) {
			boletoBatchFile.status = BoletoBatchFileStatus.SENT
			boletoBatchFile.save(failOnError: true)
		} else {
            AsaasLogger.error("BoletoBatchFileService.sendBoletoBatchFileToBank >>> Falha no envio do arquivo. Erro ao salvar o arquivo no FTP")
		}
	}

	private BoletoBatchFile saveBoletoBatchFile(BoletoBatchFileBuilderBase batchFileBuilder) {
		BoletoBatchFile boletoBatchFile = new BoletoBatchFile()

		boletoBatchFile.fileName = batchFileBuilder.fileName
		boletoBatchFile.fileContents = batchFileBuilder.fileContents
		boletoBatchFile.batchNumber = batchFileBuilder.batchNumber.toString()
		boletoBatchFile.boletoBank = batchFileBuilder.boletoBank

		boletoBatchFile.save(flush: true, failOnError: true)

		return boletoBatchFile
	}

	private void verifyFileCreation(BoletoBank boletoBank) {
		if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

		Integer numberOfPendingBoletoBatchFileItems = listPendingItems(boletoBank).size()
		Integer numberOfCreatedFilesToday = BoletoBatchFile.createdToday([boletoBank: boletoBank]).count()

		if (numberOfPendingBoletoBatchFileItems > 0 && numberOfCreatedFilesToday == 0) {
            AsaasLogger.error("BoletoBatchFileService.verifyFileCreation >>> Nenhum arquivo de remessa de boletos foi gerado e enviado hoje [boletoBankBankName: ${boletoBank.bank.name}]")
		}
	}

	private void verifyFileSend(BoletoBank boletoBank) {
		try {
            Map resultMap

            if (boletoBank.bank.code == SupportedBank.SICREDI.code()) resultMap = SicrediBoletoBatchFileSender.verifyFileSend(boletoBank)
            else if (boletoBank.bank.code == SupportedBank.SANTANDER.code()) resultMap = SantanderBoletoBatchFileSender.verifyFileSend(boletoBank)
            else if (boletoBank.bank.code == SupportedBank.SAFRA.code()) resultMap = SafraBoletoBatchFileSender.verifyFileSend(boletoBank)
            else if (boletoBank.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) resultMap = BancoDoBrasilBoletoBatchFileSender.verifyFileSend(boletoBank)
            else if (boletoBank.bank.code == SupportedBank.BRADESCO.code()) resultMap = BradescoBoletoBatchFileSender.verifyFileSend(boletoBank)
            else throw new Exception("Não foi possível encontrar a classe BoletoBatchFileSender do banco ${boletoBank.bank.name} para verificar o envio do arquivo de remessa.")

			if (!resultMap.result) {
                AsaasLogger.error("BoletoBatchFileService.verifyFileSend >> Erro consultar envio de remessa de boletos para o banco ${boletoBank.bank.name}. Erros: ${resultMap.messages}.")
            }
        } catch (Exception e) {
            AsaasLogger.error("BoletoBatchFileService.verifyFileSend >> Erro consultar envio de remessa de boletos para o banco ${boletoBank.bank.name}.", e)
        }
	}

	private List<BoletoBatchFileItem> listPendingItems(BoletoBank boletoBank, Long maxItemId = null) {
        if (boletoBank.onlineRegistrationEnabled) {
            return listPendingDeleteItems(boletoBank, maxItemId)
        }

		List<BoletoBatchFileItem> pendingItems = listPendingCreateItems(boletoBank, maxItemId)

		Integer availableCountForDeleteItems = AsaasApplicationHolder.config.asaas.registeredBoleto.boletoBatchFile.maxActionsPerBatchFile - pendingItems.size()

		if (availableCountForDeleteItems > 0) {
			pendingItems.addAll(listPendingDeleteItems(boletoBank, maxItemId, availableCountForDeleteItems))
		}

		return pendingItems
	}

	private List<BoletoBatchFileItem> listPendingCreateItems(BoletoBank boletoBank, Long maxItemId) {
		List<BoletoBatchFileItem> pendingItems = []

		pendingItems.addAll(BoletoBatchFileItem.listPendingItems(boletoBank, BoletoAction.CREATE, AsaasApplicationHolder.config.asaas.registeredBoleto.boletoBatchFile.maxActionsPerBatchFile, maxItemId))

		return pendingItems
	}

	private List<BoletoBatchFileItem> listPendingDeleteItems(BoletoBank boletoBank, Long maxItemId, Integer maxItemCount) {
		return BoletoBatchFileItem.listPendingItems(boletoBank, BoletoAction.DELETE, maxItemCount, maxItemId)
	}

	private void processItemsWithError(List<Map> items) {
		if (items.size() == 0) return

		boletoBatchFileItemService.deleteItems(items.findAll{ it }.boletoBatchFileItem)

		String bankName = items[0].boletoBatchFileItem.payment.boletoBank.bank.name

        List<Map> detailedErrorList = items.collect { Map item ->
            [boletoBatchFileItemId: item.boletoBatchFileItem.id, message: item.message]
        }

        AsaasLogger.error("BoletoBatchFileService.processItemsWithError >> Erro ao gerar o arquivo de remessa de boletos registrados para o banco ${bankName}. Detalhes: ${detailedErrorList}")
	}

    private List<BoletoBatchFileItem> listPendingDeleteItems(BoletoBank boletoBank, Long maxItemId) {
        return listPendingDeleteItems(boletoBank, maxItemId, AsaasApplicationHolder.config.asaas.registeredBoleto.boletoBatchFile.maxActionsPerBatchFile)
    }

}
