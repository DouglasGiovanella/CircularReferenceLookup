package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.pixtransactionrequest.PixTransactionRequest
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionRequestStatus
import com.asaas.pix.PixTransactionType
import com.asaas.pix.adapter.accountnumber.AccountNumberAdapter
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@Transactional
class PixTransactionRequestService {

    private static final Integer MAX_ATTEMPTS = 4

    def pixCreditService
    def messageService

    public PixTransactionRequest save(CreditAdapter creditInfo) {
        Map info = creditInfo.toMap()
        info.remove("receiver")
        info.remove("payment")
        Map encrypted = PixTransactionRequest.encrypt(JsonOutput.toJson(info).toString())

        PixTransactionRequest pixTransactionRequest = new PixTransactionRequest()
        pixTransactionRequest.status = PixTransactionRequestStatus.AWAITING_PROCESSING
        pixTransactionRequest.pixTransactionExternalIdentifier = creditInfo.externalIdentifier
        pixTransactionRequest.pixTransactionType = PixTransactionType.CREDIT
        pixTransactionRequest.encryptedInfo = encrypted.encryptedString
        pixTransactionRequest.conciliationIdentifier = PixUtils.sanitizeHtml(creditInfo.conciliationIdentifier)
        pixTransactionRequest.iv = encrypted.iv
        pixTransactionRequest.save(failOnError: true)
        return pixTransactionRequest
    }

    public void processAwaitingProcessingRequests() {
        List<Long> pixTransactionRequestIdList = PixTransactionRequest.awaitingProcessing([column: "id", order: "asc"]).list(max: 50)

        for (Long pixTransactionRequestId : pixTransactionRequestIdList) {
            Boolean processed = false

            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionRequest pixTransactionRequest = PixTransactionRequest.get(pixTransactionRequestId)
                processTransactionRequest(pixTransactionRequest)
                processed = true
            }, [logErrorMessage: "PixTransactionRequestService.processAwaitingProcessingRequests() -> Erro ao processar transação Pix [pixTransactionRequest.id: ${pixTransactionRequestId}]"])

            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionRequest pixTransactionRequest = PixTransactionRequest.get(pixTransactionRequestId)
                pixTransactionRequest.attempts = (pixTransactionRequest.attempts ?: 0) + 1
                pixTransactionRequest.save(failOnError: true)
            }, [logErrorMessage: "PixTransactionRequestService.processAwaitingProcessingRequests() -> Erro ao incrementar tentativas de processar Transação Pix [pixTransactionRequest.id: ${pixTransactionRequestId}]"])

            if (!processed) {
                Utils.withNewTransactionAndRollbackOnError({
                    PixTransactionRequest pixTransactionRequest = PixTransactionRequest.get(pixTransactionRequestId)
                    if (!canTryReprocessTransaction(pixTransactionRequest)) {
                        pixTransactionRequest.status = PixTransactionRequestStatus.ERROR
                        pixTransactionRequest.save(failOnError: true)

                        messageService.notifyErrorToProcessPixCreditTransaction(pixTransactionRequest, new JsonSlurper().parseText(pixTransactionRequest.getDecryptedInfo()))
                    }
                }, [logErrorMessage: "PixTransactionRequestService.processAwaitingProcessingRequests() -> Erro ao definir erro no Polling. [pixTransactionRequest.id: ${pixTransactionRequestId}]"])
            }
        }
    }

    private void processTransactionRequest(PixTransactionRequest pixTransactionRequest) {
        Map decryptedInfo = new JsonSlurper().parseText(pixTransactionRequest.getDecryptedInfo())

        CreditAdapter creditInfo = new CreditAdapter(decryptedInfo)
        if (!creditInfo.payment) {
            pixTransactionRequest.status = PixTransactionRequestStatus.REFUSED
            pixTransactionRequest.pixTransactionRefusalReason = PixTransactionRefusalReason.DENIED
            pixTransactionRequest.save(failOnError: true)

            messageService.notifyPixTransactionReceivedWithoutQRCode(pixTransactionRequest, decryptedInfo)

            AsaasLogger.warn("PixTransactionRequestService.processTransactionRequest() -> Transação [pixTransactionRequest.id: ${pixTransactionRequest.id}] não está relacionada com nenhum QR Code.")
            return
        }

        creditInfo.originType = PixTransactionOriginType.DYNAMIC_QRCODE
        creditInfo.payer = new AccountNumberAdapter([name: creditInfo.payment.customerAccount.name,
                                                     cpfCnpj: creditInfo.payment.customerAccount.cpfCnpj])

        PixTransaction pixTransaction = pixCreditService.save(creditInfo)
        if (pixTransaction.hasErrors()) {
            AsaasLogger.info("PixTransactionRequestService.processTransactionRequest() -> Transação [pixTransactionRequest.id: ${pixTransactionRequest.id}] foi recusada e será reprocessada [motivo: ${pixTransaction.refusalReasonDescription}]")

            creditInfo.payment = null
            creditInfo.conciliationIdentifier = null
            pixTransaction = pixCreditService.save(creditInfo)

            if (pixTransaction.hasErrors()) {
                pixTransactionRequest.status = PixTransactionRequestStatus.REFUSED
                pixTransactionRequest.pixTransactionRefusalReason = pixTransaction.asBoolean() ? pixTransaction.refusalReason : PixTransactionRefusalReason.DENIED
                pixTransactionRequest.pixTransactionRefusalReasonDescription = pixTransactionRequest.pixTransactionRefusalReasonDescription
                pixTransactionRequest.save(failOnError: true)
                return
            }
        }

        pixTransactionRequest.pixTransaction = pixTransaction
        pixTransactionRequest.status = PixTransactionRequestStatus.PROCESSED
        pixTransactionRequest.save(failOnError: true)
    }

    private Boolean canTryReprocessTransaction(PixTransactionRequest pixTransactionRequest) {
        if (pixTransactionRequest.attempts >= MAX_ATTEMPTS) return false
        if (pixTransactionRequest.alreadyCredited()) return false

        return true
    }

}
