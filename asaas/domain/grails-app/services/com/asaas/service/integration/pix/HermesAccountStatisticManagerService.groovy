package com.asaas.service.integration.pix

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.customer.get.HermesGetCustomerStatisticResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.accountstatistic.AccountStatisticAdapter
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HermesAccountStatisticManagerService {

    public AccountStatisticAdapter get(String cpfCnpj) {
        if (AsaasEnvironment.isDevelopment()) return new AccountStatisticAdapter(new MockJsonUtils("pix/HermesAccountStatisticManagerService/get.json").buildMock(HermesGetCustomerStatisticResponseDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/statistic?cpfCnpj=${cpfCnpj}", [:])

        if (hermesManager.isSuccessful()) {
            HermesGetCustomerStatisticResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetCustomerStatisticResponseDTO) as HermesGetCustomerStatisticResponseDTO

            return new AccountStatisticAdapter(responseDto)
        }

        if (hermesManager.isNotFound()) return null

        AsaasLogger.error("HermesAccountStatisticManagerService.get() -> Erro ao buscar estatísticas de conta [cpfCnpj: ${CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cpfCnpj)}, reason: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }
}
