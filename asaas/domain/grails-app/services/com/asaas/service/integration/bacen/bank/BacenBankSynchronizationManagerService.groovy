package com.asaas.service.integration.bacen.bank

import com.asaas.integration.bacen.bank.BacenBankManager
import com.asaas.integration.bacen.bank.adapter.ListBankAdapter
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class BacenBankSynchronizationManagerService {

    public ListBankAdapter list() {
        BacenBankManager bacenBankManager = new BacenBankManager()
        bacenBankManager.getFile("content/estabilidadefinanceira/str1/ParticipantesSTR.csv", [:])

        if (bacenBankManager.isSuccessful()) {
            ByteArrayInputStream bankDataCsv = bacenBankManager.responseFile as ByteArrayInputStream
            return new ListBankAdapter(bankDataCsv)
        }

        AsaasLogger.error("BacenBankSynchronizationManagerService.list() -> Falha ao sincronizar bancos. [errorMessage: ${bacenBankManager.getErrorMessage()}, status: ${bacenBankManager.statusCode}]")
        return null
    }

}
