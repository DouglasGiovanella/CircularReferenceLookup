package com.asaas.service.frauddetector

import com.asaas.domain.creditcard.CreditCardTransactionAttempt
import com.asaas.domain.creditcard.CreditCardTransactionLog
import com.asaas.domain.creditcard.CreditCardTransactionLogRelatedWithAttempt
import com.asaas.domain.frauddetector.FraudDetectorEventPrediction
import com.asaas.domain.payment.ClearsaleResponse
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.frauddetector.FraudDetectorEventPredictionOutcome
import com.asaas.frauddetector.FraudDetectorEventType
import com.asaas.frauddetector.adapter.FraudDetectorEventPredictionAdapter
import grails.transaction.Transactional

@Transactional
class FraudDetectorEventPredictionService {

    def fraudDetectorManagerService

    public FraudDetectorEventPredictionOutcome predictCreditCardTransactionFraud(CreditCardTransactionAttempt creditCardTransactionAttempt) {
        if (!AsaasEnvironment.isProduction()) return FraudDetectorEventPredictionOutcome.APPROVE

        FraudDetectorEventPredictionOutcome previousEventPredictionOutcome = retrievePreviousEventPredictionOutcome(creditCardTransactionAttempt.id, FraudDetectorEventType.CREDIT_CARD_TRANSACTION_ATTEMPT)
        if (previousEventPredictionOutcome) return previousEventPredictionOutcome

        Payment payment = Payment.read(creditCardTransactionAttempt.paymentId)
        ClearsaleResponse clearsaleResponse = ClearsaleResponse.read(creditCardTransactionAttempt.clearSaleResponseId)

        List<Long> transactionLogIdList = CreditCardTransactionLogRelatedWithAttempt.query([column: "creditCardTransactionLogId", creditCardTransactionAttemptId: creditCardTransactionAttempt.id]).list()
        if (transactionLogIdList.isEmpty()) transactionLogIdList = [creditCardTransactionAttempt.creditCardTransactionLogId]
        Map transactionLogInfo = CreditCardTransactionLog.query([columnList: ["acquirerReturnCode", "gateway"], "id[in]": transactionLogIdList, "acquirerReturnCode[isNotNull]": true]).get()

        FraudDetectorEventPredictionAdapter predictionAdapter = fraudDetectorManagerService.predictCreditCardTransactionFraud(
            creditCardTransactionAttempt,
            payment,
            clearsaleResponse,
            transactionLogInfo
        )

        FraudDetectorEventPrediction prediction = save(predictionAdapter)
        return prediction.outcome
    }

    private FraudDetectorEventPredictionOutcome retrievePreviousEventPredictionOutcome(Long eventId, FraudDetectorEventType eventType) {
        return FraudDetectorEventPrediction.query([column: "outcome", eventId: eventId, eventType: eventType]).get()
    }

    private FraudDetectorEventPrediction save(FraudDetectorEventPredictionAdapter predictionAdapter) {
        FraudDetectorEventPrediction prediction = new FraudDetectorEventPrediction()
        prediction.detectorId = predictionAdapter.detectorId
        prediction.eventId = predictionAdapter.eventId
        prediction.eventType = predictionAdapter.eventType
        prediction.entityType = predictionAdapter.entityType
        prediction.entityId = predictionAdapter.entityId
        prediction.eventTimestamp = predictionAdapter.eventTimestamp
        prediction.modelId = predictionAdapter.modelId
        prediction.modelType = predictionAdapter.modelType
        prediction.modelVersion = predictionAdapter.modelVersion
        prediction.score = predictionAdapter.score
        prediction.outcome = predictionAdapter.outcome

        prediction.save(failOnError: true)
        return prediction
    }
}
