package com.asaas.service.creditcard

import com.asaas.adyen.AdyenSettlementFileParser
import com.asaas.adyen.AdyenSettlementFileRetriever
import com.asaas.cielo.CieloSettlementFileParser
import com.asaas.cielo.CieloSettlementFileRetriever
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationBatchStatus
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationFileVO
import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.creditcardacquireroperation.CreditCardAcquirerOperationBatch
import com.asaas.domain.file.AsaasFile
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.rede.nexxera.RedeSettlementFileRetriever
import com.asaas.user.UserUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditCardAcquirerOperationBatchService {

    def creditCardAcquirerOperationBatchProcessService
    def fileService
    def messageService
    def paymentService

    public CreditCardAcquirerOperationBatch save(CreditCardAcquirer acquirer, File file, String fileName) {
        validateSave(file, fileName)

        AsaasFile asaasFile = fileService.createFile(null, file, fileName)

        CreditCardAcquirerOperationBatch creditCardAcquirerOperationBatch = new CreditCardAcquirerOperationBatch()
        creditCardAcquirerOperationBatch.creator = UserUtils.getCurrentUser()
        creditCardAcquirerOperationBatch.status = CreditCardAcquirerOperationBatchStatus.WAITING_PARSE_FILE
        creditCardAcquirerOperationBatch.acquirer = acquirer
        creditCardAcquirerOperationBatch.fileName = fileName
        creditCardAcquirerOperationBatch.file = asaasFile
        creditCardAcquirerOperationBatch.save(failOnError: true)

        return creditCardAcquirerOperationBatch
    }

    public void saveCreditCardAcquirerOperationList(CreditCardAcquirerOperationBatch creditCardAcquirerOperationBatch) {
        List<Map> creditCardAcquirerOperationMapList
        AsaasFile asaasFile = creditCardAcquirerOperationBatch.file

        AsaasLogger.info("CreditCardAcquirerOperationBatchService.saveCreditCardAcquirerOperationList >>> Iniciando parse do arquivo de liquidação ${creditCardAcquirerOperationBatch.fileName}.")

        switch (creditCardAcquirerOperationBatch.acquirer) {
            case CreditCardAcquirer.CIELO:
                creditCardAcquirerOperationMapList = processCieloEdiSettlementFile(new FileInputStream(asaasFile.getFile()))
                break
            case CreditCardAcquirer.ADYEN:
                creditCardAcquirerOperationMapList = AdyenSettlementFileParser.parse(new FileInputStream(asaasFile.getFile()), creditCardAcquirerOperationBatch)
                break
            case CreditCardAcquirer.REDE:
                // O arquivo importado será utilizado para conciliação da Simetrik, por enquanto não será processado
                creditCardAcquirerOperationMapList = []
                break
            default:
                throw new RuntimeException("Adquirente não disponível.")
        }

        if (!creditCardAcquirerOperationMapList) {
            if (creditCardAcquirerOperationBatch.automaticRoutine) {
                creditCardAcquirerOperationBatchProcessService.setBatchAsProcessed(creditCardAcquirerOperationBatch.id)

                return
            }

            throw new BusinessException("O arquivo importado não possui nenhuma operação")
        }

        AsaasLogger.info("CreditCardAcquirerOperationBatchService.saveCreditCardAcquirerOperationList >>> Arquivo ${creditCardAcquirerOperationBatch.fileName} contém ${creditCardAcquirerOperationMapList.size()} registro a serem processados.")

        for (Map creditCardAcquirerOperationMap in creditCardAcquirerOperationMapList) {
            CreditCardAcquirerOperation creditCardAcquirerOperation = new CreditCardAcquirerOperation(creditCardAcquirerOperationMap)
            creditCardAcquirerOperation.creditCardAcquirerOperationBatch = creditCardAcquirerOperationBatch
            creditCardAcquirerOperation.save(failOnError: true)
        }

        creditCardAcquirerOperationBatch.status = CreditCardAcquirerOperationBatchStatus.PENDING
        creditCardAcquirerOperationBatch.save(failOnError: true)

        AsaasLogger.info("CreditCardAcquirerOperationBatchService.saveCreditCardAcquirerOperationList >>> Finalizado processo de inclusão de CreditCardAcquirerOperation [Adquirente: ${creditCardAcquirerOperationBatch.acquirer}]")
    }

    public void automaticFileRetrieve(CreditCardAcquirer acquirer) {
        List<CreditCardAcquirerOperationFileVO> creditCardAcquirerOperationFileVoList

        switch (acquirer) {
            case CreditCardAcquirer.CIELO:
                creditCardAcquirerOperationFileVoList = CieloSettlementFileRetriever.retrieveSettlementFile()
                break
            case CreditCardAcquirer.ADYEN:
                creditCardAcquirerOperationFileVoList = AdyenSettlementFileRetriever.retrieveSettlementFile()
                break
            case CreditCardAcquirer.REDE:
                creditCardAcquirerOperationFileVoList = RedeSettlementFileRetriever.retrieveSettlementFile()
                break
            default:
                throw new RuntimeException("Adquirente ${acquirer} não disponível para a busca automática do arquivo de liquidação.")
        }

        Utils.forEachWithFlushSession(creditCardAcquirerOperationFileVoList, 5, { CreditCardAcquirerOperationFileVO creditCardAcquirerOperationFileVO ->
            Utils.withNewTransactionAndRollbackOnError ( {
                File file = FileUtils.buildFileFromBytes(creditCardAcquirerOperationFileVO.fileContents.bytes)

                save(acquirer, file, creditCardAcquirerOperationFileVO.fileName)
                deleteRetrievedFileIfNecessary(acquirer, creditCardAcquirerOperationFileVO.originalFilename)
                moveRetrievedFileIfNecessary(acquirer, creditCardAcquirerOperationFileVO.fileName)
            }, [logErrorMessage: "CreditCardAcquirerOperationBatchService.automaticFileRetrieve >>> Erro ao salvar o arquivo de liquidação [Nome do Arquivo: ${creditCardAcquirerOperationFileVO.fileName}]."])
        })
    }

    public void processFile() {
        List<Long> creditCardAcquirerOperationBatchIdList = CreditCardAcquirerOperationBatch.query([column: "id", disableSort: true, status: CreditCardAcquirerOperationBatchStatus.WAITING_PARSE_FILE]).list()

        Utils.forEachWithFlushSession(creditCardAcquirerOperationBatchIdList, 5, { Long creditCardAcquirerOperationBatchId ->
            Utils.withNewTransactionAndRollbackOnError ( {
                CreditCardAcquirerOperationBatch creditCardAcquirerOperationBatch = CreditCardAcquirerOperationBatch.query([id: creditCardAcquirerOperationBatchId]).get()
                creditCardAcquirerOperationBatch.automaticRoutine = true

                saveCreditCardAcquirerOperationList(creditCardAcquirerOperationBatch)
            }, [logErrorMessage: "CreditCardAcquirerOperationBatchService.processFile >>> Erro ao processar o arquivo de liquidação [creditCardAcquirerOperationBatchId: ${creditCardAcquirerOperationBatchId}]."])
        })
    }

    private void validateSave(File file, String fileName) {
        if (file.length() == 0) throw new BusinessException("O arquivo está vazio.")

        if (fileName) {
            Boolean isCreditCardAcquirerOperationBatchFileAlreadyImported = CreditCardAcquirerOperationBatch.query([fileName: fileName, exists: true]).get().asBoolean()
            if (isCreditCardAcquirerOperationBatchFileAlreadyImported) throw new BusinessException("O arquivo ${fileName} já foi importado!")
        } else {
            throw new BusinessException("Informe o nome do arquivo.")
        }
    }

    private List<Map> processCieloEdiSettlementFile(InputStream inputStreamFile) {
        CieloSettlementFileParser cieloSettlementFileParser = new CieloSettlementFileParser()
        List<Map> parsedOperationMapList = cieloSettlementFileParser.parse(inputStreamFile)

        List<Map> creditCardAcquirerOperationMapList = []
        for (Map parsedOperationMap in parsedOperationMapList) {
            if (!parsedOperationMap.transactionIdentifier) {
                messageService.sendCieloTransactionWithoutTid(parsedOperationMap.creditCardNumber, parsedOperationMap.authorizationNumber)
                continue
            }

            if (parsedOperationMap.rejectionCode) {
                messageService.sendCieloEdiTransactionRejected(parsedOperationMap.transactionIdentifier, parsedOperationMap.rejectionCode, parsedOperationMap.authorizationNumber)
                continue
            }

            creditCardAcquirerOperationMapList.add(parsedOperationMap)
        }

        AsaasLogger.info("processCieloEdiSettlementFile -> processamento list creditCardAcquirerOperationMapList")
        return creditCardAcquirerOperationMapList
    }

    private void deleteRetrievedFileIfNecessary(CreditCardAcquirer acquirer, String originalFileName) {
        if (acquirer.isAdyen()) AdyenSettlementFileRetriever.deleteRetrievedSettlementFile(originalFileName)
    }

    private void moveRetrievedFileIfNecessary(CreditCardAcquirer acquirer, String fileName) {
        if (acquirer.isRede()) RedeSettlementFileRetriever.moveRetrievedFile(fileName)
    }
}
