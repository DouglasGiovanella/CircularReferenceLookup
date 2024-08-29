package com.asaas.service.creditcard

import com.asaas.adyen.AdyenPayoutFileParser
import com.asaas.adyen.AdyenSettlementFileRetriever
import com.asaas.cielo.CieloSettlementFileRetriever
import com.asaas.cielo.CieloSettlementPrevisionFileParser
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.PaymentCreditCardDepositBatchFileStatus
import com.asaas.creditcard.PaymentCreditCardDepositFileItemProcessingStatus
import com.asaas.creditcard.PaymentCreditCardDepositFileItemVO
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationFileVO
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.PaymentCreditCardDepositBatchFile
import com.asaas.domain.creditcard.PaymentCreditCardDepositFileItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentGatewayInfo
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.rede.RedeSettlementPrevisionFileParser
import com.asaas.rede.nexxera.RedeSettlementFileRetriever
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentCreditCardDepositService {

    def fileService
    def financialStatementTransitoryCreditCardFeeAsyncActionService
    def receivableAnticipationValidationService

    public PaymentCreditCardDepositBatchFile save(CreditCardAcquirer acquirer, String fileName, String fileContents, Boolean bypassProcessFile) {
        validateSave(fileName, fileContents, acquirer)

        AsaasFile asaasFile = fileService.createFile(fileName, fileContents)

        PaymentCreditCardDepositBatchFile paymentCreditCardDepositBatchFile = new PaymentCreditCardDepositBatchFile()
        paymentCreditCardDepositBatchFile.status = PaymentCreditCardDepositBatchFileStatus.PENDING
        paymentCreditCardDepositBatchFile.acquirer = acquirer
        paymentCreditCardDepositBatchFile.fileName = fileName
        paymentCreditCardDepositBatchFile.file = asaasFile
        paymentCreditCardDepositBatchFile.save(failOnError: true)

        if (!bypassProcessFile) processFile(paymentCreditCardDepositBatchFile)

        return paymentCreditCardDepositBatchFile
    }

	public List<Map> buildPaymentScheduleList(Customer customer, Map params) {
        Date startDate = params.startDate ? CustomDateUtils.toDate(params.startDate) : new Date().clearTime()
        Date endDate = params.endDate ? CustomDateUtils.toDate(params.endDate) : CustomDateUtils.addMonths(startDate, 1)

        if (startDate < new Date().clearTime()) throw new BusinessException("A data inicial não pode ser inferior a data atual.")

        if (endDate < startDate) throw new BusinessException("A data final não pode ser inferior a data inicial.")

        if ((endDate - startDate) > 360) throw new BusinessException("A diferença de dias entre as datas não pode ser superior a 360 dias.")

        List<Payment> paymentList = Payment.creditCardConfirmed([customer: customer, 'estimatedCreditDate[ge]': startDate, 'estimatedCreditDate[le]': endDate]).list()

        List<Map> paymentScheduleList = []

        startDate.upto(endDate) {
            Map scheduleMap = [:]

            scheduleMap.estimatedCreditDate = it
            List<Payment> paymentListForEstimatedCreditDate = paymentList.findAll{ it.estimatedCreditDate == scheduleMap.estimatedCreditDate }
            paymentList.removeAll(paymentListForEstimatedCreditDate)

            scheduleMap.transactionCount = paymentListForEstimatedCreditDate.size()
            scheduleMap.value = paymentListForEstimatedCreditDate*.value.sum() ?: 0
            scheduleMap.netValue = paymentListForEstimatedCreditDate*.netValue.sum() ?: 0
            scheduleMap.anticipatedValue = paymentListForEstimatedCreditDate.findAll{ it.anticipated }*.netValue.sum() ?: 0

            paymentScheduleList.add(scheduleMap)
        }

        return paymentScheduleList
    }

    public void fileAutomaticRetrieving(CreditCardAcquirer acquirer) {
        List<CreditCardAcquirerOperationFileVO> creditCardAcquirerOperationFileVoList

        switch (acquirer) {
            case CreditCardAcquirer.REDE:
                creditCardAcquirerOperationFileVoList = RedeSettlementFileRetriever.retrieveSettlementPrevisionFile()
                break
            case CreditCardAcquirer.CIELO:
                creditCardAcquirerOperationFileVoList = CieloSettlementFileRetriever.retrieveSettlementPrevisionFile()
                break
            case CreditCardAcquirer.ADYEN:
                creditCardAcquirerOperationFileVoList = AdyenSettlementFileRetriever.retrieveSettlementPrevisionFile()
                break
            default:
                throw new RuntimeException("Adquirente não disponível.")
        }

        Utils.forEachWithFlushSession(creditCardAcquirerOperationFileVoList, 5, { CreditCardAcquirerOperationFileVO creditCardAcquirerOperationFileVO ->
            Utils.withNewTransactionAndRollbackOnError ( {
                save(acquirer, creditCardAcquirerOperationFileVO.fileName, creditCardAcquirerOperationFileVO.fileContents, true)
                moveRetrievedFileIfNecessary(acquirer, creditCardAcquirerOperationFileVO.fileName)
                deleteRetrievedFileIfNecessary(acquirer, creditCardAcquirerOperationFileVO.originalFilename)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    AsaasLogger.warn("PaymentCreditCardDepositService.fileAutomaticRetrieving >>> Erro ao salvar o arquivo de previsao de liquidação [${creditCardAcquirerOperationFileVO.fileName}].", exception)
                }
            ])
        })
    }

    public void fileAutomaticProcessing() {
        List<Long> paymentCreditCardDepositBatchFileIdList = PaymentCreditCardDepositBatchFile.query([column: "id", disableSort: true, status: PaymentCreditCardDepositBatchFileStatus.PENDING]).list()

        if (!paymentCreditCardDepositBatchFileIdList) return

        Boolean hasError = false
        final Integer flushEvery = 5
        Utils.forEachWithFlushSession(paymentCreditCardDepositBatchFileIdList, flushEvery, { Long paymentCreditCardDepositBatchFileId ->
            Utils.withNewTransactionAndRollbackOnError ({
                PaymentCreditCardDepositBatchFile paymentCreditCardDepositBatchFile = PaymentCreditCardDepositBatchFile.get(paymentCreditCardDepositBatchFileId)

                processFile(paymentCreditCardDepositBatchFile)
            },
                [
                    logErrorMessage: "PaymentCreditCardDepositService.fileAutomaticProcessing >>> Erro ao processar o arquivo de previsão de liquidação [paymentCreditCardDepositBatchFileId: ${paymentCreditCardDepositBatchFileId}].",
                    onError: { hasError = true }
                ]
            )

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError ( {
                    setAsError(paymentCreditCardDepositBatchFileId)
                }, [logErrorMessage: "PaymentCreditCardDepositService.fileAutomaticProcessing >>> Erro ao setar como erro [paymentCreditCardDepositBatchFileId: ${paymentCreditCardDepositBatchFileId}]."])
            }
        })
    }

    public void updatePaymentEstimatedCreditDate() {
        final Integer maxItemsPerCycle = 4000

        List<Long> fileItemIdList = PaymentCreditCardDepositFileItem.query([
            column: "id",
            status: PaymentCreditCardDepositFileItemProcessingStatus.PENDING,
            limit: maxItemsPerCycle
        ]).list()

        List<Long> fileItemWithErrorIdList = []

        final Integer flushEvery = 50
        final Integer batchSize = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(fileItemIdList, batchSize, flushEvery, { Long fileItemId ->
            try {
                PaymentCreditCardDepositFileItem fileItem = PaymentCreditCardDepositFileItem.get(fileItemId)

                Boolean paymentAlreadyUpdated = fileItem.creditCardTransactionInfo.payment.estimatedCreditDate == fileItem.estimatedCreditDate
                if (paymentAlreadyUpdated || !fileItem.creditCardTransactionInfo.payment.isConfirmed()) {
                    fileItem.status = PaymentCreditCardDepositFileItemProcessingStatus.PROCESSED
                    fileItem.save(failOnError: true)
                    return
                }

                fileItem.creditCardTransactionInfo.payment.estimatedCreditDate = fileItem.estimatedCreditDate
                fileItem.status = PaymentCreditCardDepositFileItemProcessingStatus.PROCESSED
                fileItem.save(failOnError: true)

                receivableAnticipationValidationService.onPaymentChange(fileItem.creditCardTransactionInfo.payment)
            } catch (Exception exception) {
                if (!Utils.isLock(exception)) {
                    AsaasLogger.error("PaymentCreditCardDepositService.updatePaymentEstimatedCreditDate >> Não foi possível processar o item ID: [${fileItemId}].", exception)
                    fileItemWithErrorIdList += fileItemId
                }
            }
        }, [logErrorMessage: "PaymentCreditCardDepositService.updatePaymentEstimatedCreditDate >> Erro ao atualizar as datas estimadas de crédito", appendBatchToLogErrorMessage: true])

        if (fileItemWithErrorIdList) setListAsErrorWithNewTransaction(fileItemWithErrorIdList)
    }

    private void processFile(PaymentCreditCardDepositBatchFile paymentCreditCardDepositBatchFile) {
        FileInputStream fileInputStream = new FileInputStream(paymentCreditCardDepositBatchFile.file.getFile())
        List<PaymentCreditCardDepositFileItemVO> paymentCreditCardDepositFileItemVOList

        switch (paymentCreditCardDepositBatchFile.acquirer) {
            case CreditCardAcquirer.ADYEN:
                paymentCreditCardDepositFileItemVOList = AdyenPayoutFileParser.parse(fileInputStream)
                break
            case CreditCardAcquirer.CIELO:
                paymentCreditCardDepositFileItemVOList = CieloSettlementPrevisionFileParser.parse(fileInputStream)
                break
            case CreditCardAcquirer.REDE:
                paymentCreditCardDepositFileItemVOList = parseRedeFile(fileInputStream)
                break
            default:
                throw new RuntimeException("Adquirente não disponível.")
        }

        savePaymentPredictionInfo(paymentCreditCardDepositFileItemVOList)

        paymentCreditCardDepositBatchFile.status = PaymentCreditCardDepositBatchFileStatus.PROCESSED
        paymentCreditCardDepositBatchFile.processedDate = new Date()
        paymentCreditCardDepositBatchFile.save(failOnError: true)

        financialStatementTransitoryCreditCardFeeAsyncActionService.save(paymentCreditCardDepositBatchFile.acquirer, paymentCreditCardDepositBatchFile.processedDate.clone())
    }

    private void savePaymentPredictionInfo(List<PaymentCreditCardDepositFileItemVO> paymentCreditCardDepositFileItemVOList) {
        final Integer batchSize = 100
        final Integer flushEvery = 100

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(paymentCreditCardDepositFileItemVOList, batchSize, flushEvery, { PaymentCreditCardDepositFileItemVO fileItem ->
            CreditCardTransactionInfo creditCardTransactionInfo = CreditCardTransactionInfo.query([transactionIdentifier: fileItem.transactionIdentifier, installmentNumber: fileItem.installmentNumber]).get()

            if (!creditCardTransactionInfo) return

            PaymentCreditCardDepositFileItem paymentCreditCardDepositFileItem = new PaymentCreditCardDepositFileItem()
            paymentCreditCardDepositFileItem.creditCardTransactionInfo = creditCardTransactionInfo
            paymentCreditCardDepositFileItem.estimatedFeePercentage = fileItem.estimatedFeePercentage
            paymentCreditCardDepositFileItem.transactionFeeAmount = fileItem.transactionFeeAmount
            paymentCreditCardDepositFileItem.totalPaymentVolume = fileItem.totalPaymentVolume
            paymentCreditCardDepositFileItem.saleDate = fileItem.saleDate
            paymentCreditCardDepositFileItem.estimatedCreditDate = fileItem.estimatedCreditDate
            paymentCreditCardDepositFileItem.save(failOnError: true)
        }, [logErrorMessage: "PaymentCreditCardDepositService.savePaymentPredictionInfo >> Erro ao atualizar previsão de recebimento.",
            appendBatchToLogErrorMessage: true])
    }

    private void moveRetrievedFileIfNecessary(CreditCardAcquirer acquirer, String fileName) {
        if (acquirer.isRede()) RedeSettlementFileRetriever.moveRetrievedFile(fileName)
    }

    private void setAsError(Long paymentCreditCardDepositBatchFileId) {
        PaymentCreditCardDepositBatchFile paymentCreditCardDepositBatchFile = PaymentCreditCardDepositBatchFile.get(paymentCreditCardDepositBatchFileId)
        paymentCreditCardDepositBatchFile.status = PaymentCreditCardDepositBatchFileStatus.ERROR
        paymentCreditCardDepositBatchFile.save(failOnError: true)
    }

    private void validateSave(String fileName, String fileContents, CreditCardAcquirer acquirer) {
        if (!fileContents) throw new RuntimeException("O arquivo está vazio.")

        if (!fileName) {
            throw new RuntimeException("Informe o nome do arquivo.")
        } else {
            Boolean isPaymentCreditCardDepositBatchFileAlreadyImported = PaymentCreditCardDepositBatchFile.query([fileName: fileName, exists: true]).get().asBoolean()
            if (isPaymentCreditCardDepositBatchFileAlreadyImported) throw new RuntimeException("Arquivo já importado [${fileName}].")
        }

        if (acquirer.isAdyen() && !AdyenPayoutFileParser.isValidFile(fileContents)) throw new BusinessException("O arquivo da adquirente Adyen é inválido.")

        if (acquirer.isCielo()) {
            CieloSettlementPrevisionFileParser.validateFileHeader(fileContents)
        }
    }

    private List<PaymentCreditCardDepositFileItemVO> parseRedeFile(FileInputStream fileInputStream) {
        List<PaymentCreditCardDepositFileItemVO> paymentCreditCardDepositFileItemVOList = new RedeSettlementPrevisionFileParser().parse(fileInputStream)
        List<PaymentCreditCardDepositFileItemVO> parsedPaymentCreditCardDepositFileItemVOList = []

        parsedPaymentCreditCardDepositFileItemVOList.addAll(parseRedeInstallmentTransaction(paymentCreditCardDepositFileItemVOList))
        parsedPaymentCreditCardDepositFileItemVOList.addAll(parseRedeSingleTransaction(paymentCreditCardDepositFileItemVOList))

        return parsedPaymentCreditCardDepositFileItemVOList
    }

    private List<PaymentCreditCardDepositFileItemVO> parseRedeInstallmentTransaction(List<PaymentCreditCardDepositFileItemVO> paymentCreditCardDepositFileItemVOList) {
        Map queryParams = [:]
        queryParams."gateway[in]" = [CreditCardGateway.REDE, CreditCardGateway.ACQUIRING]
        queryParams."creditCardTransactionInfoAcquirer[exists]" = CreditCardAcquirer.REDE

        List<PaymentCreditCardDepositFileItemVO> parsedPaymentCreditCardDepositFileItemVOList = []

        Utils.forEachWithFlushSession(paymentCreditCardDepositFileItemVOList, 100, { PaymentCreditCardDepositFileItemVO fileItem ->
            Utils.withNewTransactionAndRollbackOnError ( {
                if (!fileItem.isInstallment) return

                queryParams.transactionIdentifier = fileItem.transactionIdentifier

                List<PaymentGatewayInfo> paymentGatewayInfoList = PaymentGatewayInfo.query(queryParams).list()

                if (!paymentGatewayInfoList) {
                    AsaasLogger.warn("PaymentCreditCardDepositService.parseRedeInstallmentTransaction >>> PaymentGatewayInfo não encontrado ${fileItem.transactionIdentifier}")
                    return
                }

                for (PaymentGatewayInfo paymentGatewayInfo : paymentGatewayInfoList) {
                    paymentGatewayInfo.mundiPaggTransactionKey = fileItem.saleSummaryNumber
                    paymentGatewayInfo.save(failOnError: true)

                    BigDecimal installmentFeeAmount = BigDecimalUtils.roundHalfUp(fileItem.transactionFeeAmount / fileItem.lastInstallmentNumber, 2)
                    if (paymentGatewayInfo.installmentNumber == fileItem.lastInstallmentNumber) {
                        installmentFeeAmount = fileItem.transactionFeeAmount - (installmentFeeAmount * (fileItem.lastInstallmentNumber - 1))
                    }

                    parsedPaymentCreditCardDepositFileItemVOList.add(new PaymentCreditCardDepositFileItemVO([
                        transactionIdentifier: paymentGatewayInfo.payment.creditCardTid,
                        totalPaymentVolume: fileItem.totalPaymentVolume,
                        transactionFeeAmount: installmentFeeAmount,
                        estimatedFeePercentage: fileItem.estimatedFeePercentage,
                        installmentNumber: paymentGatewayInfo.installmentNumber,
                        saleDate: fileItem.saleDate,
                        estimatedCreditDate: paymentGatewayInfo.payment.creditDate
                    ]))
                }
            }, [logErrorMessage: "PaymentCreditCardDepositService.parseRedeInstallmentTransaction >>> Erro ao atualizar o resumo de venda do parcelamento ${fileItem.transactionIdentifier}."] )
        })

        return parsedPaymentCreditCardDepositFileItemVOList
    }

    private List<PaymentCreditCardDepositFileItemVO> parseRedeSingleTransaction(List<PaymentCreditCardDepositFileItemVO> paymentCreditCardDepositFileItemVOList) {
        Map queryParams = [:]
        queryParams."gateway[in]" = [CreditCardGateway.REDE, CreditCardGateway.ACQUIRING]
        queryParams."creditCardTransactionInfoAcquirer[exists]" = CreditCardAcquirer.REDE

        List<PaymentCreditCardDepositFileItemVO> parsedPaymentCreditCardDepositFileItemVOList = []

        Utils.forEachWithFlushSession(paymentCreditCardDepositFileItemVOList, 100, { PaymentCreditCardDepositFileItemVO fileItem ->
            Utils.withNewTransactionAndRollbackOnError ( {
                if (fileItem.isInstallment) return

                queryParams.transactionIdentifier = fileItem.transactionIdentifier
                queryParams.installmentNumber = fileItem.installmentNumber

                PaymentGatewayInfo paymentGatewayInfo = PaymentGatewayInfo.query(queryParams).get()

                if (!paymentGatewayInfo) {
                    AsaasLogger.warn("PaymentCreditCardDepositService.parseRedeSingleTransaction >>> PaymentGatewayInfo não encontrado ${fileItem.transactionIdentifier}")
                    return
                }

                paymentGatewayInfo.mundiPaggTransactionKey = fileItem.saleSummaryNumber
                paymentGatewayInfo.save(failOnError: true)

                parsedPaymentCreditCardDepositFileItemVOList.add(new PaymentCreditCardDepositFileItemVO([
                    transactionIdentifier: fileItem.transactionIdentifier,
                    totalPaymentVolume: fileItem.totalPaymentVolume,
                    transactionFeeAmount: fileItem.transactionFeeAmount,
                    estimatedFeePercentage: fileItem.estimatedFeePercentage,
                    installmentNumber: fileItem.installmentNumber,
                    saleDate: fileItem.saleDate,
                    estimatedCreditDate: fileItem.estimatedCreditDate
                ]))
            }, [logErrorMessage: "PaymentCreditCardDepositService.parseRedeSingleTransaction >>> Erro ao atualizar o resumo de venda da cobrança ${fileItem.transactionIdentifier}."] )
        })

        return parsedPaymentCreditCardDepositFileItemVOList
    }

    private void deleteRetrievedFileIfNecessary(CreditCardAcquirer acquirer, String originalFileName) {
        if (acquirer.isAdyen()) AdyenSettlementFileRetriever.deleteRetrievedSettlementFile(originalFileName)
    }

    private void setListAsErrorWithNewTransaction(List<Long> fileItemWithErrorIdList) {
        final Integer flushEvery = 100
        final Integer batchSize = 100

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(fileItemWithErrorIdList, batchSize, flushEvery, { Long fileItemWithErrorId ->
            PaymentCreditCardDepositFileItem fileItem = PaymentCreditCardDepositFileItem.get(fileItemWithErrorId)
            fileItem.status = PaymentCreditCardDepositFileItemProcessingStatus.ERROR
            fileItem.save(failOnError: true)
        }, [logErrorMessage: "PaymentCreditCardDepositService.setListAsErrorWithNewTransaction >> Erro ao atualizar o status dos itens", appendBatchToLogErrorMessage: true] )
    }
}
