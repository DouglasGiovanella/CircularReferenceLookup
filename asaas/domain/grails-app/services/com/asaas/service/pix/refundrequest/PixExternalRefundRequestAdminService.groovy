package com.asaas.service.pix.refundrequest

import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.adapter.transaction.refundrequest.base.BaseRefundRequestRefundTransactionAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixExternalRefundRequestAdminService {

    def paymentRefundService
    def pixCreditService
    def pixRefundRequestManagerService
    def pixTransactionService

    public Map list(Map filters, Integer limit, Integer offset) {
        return pixRefundRequestManagerService.listExternal(filters, limit, offset)
    }

    public Map get(Long id) {
        return pixRefundRequestManagerService.getExternal(id)
    }

    public PixTransaction createRefundTransaction(BaseRefundRequestRefundTransactionAdapter externalRefundRequestRefundTransactionAdapter) {
        BigDecimal refundValue = getValueToBeRefunded(externalRefundRequestRefundTransactionAdapter.customer, externalRefundRequestRefundTransactionAdapter.value)

        PixTransaction validatedTransaction = validate(externalRefundRequestRefundTransactionAdapter, refundValue)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        PixTransaction contestedPixTransaction = externalRefundRequestRefundTransactionAdapter.pixTransaction
        if (contestedPixTransaction.type.isCredit()) return refund(contestedPixTransaction, refundValue, externalRefundRequestRefundTransactionAdapter.pixTransactionRefundReason)
        if (contestedPixTransaction.type.isDebitRefund()) {
            return pixTransactionService.cancelDebitRefund(externalRefundRequestRefundTransactionAdapter, refundValue)
        }

        throw new RuntimeException("PixExternalRefundRequestAdminService.createRefundTransaction() -> Tipo incorreto para gerar uma transação de devolução para uma devolução especial. [pixTransaction.id: ${externalRefundRequestRefundTransactionAdapter.pixTransaction.id}, type: ${externalRefundRequestRefundTransactionAdapter.pixTransaction.type}]")
    }

    private PixTransaction refund(PixTransaction contestedPixTransaction, BigDecimal valueToRefund, PixTransactionRefundReason refundReason) {
        String description = "Estorno via mecanismo especial de devolução"
        Boolean bypassCustomerValidation = true

        if (contestedPixTransaction.payment) {
            Map transactionInfo = [:]
            transactionInfo.value = valueToRefund
            transactionInfo.reason = refundReason
            transactionInfo.refundOnAcquirer = false
            transactionInfo.bypassCustomerValidation = bypassCustomerValidation
            transactionInfo.description = description

            paymentRefundService.refund(contestedPixTransaction.payment, transactionInfo)

            return PixTransactionRefund.query([column: "transaction", refundedTransaction: contestedPixTransaction, sort: "id", order: "desc"]).get()
        } else {
            PixTransaction pixTransaction = pixCreditService.refundWithoutPayment(contestedPixTransaction, valueToRefund, refundReason, description, bypassCustomerValidation, [authorizeSynchronous: false])
            if (pixTransaction.hasErrors()) throw new RuntimeException("Erro ao fazer transação de estorno de Pix ${contestedPixTransaction.id} ${pixTransaction.errors}")

            return pixTransaction
        }
    }

    private BigDecimal getValueToBeRefunded(Customer customer, BigDecimal refundRequestValue) {
        if (customer.hasSufficientBalance(refundRequestValue)) return refundRequestValue

        BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(customer)
        if (customerBalance >= 0) {
            return customerBalance
        } else {
            return 0
        }
    }

    private PixTransaction validate(BaseRefundRequestRefundTransactionAdapter externalRefundRequestRefundTransactionAdapter, BigDecimal valueToRefund) {
        if (externalRefundRequestRefundTransactionAdapter.customer.status.isDisabled()) return refuseRefundRequest(externalRefundRequestRefundTransactionAdapter, PixTransactionRefusalReason.DELETED_ACCOUNT_NUMBER, "Cliente encerrou a conta.")
        if (valueToRefund == 0) return refuseRefundRequest(externalRefundRequestRefundTransactionAdapter, PixTransactionRefusalReason.NO_BALANCE, "Cliente não possui saldo.")

        PixTransaction contestedPixTransaction = externalRefundRequestRefundTransactionAdapter.pixTransaction
        if (valueToRefund > contestedPixTransaction.value) return refuseRefundRequest(externalRefundRequestRefundTransactionAdapter, PixTransactionRefusalReason.DENIED, "Valor de devolução especial supera o valor da transação")
        if (valueToRefund > contestedPixTransaction.getRemainingValueToRefund()) return refuseRefundRequest(externalRefundRequestRefundTransactionAdapter, PixTransactionRefusalReason.DENIED, "Valor de devolução especial somado a outras devoluções supera o valor da transação")

        return new PixTransaction()
    }

    private PixTransaction refuseRefundRequest(BaseRefundRequestRefundTransactionAdapter externalRefundRequestRefundTransactionAdapter, PixTransactionRefusalReason refusalReason, String reasonDescription) {
        AsaasLogger.info("PixExternalRefundRequestAdminService -> ${reasonDescription} [Informações da transação: [pixTransactionId: ${externalRefundRequestRefundTransactionAdapter.pixTransaction?.id}, refusalReason: ${refusalReason}]]")

        PixTransaction validatedTransaction = new PixTransaction()
        validatedTransaction.refusalReason = refusalReason
        validatedTransaction.refusalReasonDescription = reasonDescription
        DomainUtils.addError(validatedTransaction, Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason}", [reasonDescription]))

        return validatedTransaction
    }
}
