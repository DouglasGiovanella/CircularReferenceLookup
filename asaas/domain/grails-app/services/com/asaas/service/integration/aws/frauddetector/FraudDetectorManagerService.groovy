package com.asaas.service.integration.aws.frauddetector

import com.amazonaws.services.frauddetector.model.GetEventPredictionResult
import com.asaas.domain.creditcard.CreditCardTransactionAttempt
import com.asaas.domain.payment.ClearsaleResponse
import com.asaas.domain.payment.Payment
import com.asaas.frauddetector.adapter.FraudDetectorEventPredictionAdapter
import com.asaas.integration.aws.managers.FraudDetectorManager
import com.asaas.integration.aws.managers.dto.frauddetector.creditcardtransaction.FraudDetectorCreditCardTransactionEventPredictionRequestDTO
import grails.transaction.Transactional

@Transactional
class FraudDetectorManagerService {

    public FraudDetectorEventPredictionAdapter predictCreditCardTransactionFraud(CreditCardTransactionAttempt creditCardTransactionAttempt, Payment payment, ClearsaleResponse clearsaleResponse, Map transactionLogInfo) {
        FraudDetectorCreditCardTransactionEventPredictionRequestDTO requestDTO = new FraudDetectorCreditCardTransactionEventPredictionRequestDTO(
            creditCardTransactionAttempt,
            payment,
            clearsaleResponse,
            transactionLogInfo
        )

        GetEventPredictionResult eventPredictionResult = new FraudDetectorManager().getEventPrediction(requestDTO)

        return new FraudDetectorEventPredictionAdapter(requestDTO, eventPredictionResult)
    }
}
