package com.asaas.service.integration.sauron.fraudpreventionknowyourcustomerinfo

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sauron.adapter.fraudpreventionknowyourcustomerinfo.FraudPreventionKnowYourCustomerInfoAdapter
import com.asaas.integration.sauron.adapter.fraudpreventionknowyourcustomerinfo.FraudPreventionKnowYourCustomerInteractionAdapter
import com.asaas.integration.sauron.adapter.fraudpreventionknowyourcustomerinfo.CreateFraudPreventionKnowYourCustomerInteractionAdapter
import com.asaas.integration.sauron.adapter.fraudpreventionknowyourcustomerinfo.UpdateFraudPreventionKnowYourCustomerInfoAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.fraudpreventionknowyourcustomerinfo.SauronCreateFraudPreventionKnowYourCustomerInteractionRequestDTO
import com.asaas.integration.sauron.dto.fraudpreventionknowyourcustomerinfo.SauronUpdateFraudPreventionKnowYourCustomerInfoRequestDTO
import com.asaas.integration.sauron.dto.fraudpreventionknowyourcustomerinfo.SauronFraudPreventionKnowYourCustomerInfoResponseDTO
import com.asaas.integration.sauron.dto.fraudpreventionknowyourcustomerinfo.SauronFraudPreventionKnowYourCustomerInteractionResponseDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.compiler.GrailsCompileStatic
import grails.converters.JSON

@GrailsCompileStatic
class FraudPreventionKnowYourCustomerInfoManagerService {

    public FraudPreventionKnowYourCustomerInfoAdapter find(String cpfCnpj) {
        SauronFraudPreventionKnowYourCustomerInfoResponseDTO knowYourCustomerInfoResponseDTO

        if (AsaasEnvironment.isSandbox() || AsaasEnvironment.isDevelopment()) {
            knowYourCustomerInfoResponseDTO = new MockJsonUtils("sauron/FraudPreventionKnowYourCustomerInfo/find.json").buildMock(SauronFraudPreventionKnowYourCustomerInfoResponseDTO) as SauronFraudPreventionKnowYourCustomerInfoResponseDTO
            knowYourCustomerInfoResponseDTO.cpfCnpj = cpfCnpj
            return new FraudPreventionKnowYourCustomerInfoAdapter(knowYourCustomerInfoResponseDTO)
        }

        SauronManager sauronManager = new SauronManager()
        sauronManager.get("/fraudPreventionKnowYourCustomerInfos/${cpfCnpj}", null)

        if (!sauronManager.isSuccessful()) throw new RuntimeException("Não foi possível realizar a busca dos dados de PLDFT do cliente. Erro: ${sauronManager.responseBody}")

        knowYourCustomerInfoResponseDTO = GsonBuilderUtils.buildClassFromJson((sauronManager.responseBody as JSON).toString(), SauronFraudPreventionKnowYourCustomerInfoResponseDTO) as SauronFraudPreventionKnowYourCustomerInfoResponseDTO

        return new FraudPreventionKnowYourCustomerInfoAdapter(knowYourCustomerInfoResponseDTO)
    }

    public void update(UpdateFraudPreventionKnowYourCustomerInfoAdapter updateKnowYourCustomerInfoAdapter) {
        if (AsaasEnvironment.isSandbox() || AsaasEnvironment.isDevelopment()) return

        SauronUpdateFraudPreventionKnowYourCustomerInfoRequestDTO updateKnowYourCustomerInfoRequestDTO = new SauronUpdateFraudPreventionKnowYourCustomerInfoRequestDTO(updateKnowYourCustomerInfoAdapter)

        SauronManager sauronManager = new SauronManager()
        sauronManager.put("/fraudPreventionKnowYourCustomerInfos/${updateKnowYourCustomerInfoAdapter.cpfCnpj}", updateKnowYourCustomerInfoRequestDTO.getProperties())

        if (sauronManager.isUnprocessableEntity()) throw new RuntimeException("Ocorreu um erro durante o processamento.")
        if (!sauronManager.isSuccessful()) throw new RuntimeException("Não foi possível realizar a alterações dos dados de PLDFT do cliente. Erro: ${sauronManager.responseBody?.message}")
    }

    public FraudPreventionKnowYourCustomerInteractionAdapter createInteraction(CreateFraudPreventionKnowYourCustomerInteractionAdapter createKnowYourCustomerInteractionAdapter) {
        SauronFraudPreventionKnowYourCustomerInteractionResponseDTO knowYourCustomerInteractionResponseDTO

        if (AsaasEnvironment.isSandbox() || AsaasEnvironment.isDevelopment()) {
            knowYourCustomerInteractionResponseDTO = new MockJsonUtils("sauron/FraudPreventionKnowYourCustomerInfo/createInteraction.json").buildMock(SauronFraudPreventionKnowYourCustomerInteractionResponseDTO) as SauronFraudPreventionKnowYourCustomerInteractionResponseDTO
            return new FraudPreventionKnowYourCustomerInteractionAdapter(knowYourCustomerInteractionResponseDTO)
        }

        SauronCreateFraudPreventionKnowYourCustomerInteractionRequestDTO createKnowYourCustomerInteractionRequestDTO = new SauronCreateFraudPreventionKnowYourCustomerInteractionRequestDTO(createKnowYourCustomerInteractionAdapter)

        SauronManager sauronManager = new SauronManager()
        sauronManager.post("/fraudPreventionKnowYourCustomerInfos/${createKnowYourCustomerInteractionAdapter.cpfCnpj}/interactions", createKnowYourCustomerInteractionRequestDTO)

        if (sauronManager.isUnprocessableEntity()) throw new RuntimeException("Ocorreu um erro durante o processamento.")
        if (!sauronManager.isSuccessful()) throw new RuntimeException("Não foi possível realizar a inserção de observação de PLDFT do cliente. Erro: ${sauronManager.responseBody}")

        knowYourCustomerInteractionResponseDTO = GsonBuilderUtils.buildClassFromJson((sauronManager.responseBody as JSON).toString(), SauronFraudPreventionKnowYourCustomerInteractionResponseDTO) as SauronFraudPreventionKnowYourCustomerInteractionResponseDTO
        return new FraudPreventionKnowYourCustomerInteractionAdapter(knowYourCustomerInteractionResponseDTO)
    }
}
