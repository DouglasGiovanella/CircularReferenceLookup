package com.asaas.service.integration.asaaserp

import com.asaas.domain.asaaserp.AsaasErpFinancialTransactionNotification
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.AsaasErpUndefinedErrorException
import com.asaas.integration.asaaserp.api.AsaasErpManager
import com.asaas.integration.asaaserp.dto.financialtransactionnotification.AsaasErpFinancialTransactionNotificationRequestDTO
import grails.transaction.Transactional

@Transactional
class AsaasErpFinancialTransactionNotificationManagerService {

    public void send(AsaasErpFinancialTransactionNotification asaasErpFinancialTransactionNotification) {
        if (AsaasEnvironment.isDevelopment()) return

        AsaasErpFinancialTransactionNotificationRequestDTO requestDTO = new AsaasErpFinancialTransactionNotificationRequestDTO(asaasErpFinancialTransactionNotification.asaasErpCustomerConfig, asaasErpFinancialTransactionNotification.financialTransaction?.id)

        String apiKey = asaasErpFinancialTransactionNotification.asaasErpCustomerConfig.getDecryptedApiKey()
        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.post("/api/asaas/user-bank-statement/sync", requestDTO.properties)

        if (!asaasErpManager.isSuccessful()) {
            if (asaasErpManager.isErrorWithRetryEnabled()) throw new AsaasErpUndefinedErrorException("Ocorreu um erro ao notificar a atualização de extrato: id[${asaasErpFinancialTransactionNotification.financialTransaction.id}].")

            throw new RuntimeException("AsaasErpFinancialTransactionNotificationManagerService.send -> O seguinte erro foi retornado ao notificar a atualização de extrato: ${asaasErpManager.getErrorMessage()}")
        }
    }
}
