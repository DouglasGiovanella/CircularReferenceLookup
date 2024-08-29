package com.asaas.service.cardtransactioncapturedrawinfo

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoCardType
import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoVO
import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoValidationStatus
import com.asaas.creditcard.CreditCardTransactionAnalysisStatus
import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.debitcard.DebitCardTransactionEvent
import com.asaas.domain.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfo
import com.asaas.domain.creditcard.CreditCardTransactionAnalysis
import com.asaas.domain.creditcard.CreditCardTransactionLog
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.debitcard.DebitCardTransactionInfo
import com.asaas.domain.debitcard.DebitCardTransactionLog
import com.asaas.domain.payment.Payment
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CardTransactionCapturedRawInfoService {

    public void saveWithNewTransaction(CardTransactionCapturedRawInfoCardType cardType, CardTransactionCapturedRawInfoVO cardTransactionCapturedRawInfoVO) {
        Utils.withNewTransactionAndRollbackOnError ( {
            save(cardType, cardTransactionCapturedRawInfoVO)
        }, [logErrorMessage: "CardTransactionCapturedRawInfoService.saveWithNewTransaction >>> Erro ao salvar dados de captura [paymentId: ${cardTransactionCapturedRawInfoVO.paymentId}]"])
    }

    public CardTransactionCapturedRawInfo validatePendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        if (cardTransactionCapturedRawInfo.cardType.isCredit()) return validateIfCanProcessCreditPendingItem(cardTransactionCapturedRawInfo)

        return cardTransactionCapturedRawInfo
    }

    public CardTransactionCapturedRawInfo processPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        if (cardTransactionCapturedRawInfo.cardType.isCredit()) return processCreditPendingItem(cardTransactionCapturedRawInfo)

        return cardTransactionCapturedRawInfo
    }

    private CardTransactionCapturedRawInfo save(CardTransactionCapturedRawInfoCardType cardType, CardTransactionCapturedRawInfoVO cardTransactionCapturedRawInfoVO) {
        CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo = new CardTransactionCapturedRawInfo()
        cardTransactionCapturedRawInfo.captureValidationStatus = CardTransactionCapturedRawInfoValidationStatus.PENDING
        cardTransactionCapturedRawInfo.cardType = cardType
        cardTransactionCapturedRawInfo.paymentId = cardTransactionCapturedRawInfoVO.paymentId
        cardTransactionCapturedRawInfo.customerId = cardTransactionCapturedRawInfoVO.customerId
        cardTransactionCapturedRawInfo.customerAccountId = cardTransactionCapturedRawInfoVO.customerAccountId
        cardTransactionCapturedRawInfo.transactionIdentifier = cardTransactionCapturedRawInfoVO.transactionIdentifier
        cardTransactionCapturedRawInfo.transactionReference = cardTransactionCapturedRawInfoVO.transactionReference
        cardTransactionCapturedRawInfo.gateway = cardTransactionCapturedRawInfoVO.gateway
        cardTransactionCapturedRawInfo.amountInCents = cardTransactionCapturedRawInfoVO.amountInCents
        cardTransactionCapturedRawInfo.refundReferenceCode = cardTransactionCapturedRawInfoVO.refundReferenceCode
        cardTransactionCapturedRawInfo.save(failOnError: true)

        return cardTransactionCapturedRawInfo
    }

    private CardTransactionCapturedRawInfo processDebitPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        validateIfCanProcessDebitPendingItem(cardTransactionCapturedRawInfo)

        Boolean hasTransactionInfo = DebitCardTransactionInfo.query([exists: true, paymentId: cardTransactionCapturedRawInfo.paymentId, transactionIdentifier: cardTransactionCapturedRawInfo.transactionIdentifier]).get().asBoolean()
        if (hasTransactionInfo) return delete(cardTransactionCapturedRawInfo)

        return refundDebitPendingItem(cardTransactionCapturedRawInfo)
    }

    private CardTransactionCapturedRawInfo processCreditPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        CardTransactionCapturedRawInfo validatedCardTransactionCapturedRawInfo = validateIfCanProcessCreditPendingItem(cardTransactionCapturedRawInfo)

        if (validatedCardTransactionCapturedRawInfo.hasErrors()) {
            if (DomainUtils.hasErrorCode(validatedCardTransactionCapturedRawInfo, "payment.successfully.captured")) {
                delete(cardTransactionCapturedRawInfo)
            } else {
                setAsError(cardTransactionCapturedRawInfo)
            }

            return validatedCardTransactionCapturedRawInfo
        }

        return refundCreditPendingItem(cardTransactionCapturedRawInfo)
    }

    private CardTransactionCapturedRawInfo refundDebitPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        if (!cardTransactionCapturedRawInfo.captureValidationStatus.isPending()) throw new RuntimeException("Item não está pendente.")

        Customer customer = Customer.read(cardTransactionCapturedRawInfo.customerId)
        CustomerAccount customerAccount = CustomerAccount.read(cardTransactionCapturedRawInfo.customerAccountId)

        Map refundResponseMap = AsaasApplicationHolder.applicationContext.debitCardService.refund(customer, customerAccount, cardTransactionCapturedRawInfo.transactionIdentifier)

        if (!refundResponseMap.success) throw new RuntimeException("Não foi possível processar o estorno [cardTransactionCapturedRawInfoId: ${cardTransactionCapturedRawInfo.id}].")

        setAsRefunded(cardTransactionCapturedRawInfo)

        return cardTransactionCapturedRawInfo
    }

    private CardTransactionCapturedRawInfo refundCreditPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        if (!cardTransactionCapturedRawInfo.captureValidationStatus.isPending()) return DomainUtils.addErrorWithErrorCode(cardTransactionCapturedRawInfo, "invalid.transaction.refund", "Item não está pendente.")

        Customer customer = Customer.read(cardTransactionCapturedRawInfo.customerId)
        CustomerAccount customerAccount = CustomerAccount.read(cardTransactionCapturedRawInfo.customerAccountId)

        Map refundResponseMap = AsaasApplicationHolder.applicationContext.creditCardService.processRefund(
            customer,
            customerAccount,
            cardTransactionCapturedRawInfo.gateway,
            cardTransactionCapturedRawInfo.transactionIdentifier,
            cardTransactionCapturedRawInfo.transactionReference,
            cardTransactionCapturedRawInfo.amountInCents,
            cardTransactionCapturedRawInfo.refundReferenceCode
        )

        if (!refundResponseMap.success) return DomainUtils.addErrorWithErrorCode(cardTransactionCapturedRawInfo, "invalid.transaction.refund", "Não foi possível processar o estorno [message: ${refundResponseMap.message}].")

        setAsRefunded(cardTransactionCapturedRawInfo)

        return cardTransactionCapturedRawInfo
    }

    private void validateIfCanProcessDebitPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        if (!cardTransactionCapturedRawInfo.captureValidationStatus.isPending()) throw new RuntimeException("Item não está pendente.")
        if (!cardTransactionCapturedRawInfo.cardType.isDebit()) throw new RuntimeException("Somente cartão de débito pode ser processado.")
        if (!cardTransactionCapturedRawInfo.paymentId) throw new RuntimeException("ID da cobrança não informado.")
        if (!cardTransactionCapturedRawInfo.customerId) throw new RuntimeException("ID do cliente não informado.")
        if (!cardTransactionCapturedRawInfo.customerAccountId) throw new RuntimeException("ID do pagador não informado.")
        if (!cardTransactionCapturedRawInfo.amountInCents) throw new RuntimeException("Valor não informado.")

        Boolean hasCaptureEvent = DebitCardTransactionLog.query([exists: true, transactionIdentifier: cardTransactionCapturedRawInfo.transactionIdentifier, event: DebitCardTransactionEvent.CAPTURE_SUCCESS]).get().asBoolean()
        if (!hasCaptureEvent) throw new RuntimeException("Não foi encontrado evento de captura com sucesso para este TID.")

        Boolean hasRefundEvent = DebitCardTransactionLog.query([exists: true, transactionIdentifier: cardTransactionCapturedRawInfo.transactionIdentifier, event: DebitCardTransactionEvent.REFUND]).get().asBoolean()
        if (hasRefundEvent) throw new RuntimeException("Já existe solicitação de estorno para este TID.")
    }

    private CardTransactionCapturedRawInfo validateIfCanProcessCreditPendingItem(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        CardTransactionCapturedRawInfo validatedCardTransactionCapturedRawInfo = new CardTransactionCapturedRawInfo()

        if (!cardTransactionCapturedRawInfo.captureValidationStatus.isPending()) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "Item não está pendente.")
        if (!cardTransactionCapturedRawInfo.cardType.isCredit()) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "Somente cartão de crédito pode ser processado.")
        if (!cardTransactionCapturedRawInfo.paymentId) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "ID da cobrança não informado.")
        if (!cardTransactionCapturedRawInfo.customerId) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "ID do cliente não informado.")
        if (!cardTransactionCapturedRawInfo.customerAccountId) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "ID do pagador não informado.")
        if (!cardTransactionCapturedRawInfo.amountInCents) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "Valor não informado.")

        if (cardTransactionCapturedRawInfo.gateway.isCielo() && !cardTransactionCapturedRawInfo.transactionReference) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "ID da transação na CIELO não informado.")
        if (cardTransactionCapturedRawInfo.gateway.isMundipagg() && !cardTransactionCapturedRawInfo.refundReferenceCode) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "ID da transação na MUNDIPAGG não informado.")
        if (cardTransactionCapturedRawInfo.gateway.isCybersource() && (!cardTransactionCapturedRawInfo.refundReferenceCode || !cardTransactionCapturedRawInfo.transactionReference)) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "Dados para estorno na CYBERSOURCE incompletos.")

        Boolean hasCaptureEvent = CreditCardTransactionLog.query([transactionIdentifier: cardTransactionCapturedRawInfo.transactionIdentifier, event: CreditCardTransactionEvent.CAPTURE_SUCCESS, column: "id"]).get().asBoolean()
        if (!hasCaptureEvent) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "Não foi encontrado evento de captura com sucesso para este TID.")

        Boolean hasRefundEvent = CreditCardTransactionLog.query([transactionIdentifier: cardTransactionCapturedRawInfo.transactionIdentifier, event: CreditCardTransactionEvent.REFUND, column: "id"]).get().asBoolean()
        if (hasRefundEvent) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "invalid.transaction.refund", "Já existe solicitação de estorno para este TID.")

        Map payment = Payment.query([id: cardTransactionCapturedRawInfo.paymentId, columnList: ["creditCardTid", "status", "installment.id"]]).get()

        if (payment && payment.creditCardTid && payment.creditCardTid == cardTransactionCapturedRawInfo.transactionIdentifier) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "payment.successfully.captured", "Existe cobrança com sucesso para esta transação.")
        if (payment && payment.status.isAwaitingRiskAnalysis()) {
            Map queryParams = [:]
            queryParams.status = CreditCardTransactionAnalysisStatus.PENDING
            queryParams.column = "transactionIdentifier"

            if (payment."installment.id") {
                queryParams.installmentId = payment."installment.id"
            } else {
                queryParams.paymentId = cardTransactionCapturedRawInfo.paymentId
            }

            String creditCardTransactionAnalysisTid = CreditCardTransactionAnalysis.query(queryParams).get()

            if (creditCardTransactionAnalysisTid && creditCardTransactionAnalysisTid == cardTransactionCapturedRawInfo.transactionIdentifier) return DomainUtils.addErrorWithErrorCode(validatedCardTransactionCapturedRawInfo, "payment.successfully.captured", "Existe cobrança com sucesso para esta transação.")
        }

        return validatedCardTransactionCapturedRawInfo
    }

    private CardTransactionCapturedRawInfo setAsError(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        cardTransactionCapturedRawInfo.captureValidationStatus = CardTransactionCapturedRawInfoValidationStatus.ERROR
        cardTransactionCapturedRawInfo.save(failOnError: true)

        return cardTransactionCapturedRawInfo
    }

    private CardTransactionCapturedRawInfo setAsRefunded(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        cardTransactionCapturedRawInfo.captureValidationStatus = CardTransactionCapturedRawInfoValidationStatus.REFUNDED
        cardTransactionCapturedRawInfo.save(failOnError: true)

        return cardTransactionCapturedRawInfo
    }

    private void delete(CardTransactionCapturedRawInfo cardTransactionCapturedRawInfo) {
        cardTransactionCapturedRawInfo.delete(failOnError: true)
    }
}
