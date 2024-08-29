package com.asaas.service.pix.refundrequest

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.exception.BusinessException
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.adapter.transaction.refundrequest.base.BaseRefundRequestRefundTransactionAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixRefundRequestAdminService {

    def pixRefundRequestManagerService
    def pixTransactionService

    public Map save(PixTransaction pixTransaction, BigDecimal value, String details) {
        BusinessValidation refundRequestValidated = canCreateRefundRequestForTransaction(pixTransaction)
        if (!refundRequestValidated.isValid()) throw new BusinessException(refundRequestValidated.getFirstErrorMessage())

        return pixRefundRequestManagerService.save(pixTransaction, value, details)
    }

    public void cancel(Long id) {
        pixRefundRequestManagerService.cancel(id)
    }

    public BusinessValidation canCreateRefundRequestForTransaction(PixTransaction pixTransaction) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        Map info = pixRefundRequestManagerService.validate(pixTransaction)
        if (!info.success) validatedBusiness.addError("pixRefundRequest.create.error.default", [info.error])

        return validatedBusiness
    }

    public Map get(Long id) {
        return pixRefundRequestManagerService.get(id)
    }

    public Map list(Map filters, Integer limit, Integer offset) {
        return pixRefundRequestManagerService.list(filters, limit, offset)
    }

    public PixTransaction createRefundReversal(BaseRefundRequestRefundTransactionAdapter refundRequestReversalRefundTransactionAdapter) {
        PixTransaction validatedTransaction = validateCreateRefundReversal(refundRequestReversalRefundTransactionAdapter)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        return pixTransactionService.cancelDebitRefund(refundRequestReversalRefundTransactionAdapter, refundRequestReversalRefundTransactionAdapter.value)
    }

    private PixTransaction validateCreateRefundReversal(BaseRefundRequestRefundTransactionAdapter refundRequestReversalRefundTransactionAdapter) {
        if (refundRequestReversalRefundTransactionAdapter.customer.status.isDisabled()) return refuseRefundRequest(PixTransactionRefusalReason.DELETED_ACCOUNT_NUMBER, "Cliente encerrou a conta.")
        if (!refundRequestReversalRefundTransactionAdapter.customer.hasSufficientBalance(refundRequestReversalRefundTransactionAdapter.value)) return refuseRefundRequest(PixTransactionRefusalReason.DENIED, "Cliente não possui saldo para devolução do valor integral.")

        PixTransaction contestedPixTransaction = refundRequestReversalRefundTransactionAdapter.pixTransaction
        if (!contestedPixTransaction.type.isDebitRefund()) return refuseRefundRequest(PixTransactionRefusalReason.DENIED, "Transação contestada não é um estorno de débito")
        if (refundRequestReversalRefundTransactionAdapter.value != contestedPixTransaction.value) return refuseRefundRequest(PixTransactionRefusalReason.DENIED, "Valor de devolução especial é diferente do valor do estorno já feito")

        Boolean pixTransactionRefundCancel = PixTransactionRefund.query([exists: true, refundedTransactionId: contestedPixTransaction.id, "transaction.status[in]": PixTransactionStatus.listRefundedValueCompromised()]).get().asBoolean()
        if (pixTransactionRefundCancel) return refuseRefundRequest(PixTransactionRefusalReason.DENIED, "Transação de estorno já teve seu estorno cancelado")

        return new PixTransaction()
    }

    private PixTransaction refuseRefundRequest(PixTransactionRefusalReason refusalReason, String reasonDescription) {
        PixTransaction validatedTransaction = new PixTransaction()
        validatedTransaction.refusalReason = refusalReason
        validatedTransaction.refusalReasonDescription = reasonDescription
        DomainUtils.addError(validatedTransaction, Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason}", [reasonDescription]))

        return validatedTransaction
    }
}
