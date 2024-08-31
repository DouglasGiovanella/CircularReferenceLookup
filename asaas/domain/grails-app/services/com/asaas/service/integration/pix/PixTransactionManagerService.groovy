package com.asaas.service.integration.pix

import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.api.bradesco.BradescoPixManager
import com.asaas.integration.pix.dto.bradesco.creditrefund.create.RefundCreditRequestDTO
import com.asaas.integration.pix.dto.bradesco.creditrefund.create.RefundCreditResponseDTO
import com.asaas.integration.pix.dto.bradesco.creditrefund.get.BradescoPixGetCreditRefundResponseDTO
import com.asaas.integration.pix.dto.creditrefund.HermesSaveCreditRefundRequestDTO
import com.asaas.integration.pix.dto.creditrefund.HermesSaveCreditRefundResponseDTO
import com.asaas.integration.pix.dto.debit.save.HermesSaveDebitRequestDTO
import com.asaas.integration.pix.dto.debit.save.HermesSaveDebitResponseDTO
import com.asaas.integration.pix.dto.transaction.HermesFindBacenTransactionResponseDTO
import com.asaas.integration.pix.parser.PixDecoder
import com.asaas.integration.pix.parser.bradesco.BradescoPixDecoder
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixAsaasIdempotencyKeyType
import com.asaas.pix.adapter.transaction.bacen.PixTransactionBacenAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixTransactionManagerService {

    def pixAsaasIdempotencyKeyService

    public Map createDebit(PixTransaction transaction) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.enableLoggingOnlyForErrors()
        hermesManager.post("/accounts/${transaction.customer.id}/transactions", new HermesSaveDebitRequestDTO(transaction).properties, pixAsaasIdempotencyKeyService.getIdempotencyKey(transaction, PixAsaasIdempotencyKeyType.CREATE_DEBIT))

        if (hermesManager.isSuccessful()) {
            HermesSaveDebitResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveDebitResponseDTO)
            return [success: true, externalIdentifier: responseDto.externalIdentifier]
        }

        if (hermesManager.isTimeout()) {
            AsaasLogger.error("PixTransactionManagerService.createDebit() -> Timeout ao criar débito [pixTransaction.id: ${transaction.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")
            return [withoutExternalResponse: true]
        }

        if (hermesManager.isClientError()) {
            AsaasLogger.warn("PixTransactionManagerService.createDebit() -> Erro ao criar débito [pixTransaction.id: ${transaction.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")

            HermesSaveDebitResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveDebitResponseDTO)
            return [success: false, error: responseDto.errorMessage]
        }

        AsaasLogger.error("PixTransactionManagerService.createDebit() -> Erro ao criar débito [pixTransaction.id: ${transaction.id}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        return [unknowError: true, error: Utils.getMessageProperty("unknow.error")]
    }

    public Map createCreditRefund(PixTransactionRefund refund) {
        if (refund.refundedTransaction.receivedWithAsaasQrCode) return createCreditRefundFromAsaasKey(refund)

        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/accounts/${refund.refundedTransaction.customer.id}/transactions/${refund.refundedTransaction.id}/refunds", new HermesSaveCreditRefundRequestDTO(refund).properties, pixAsaasIdempotencyKeyService.getIdempotencyKey(refund.transaction, PixAsaasIdempotencyKeyType.CREATE_CREDIT_REFUND))

        if (hermesManager.isSuccessful()) {
            HermesSaveCreditRefundResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveCreditRefundResponseDTO)
            return [success: true, externalIdentifier: responseDto.externalIdentifier]
        }

        if (hermesManager.isTimeout()) {
            AsaasLogger.error("PixTransactionManagerService.createCreditRefund() -> Timeout ao criar estorno de crédito [PixTransactionRefund.id: ${refund.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")
            return [withoutExternalResponse: true]
        }

        if (hermesManager.isClientError()) {
            AsaasLogger.error("PixTransactionManagerService.createCreditRefund() -> Erro ao criar estorno de crédito [PixTransactionRefund.id: ${refund.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")

            HermesSaveCreditRefundResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveCreditRefundResponseDTO)
            return [success: false, error: responseDto.errorMessage]
        }

        AsaasLogger.error("PixTransactionManagerService.createCreditRefund() -> Erro ao criar estorno de crédito [PixTransactionRefund.id: ${refund.id}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        return [unknowError: true, error: Utils.getMessageProperty("unknow.error")]
    }

    public Map executeRefundTransactionManually(Long transactionId, BigDecimal refundValue, Customer customer, Long asaasId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/transactions/refundCredit", new HermesSaveCreditRefundRequestDTO(transactionId, refundValue, customer, asaasId).properties, UUID.randomUUID().toString())

        if (hermesManager.isSuccessful()) {
            return [success: true]
        }

        if (hermesManager.isTimeout()) {
            AsaasLogger.error("PixTransactionManagerService.executeRefundTransactionManually() -> Timeout ao criar o reembolso [TransactionId: ${transactionId}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")
            return [withoutExternalResponse: true]
        }

        if (hermesManager.isClientError()) {
            AsaasLogger.error("PixTransactionManagerService.executeRefundTransactionManually() -> Erro ao criar o reembolso [TransactionId: ${transactionId}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")

            HermesSaveCreditRefundResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveCreditRefundResponseDTO)
            return [success: false, error: responseDto.errorMessage]
        }

        AsaasLogger.error("PixTransactionManagerService.executeRefundTransactionManually() -> Erro ao criar o reembolso [TransactionId: ${transactionId}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    public Map getCreditRefund(PixTransactionRefund refund) {
        BradescoPixManager manager = new BradescoPixManager()
        manager.get("/v1/spi/pix/${refund.refundedTransaction.endToEndIdentifier}/devolucao/${refund.id}", null)

        if (manager.isSuccessful()) {
            BradescoPixGetCreditRefundResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), BradescoPixGetCreditRefundResponseDTO)

            Map creditRefundInfo = [endToEndIdentifier: responseDto.rtrId, status: BradescoPixDecoder.decodeCreditRefundStatus(responseDto.status), refusalReason: responseDto.motivo]
            if (responseDto.horario.liquidacao) creditRefundInfo.effectiveDate = PixDecoder.decodeDateTime(responseDto.horario.liquidacao)
            return creditRefundInfo
        }

        AsaasLogger.error("PixTransactionManagerService.getCreditRefund() -> Erro ao consultar estorno de crédito [PixTransactionRefund.id: ${refund.id}, status: ${manager.statusCode}, error: ${manager.responseBody}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    public Boolean get(Long pixTransactionId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/transactions/get?asaasId=${pixTransactionId}", [:])

        if (hermesManager.isSuccessful()) return true

        return false
    }

    public PixTransactionBacenAdapter findInBacen(String endToEndIdentifier) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.get("/transactions/findInBacen", [endToEndIdentifier: endToEndIdentifier])

        if (hermesManager.isSuccessful()) {
            HermesFindBacenTransactionResponseDTO hermesFindBacenTransactionResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesFindBacenTransactionResponseDTO)
            return new PixTransactionBacenAdapter(hermesFindBacenTransactionResponseDTO)
        }

        if (hermesManager.isNotFound()) return null

        AsaasLogger.error("${this.getClass().getSimpleName()}.findInBacen() -> Erro ao consultar lançamento de transação no Bacen [endToEndIdentifier: ${endToEndIdentifier}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    private Map createCreditRefundFromAsaasKey(PixTransactionRefund refund) {
        BradescoPixManager manager = new BradescoPixManager()
        manager.put("/v1/spi/pix/${refund.refundedTransaction.endToEndIdentifier}/devolucao/${refund.id}", new RefundCreditRequestDTO(refund).properties)

        if (manager.isSuccessful()) {
            RefundCreditResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), RefundCreditResponseDTO)
            return [success: true, externalIdentifier: responseDto.rtrId]
        }

        if (manager.isTimeout()) {
            AsaasLogger.warn("PixTransactionManagerService.createCreditRefundFromAsaasKey() -> Timeout ao estornar crédito [PixTransactionRefund.id: ${refund.id}, error: ${manager.responseBody}, status: ${manager.statusCode}]")
            return [withoutExternalResponse: true]
        }

        if (manager.isClientError()) {
            AsaasLogger.warn("PixTransactionManagerService.createCreditRefundFromAsaasKey() -> Erro ao estornar crédito [PixTransactionRefund.id: ${refund.id}, error: ${manager.responseBody}, status: ${manager.statusCode}]")
            return [success: false, error: Utils.getMessageProperty("unknow.error"), handleErrorManually: manager.isPreconditionFailed()]
        }

        AsaasLogger.error("PixTransactionManagerService.createCreditRefundFromAsaasKey() -> Erro ao estornar crédito [PixTransactionRefund.id: ${refund.id}, status: ${manager.statusCode}, error: ${manager.responseBody}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }
}
