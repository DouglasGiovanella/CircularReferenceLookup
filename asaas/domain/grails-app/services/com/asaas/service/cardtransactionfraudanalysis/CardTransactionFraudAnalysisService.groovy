package com.asaas.service.cardtransactionfraudanalysis

import com.asaas.cardtransactionfraudanalysis.adapter.CardTransactionFraudAnalysisResponseAdapter
import com.asaas.domain.cardtransactionfraudanalysis.CardTransactionFraudAnalysis

import grails.transaction.Transactional

@Transactional
class CardTransactionFraudAnalysisService {

    public CardTransactionFraudAnalysis save(CardTransactionFraudAnalysisResponseAdapter cardTransactionFraudAnalysisResponseAdapter) {
        validateSave(cardTransactionFraudAnalysisResponseAdapter)

        CardTransactionFraudAnalysis cardTransactionFraudAnalysis = new CardTransactionFraudAnalysis()
        cardTransactionFraudAnalysis.customerId = cardTransactionFraudAnalysisResponseAdapter.customerId
        cardTransactionFraudAnalysis.externalId = cardTransactionFraudAnalysisResponseAdapter.externalId
        cardTransactionFraudAnalysis.gateway = cardTransactionFraudAnalysisResponseAdapter.gateway
        cardTransactionFraudAnalysis.installmentId = cardTransactionFraudAnalysisResponseAdapter.installmentId
        cardTransactionFraudAnalysis.paymentId = cardTransactionFraudAnalysisResponseAdapter.paymentId
        cardTransactionFraudAnalysis.score = cardTransactionFraudAnalysisResponseAdapter.score
        cardTransactionFraudAnalysis.status = cardTransactionFraudAnalysisResponseAdapter.status
        cardTransactionFraudAnalysis.transactionValue = cardTransactionFraudAnalysisResponseAdapter.transactionValue
        cardTransactionFraudAnalysis.save(failOnError: true)

        return cardTransactionFraudAnalysis
    }

    private void validateSave(CardTransactionFraudAnalysisResponseAdapter cardTransactionFraudAnalysisResponseAdapter) {
        if (!cardTransactionFraudAnalysisResponseAdapter.customerId) throw new RuntimeException("Informe o ID do cliente.")
        if (!cardTransactionFraudAnalysisResponseAdapter.gateway) throw new RuntimeException("Informe o gateway.")
        if (!cardTransactionFraudAnalysisResponseAdapter.paymentId) throw new RuntimeException("Informe o ID da cobrança.")
        if (!cardTransactionFraudAnalysisResponseAdapter.status) throw new RuntimeException("Informe o status.")
        if (!cardTransactionFraudAnalysisResponseAdapter.transactionValue) throw new RuntimeException("Informe o valor da transação.")
    }
}
