package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionProcessingRetry
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixTransactionProcessingRetryService {

    def pixTransactionManagerService
    def pixTransactionService

    public PixTransactionProcessingRetry save(PixTransaction pixTransaction) {
        BusinessValidation validation = validate(pixTransaction)
        if (!validation.isValid()) {
            AsaasLogger.error("PixTransactionProcessingRetryService.save() -> ${validation.getFirstErrorMessage()} [pixTransaction.id: ${pixTransaction.id}]")
            return null
        }

        PixTransactionProcessingRetry processingRetry = new PixTransactionProcessingRetry()
        processingRetry.pixTransaction = pixTransaction
        processingRetry.processed = false

        Integer attempts = PixTransactionProcessingRetry.query([pixTransactionId: pixTransaction.id]).count()
        processingRetry.nextExecution = calculateNextExecution(attempts)
        processingRetry.save(failOnError: true)

        return processingRetry
    }

    public PixTransactionProcessingRetry seAsProcessed(PixTransactionProcessingRetry processingRetry) {
        processingRetry.processed = true
        processingRetry.save(failOnError: true)

        return processingRetry
    }

    public void process() {
        List<Long> pixTransactionProcessingRetryIdList = PixTransactionProcessingRetry.query([column: "id", processed: false, "nextExecution[le]": new Date()]).list(max: 100)

        for (Long pixTransactionProcessingRetryId : pixTransactionProcessingRetryIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionProcessingRetry processingRetry = PixTransactionProcessingRetry.get(pixTransactionProcessingRetryId)
                PixTransaction pixTransaction = processingRetry.pixTransaction

                BusinessValidation validation = validate(pixTransaction)
                if (!validation.isValid()) {
                    AsaasLogger.error("PixTransactionProcessingRetryService.process >> ${validation.getFirstErrorMessage()} [pixTransaction.id: ${pixTransaction.id}]")
                    seAsProcessed(processingRetry)
                    return
                }

                Boolean hasHermesTransaction = pixTransactionManagerService.get(pixTransaction.id)
                if (!hasHermesTransaction) pixTransactionService.setAsAwaitingRequest(pixTransaction)

                seAsProcessed(processingRetry)
            }, [logErrorMessage:  "PixTransactionProcessingRetryService.reprocessDebitPixTransactionWithError() -> Erro ao tentar reprocessar PixTransaction [PixTransactionProcessingRetryId: ${pixTransactionProcessingRetryId}]"])
        }
    }

    private BusinessValidation validate(PixTransaction pixTransaction) {
        BusinessValidation validation = new BusinessValidation()

        if (!pixTransaction.type.isEquivalentToDebit()) {
            validation.addError("pixTransactionProcessingRetry.invalid.type")
            return validation
        }

        if (pixTransaction.receivedWithAsaasQrCode) {
            validation.addError("pixTransactionProcessingRetry.invalid.transaction")
            return validation
        }

        if (!pixTransaction.status.isError()) {
            validation.addError("pixTransactionProcessingRetry.invalid.status")
            return validation
        }

        Integer numberOfAttempts = PixTransactionProcessingRetry.query([pixTransactionId: pixTransaction.id]).count()
        if (numberOfAttempts > PixTransactionProcessingRetry.MAX_ATTEMPTS) {
            validation.addError("pixTransactionProcessingRetry.invalid.exceededMaxAttempts")
            return validation
        }

        return validation
    }

    private Date calculateNextExecution(Integer retryCount) {
        final Integer baseSeconds = 30

        Integer secondsToNextExecution = retryCount * baseSeconds
        return CustomDateUtils.sumSeconds(new Date(), secondsToNextExecution)
    }

}
