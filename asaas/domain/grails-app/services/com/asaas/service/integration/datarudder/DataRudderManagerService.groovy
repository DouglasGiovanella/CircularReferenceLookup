package com.asaas.service.integration.datarudder

import com.asaas.cardtransactionfraudanalysis.adapter.CardTransactionFraudAnalysisResponseAdapter
import com.asaas.creditcard.CreditCardTransactionInfoVo
import com.asaas.integration.datarudder.api.DataRudderManager
import com.asaas.integration.datarudder.dto.transactionfraudanalysis.DataRudderTransactionFraudAnalysisRequestDTO
import com.asaas.integration.datarudder.dto.transactionfraudanalysis.DataRudderTransactionFraudAnalysisResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON

import grails.transaction.Transactional

@Transactional
class DataRudderManagerService {

    public CardTransactionFraudAnalysisResponseAdapter doTransactionFraudAnalysis(CreditCardTransactionInfoVo creditCardTransactionInfo) {
        DataRudderTransactionFraudAnalysisRequestDTO dataRudderTransactionFraudAnalysisRequestDTO = new DataRudderTransactionFraudAnalysisRequestDTO(creditCardTransactionInfo)

        DataRudderManager dataRudderManager = new DataRudderManager()
        dataRudderManager.post("/api/v1/fraud/transactions/", dataRudderTransactionFraudAnalysisRequestDTO.properties)

        DataRudderTransactionFraudAnalysisResponseDTO dataRudderTransactionFraudAnalysisResponseDTO = GsonBuilderUtils.buildClassFromJson((dataRudderManager.responseBody as JSON).toString(), DataRudderTransactionFraudAnalysisResponseDTO)

        final Boolean transactionSuccess = dataRudderManager.isSuccessful()

        if (!transactionSuccess) {
            AsaasLogger.error("DataRudderManagerService.doTransactionFraudAnalysis >>> Erro ao processar a análise de fraude na Data Rudder [statusCode: ${dataRudderTransactionFraudAnalysisResponseDTO.status_code}]")
        }

        return new CardTransactionFraudAnalysisResponseAdapter(dataRudderTransactionFraudAnalysisResponseDTO, creditCardTransactionInfo, transactionSuccess)
    }
}
