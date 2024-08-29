package com.asaas.service.asaascardtransaction

import com.asaas.asaascardtransaction.AsaasCardTransactionRefusalReason
import com.asaas.asaascardtransaction.AsaasCardTransactionType
import com.asaas.checkout.CheckoutValidator
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardtransaction.AsaasCardTransaction
import com.asaas.domain.asaascardtransactionfee.AsaasCardTransactionFee
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.bifrost.adapter.asaascardtransaction.SaveAsaasCardTransactionRequestAdapter
import com.asaas.integration.bifrost.adapter.asaascardtransaction.SaveAsaasCardTransactionResponseAdapter
import com.asaas.integration.bifrost.adapter.asaascardtransaction.children.SaveAsaasCardTransactionFeeAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class AsaasCardTransactionService {

    def asaasCardTransactionSettlementService
    def bifrostTransactionManagerService
    def customerCheckoutLimitService
    def financialTransactionService
    def lastCheckoutInfoService

    public Map getTransactionWithExternalDetails(AsaasCard asaasCard, Long externalId) {
        return bifrostTransactionManagerService.getDetails(asaasCard, externalId)
    }

    public SaveAsaasCardTransactionResponseAdapter saveDebit(SaveAsaasCardTransactionRequestAdapter requestAdapter) {
        AsaasCardTransaction asaasCardTransactionValidated = validateSaveDebit(requestAdapter)
        if (asaasCardTransactionValidated.hasErrors()) return new SaveAsaasCardTransactionResponseAdapter(asaasCardTransactionValidated)

        AsaasCardTransaction asaasCardTransaction = create(requestAdapter)
        if (asaasCardTransaction.type.isWithdrawal()) asaasCardTransaction = updateFeeAndNetValue(asaasCardTransaction, requestAdapter.fee)
        asaasCardTransaction.international = requestAdapter.international
        asaasCardTransaction.establishmentName = requestAdapter.establishmentName
        asaasCardTransaction.dollarAmount = requestAdapter.dollarAmount
        asaasCardTransaction.save(failOnError: true)

        if (requestAdapter.transactionFeeList) saveDebitFees(asaasCardTransaction, requestAdapter.transactionFeeList)

        lastCheckoutInfoService.save(requestAdapter.asaasCard.customer)
        financialTransactionService.saveAsaasCardTransaction(asaasCardTransaction, requestAdapter.forceAuthorization)
        asaasCardTransactionSettlementService.save(asaasCardTransaction)
        BigDecimal customerBalance = customerCheckoutLimitService.getAvailableDailyCheckout(requestAdapter.asaasCard.customer)

        return new SaveAsaasCardTransactionResponseAdapter(asaasCardTransaction, customerBalance)
    }

    public AsaasCardTransaction saveCancellation(SaveAsaasCardTransactionRequestAdapter requestAdapter) {
        AsaasCardTransaction transactionOrigin = AsaasCardTransaction.query([asaasCard: requestAdapter.asaasCard, type: requestAdapter.type, externalId: requestAdapter.transactionOriginId]).get()
        requestAdapter.type = transactionOrigin.type.getCancelType()

        AsaasCardTransaction asaasCardTransactionCancellationValidated = validateSaveTransactionCancellation(requestAdapter, transactionOrigin)
        if (asaasCardTransactionCancellationValidated.hasErrors()) return asaasCardTransactionCancellationValidated

        AsaasCardTransaction cancellationTransaction = create(requestAdapter, transactionOrigin)
        cancellationTransaction.save(failOnError: true)

        financialTransactionService.refundAsaasCardTransaction(cancellationTransaction, false)

        asaasCardTransactionSettlementService.updateStatusAndValue(cancellationTransaction.transactionOrigin.asaasCardTransactionSettlement, cancellationTransaction.value)

        return cancellationTransaction
    }

    public SaveAsaasCardTransactionResponseAdapter saveRefund(SaveAsaasCardTransactionRequestAdapter requestAdapter) {
        AsaasCardTransaction transactionOrigin = AsaasCardTransaction.find(requestAdapter.asaasCard.customer, requestAdapter.transactionOriginId)
        requestAdapter.type = transactionOrigin.type.getRefundType()

        AsaasCardTransaction asaasCardTransactionRefundValidated = validateSaveRefund(requestAdapter, transactionOrigin)
        if (asaasCardTransactionRefundValidated.hasErrors()) return new SaveAsaasCardTransactionResponseAdapter(asaasCardTransactionRefundValidated)

        AsaasCardTransaction refundTransaction = create(requestAdapter, transactionOrigin)
        BigDecimal refundAbsoluteValue = BigDecimalUtils.abs(requestAdapter.totalAmount)
        if (refundTransaction.type.isRefundWithdrawal()) {
            BigDecimal totalAlreadyRefundedValue = AsaasCardTransaction.sumValueAbs([transactionOrigin: transactionOrigin]).get()
            if ((totalAlreadyRefundedValue + refundAbsoluteValue) == transactionOrigin.value) {
                refundTransaction.fee = BigDecimalUtils.negate(transactionOrigin.fee)
                refundTransaction.netValue = refundTransaction.value - refundTransaction.fee
            }
        }

        refundTransaction.international = transactionOrigin.international
        refundTransaction.save(failOnError: true)

        Boolean isPartialRefund = refundAbsoluteValue != transactionOrigin.value
        financialTransactionService.refundAsaasCardTransaction(refundTransaction, isPartialRefund)
        asaasCardTransactionSettlementService.updateStatusAndValue(refundTransaction.transactionOrigin.asaasCardTransactionSettlement, refundTransaction.value)

        BigDecimal customerBalance = customerCheckoutLimitService.getAvailableDailyCheckout(requestAdapter.asaasCard.customer)

        return new SaveAsaasCardTransactionResponseAdapter(refundTransaction, customerBalance)
    }

    public SaveAsaasCardTransactionResponseAdapter saveRefundCancellation(AsaasCard asaasCard, Long externalId, Long transactionRefundOriginId) {
        AsaasCardTransaction transactionRefundOrigin = AsaasCardTransaction.find(asaasCard.customer, transactionRefundOriginId)
        if (transactionRefundOrigin.type.isRefundWithdrawal()) throw new BusinessException("Estorno de cancelamento para saque não suportado.")

        AsaasCardTransaction refundCancellationValidated = validateRefundCancellation(transactionRefundOrigin)
        if (refundCancellationValidated.hasErrors()) return new SaveAsaasCardTransactionResponseAdapter(refundCancellationValidated)

        AsaasCardTransaction refundCancellationTransaction = create(asaasCard, BigDecimalUtils.abs(transactionRefundOrigin.value), externalId, transactionRefundOrigin.type.getRefundCancelledType(), transactionRefundOrigin)
        refundCancellationTransaction.international = transactionRefundOrigin.international
        refundCancellationTransaction.save(failOnError: true)

        lastCheckoutInfoService.save(asaasCard.customer)
        financialTransactionService.cancelRefundAsaasCardTransaction(refundCancellationTransaction)

        asaasCardTransactionSettlementService.updateStatusAndValue(refundCancellationTransaction.transactionOrigin.transactionOrigin.asaasCardTransactionSettlement, refundCancellationTransaction.value)

        BigDecimal customerBalance = customerCheckoutLimitService.getAvailableDailyCheckout(asaasCard.customer)

        return new SaveAsaasCardTransactionResponseAdapter(refundCancellationTransaction, customerBalance)
    }

    public AsaasCardTransaction conciliate(Customer customer, Long asaasCardTransactionId, BigDecimal totalAmount) {
        AsaasCardTransaction transaction
        try {
            transaction = AsaasCardTransaction.find(customer, asaasCardTransactionId)
            if (transaction.value != totalAmount) {
                return setRefusalReason(transaction, AsaasCardTransactionRefusalReason.TRANSACTION_CONCILIATION_INVALID_VALUE)
            }

            return transaction
        } catch (ResourceNotFoundException exception) {
            return setRefusalReason(transaction, AsaasCardTransactionRefusalReason.TRANSACTION_NOT_FOUND)
        }
    }

    public AsaasCardTransaction scheduleSettlement(Customer customer, Long asaasCardTransactionId, Date estimatedSettlementDate) {
        AsaasCardTransaction asaasCardTransaction
        try {
            asaasCardTransaction = AsaasCardTransaction.find(customer, asaasCardTransactionId)

            if (estimatedSettlementDate < new Date().clearTime()) return setRefusalReason(asaasCardTransaction, AsaasCardTransactionRefusalReason.SETTLEMENT_DATE_NOT_ALLOWED)

            asaasCardTransactionSettlementService.schedule(asaasCardTransactionId, estimatedSettlementDate)
            return asaasCardTransaction
        } catch (ResourceNotFoundException exception) {
            return setRefusalReason(asaasCardTransaction, AsaasCardTransactionRefusalReason.TRANSACTION_NOT_FOUND)
        }
    }

    private AsaasCardTransaction validateSaveDebit(SaveAsaasCardTransactionRequestAdapter requestAdapter) {
        AsaasCardTransaction validatedTransaction = new AsaasCardTransaction()

        if (!requestAdapter.asaasCard.type.isDebitEnabled()) {
            return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INACTIVE_CARD)
        }

        if (!requestAdapter.asaasCard.status.isActive() && !(requestAdapter.forceAuthorization && requestAdapter.asaasCard.status.hasBeenActivated())) {
            return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INACTIVE_CARD)
        }

        if (!requestAdapter.totalAmount || requestAdapter.totalAmount <= 0) {
            return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INVALID_VALUE)
        }

        if (AsaasCardTransaction.query([externalId: requestAdapter.externalId, type: requestAdapter.type, exists: true]).get()) {
            return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.ALREADY_PROCESSED)
        }

        if (!requestAdapter.forceAuthorization && requestAdapter.asaasCard.customer.asaasCardDisabled()) {
            return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INACTIVE_CARD)
        }

        CheckoutValidator checkoutValidator = new CheckoutValidator(requestAdapter.asaasCard.customer)
        checkoutValidator.isAsaasCardTransaction = true
        List<AsaasError> listOfAsaasError = checkoutValidator.validate(requestAdapter.totalAmount)

        if (listOfAsaasError) {
            AsaasError asaasError = listOfAsaasError.first()

            final Boolean isInsufficientDailyLimit = asaasError.code == "denied.insufficient.daily.limit"
            final Boolean isInsufficientBalance = asaasError.code == "denied.insufficient.balance"

            if ((isInsufficientDailyLimit || isInsufficientBalance) && requestAdapter.forceAuthorization) {
                AsaasLogger.warn("AsaasCardTransactionService.validateSaveDebit >>> Transação de cartão forçada para conta sem saldo. [asaasCardId: ${requestAdapter.asaasCard.id}, transactionValue: ${requestAdapter.totalAmount}].")
                return validatedTransaction
            }

            if (isInsufficientDailyLimit) {
                return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INSUFFICIENT_BALANCE)
            } else if (isInsufficientBalance) {
                return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INSUFFICIENT_BALANCE)
            }

            return setRefusalReason(validatedTransaction, AsaasCardTransactionRefusalReason.INACTIVE_CARD)
        }

        return validatedTransaction
    }

    private AsaasCardTransaction updateFeeAndNetValue(AsaasCardTransaction asaasCardTransaction, BigDecimal fee) {
        asaasCardTransaction.fee = fee
        asaasCardTransaction.netValue = asaasCardTransaction.value - asaasCardTransaction.fee

        return asaasCardTransaction
    }

    private void saveDebitFees(AsaasCardTransaction asaasCardTransaction, List<SaveAsaasCardTransactionFeeAdapter> transactionFeeList) {
        for (SaveAsaasCardTransactionFeeAdapter feeAdapter : transactionFeeList) {
            AsaasCardTransactionFee asaasCardTransactionFee = new AsaasCardTransactionFee(asaasCardTransaction, feeAdapter)
            asaasCardTransactionFee.save(failOnError: true)
        }
    }

    private AsaasCardTransaction validateSaveTransactionCancellation(SaveAsaasCardTransactionRequestAdapter requestAdapter, AsaasCardTransaction transactionOrigin) {
        AsaasCardTransaction asaasCardTransactionValidated = new AsaasCardTransaction()

        if (!transactionOrigin) {
            return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.TRANSACTION_NOT_FOUND)
        }

        if (!requestAdapter.totalAmount || requestAdapter.totalAmount >= 0 || BigDecimalUtils.abs(requestAdapter.totalAmount) != transactionOrigin.value) {
            return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.INVALID_VALUE)
        }

        Boolean alreadyRequested = AsaasCardTransaction.query([transactionOrigin: transactionOrigin, exists: true]).get()
        if (alreadyRequested) {
            return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.ALREADY_PROCESSED)
        }

        return asaasCardTransactionValidated
    }

    private AsaasCardTransaction validateSaveRefund(SaveAsaasCardTransactionRequestAdapter requestAdapter, AsaasCardTransaction transactionOrigin) {
        AsaasCardTransaction asaasCardTransactionValidated = new AsaasCardTransaction()

        if (!requestAdapter.totalAmount || requestAdapter.totalAmount >= 0) {
            return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.INVALID_VALUE)
        }

        BigDecimal totalAlreadyRefundedValue = AsaasCardTransaction.sumValueAbs([transactionOrigin: transactionOrigin]).get()
        if (totalAlreadyRefundedValue + BigDecimalUtils.abs(requestAdapter.totalAmount) > transactionOrigin.value) {
            return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.INVALID_VALUE)
        }

        Boolean alreadyRequested = AsaasCardTransaction.query([externalId: requestAdapter.externalId, type: requestAdapter.type, exists: true]).get()
        if (alreadyRequested) {
            return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.ALREADY_PROCESSED)
        }

        return asaasCardTransactionValidated
    }

    private AsaasCardTransaction validateRefundCancellation(AsaasCardTransaction transactionRefundOrigin) {
        AsaasCardTransaction asaasCardTransactionValidated = new AsaasCardTransaction()

        Boolean alreadyCancelled = AsaasCardTransaction.query([exists: true, transactionOrigin: transactionRefundOrigin]).get()
        if (alreadyCancelled) return setRefusalReason(asaasCardTransactionValidated, AsaasCardTransactionRefusalReason.ALREADY_PROCESSED)

        return asaasCardTransactionValidated
    }

    private AsaasCardTransaction create(SaveAsaasCardTransactionRequestAdapter requestAdapter, AsaasCardTransaction transactionOrigin) {
        AsaasCardTransaction asaasCardTransaction = create(requestAdapter)
        asaasCardTransaction.transactionOrigin = transactionOrigin

        return asaasCardTransaction
    }
    private AsaasCardTransaction create(SaveAsaasCardTransactionRequestAdapter requestAdapter) {
        AsaasCardTransaction asaasCardTransaction = new AsaasCardTransaction()

        asaasCardTransaction.asaasCard = requestAdapter.asaasCard
        asaasCardTransaction.customer = requestAdapter.asaasCard.customer
        asaasCardTransaction.externalId = requestAdapter.externalId
        asaasCardTransaction.value = requestAdapter.totalAmount
        asaasCardTransaction.netValue = requestAdapter.totalAmount
        asaasCardTransaction.fee = 0.00
        asaasCardTransaction.type = requestAdapter.type

        return asaasCardTransaction
    }

    private AsaasCardTransaction create(AsaasCard asaasCard, BigDecimal value, Long externalId, AsaasCardTransactionType type, AsaasCardTransaction transactionOrigin = null) {
        return new AsaasCardTransaction([
            asaasCard: asaasCard,
            customer: asaasCard.customer,
            externalId: externalId,
            value: value,
            netValue: value,
            fee: 0,
            type: type,
            transactionOrigin: transactionOrigin
        ])
    }

    private AsaasCardTransaction setRefusalReason(AsaasCardTransaction asaasCardTransaction, AsaasCardTransactionRefusalReason reason) {
        asaasCardTransaction.refusalReason = reason
        return DomainUtils.addError(asaasCardTransaction, reason.message)
    }
}
