package com.asaas.service.integration.pix

import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.instantpaymentaccount.get.HermesGetBalanceRequestDTO
import com.asaas.integration.pix.dto.instantpaymentaccount.get.HermesGetBalanceResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixInstantPaymentAccountBalanceSource
import com.asaas.pix.adapter.instantpaymentaccount.PixInstantPaymentAccountBalanceAdapter
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HermesInstantPaymentAccountManagerService {

    public PixInstantPaymentAccountBalanceAdapter getBalance(PixInstantPaymentAccountBalanceSource source) {
        HermesManager manager = new HermesManager()
        manager.logged = false
        manager.get("/instantPaymentAccount/getBalance", new HermesGetBalanceRequestDTO(source).properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("HermesInstantPaymentAccountManagerService.getBalance >> Erro ao consultar saldo da Conta PI [status: ${manager.status}, error: ${manager.responseBody}]")
            throw new RuntimeException(manager.getErrorMessage())
        }

        HermesGetBalanceResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), HermesGetBalanceResponseDTO)
        return new PixInstantPaymentAccountBalanceAdapter(responseDto)
    }
}
