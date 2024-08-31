package com.asaas.service.integration.ocean

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.ocean.OceanManager
import com.asaas.integration.ocean.dto.acquisition.OceanAcquisitionCreditAuthorizeRequestDTO
import com.asaas.receivableanticipationpartner.adapter.CreateCustomerAdapter
import com.asaas.receivableanticipationpartner.adapter.CustomerAdapter
import com.asaas.receivableanticipationpartner.adapter.CustomerDocumentAdapter
import com.asaas.receivableanticipationpartner.adapter.CreditAuthorizationInfoAdapter
import com.asaas.integration.ocean.dto.customer.OceanCreateCustomerRequestDTO
import com.asaas.integration.ocean.dto.customer.OceanCreateCustomerResponseDTO
import com.asaas.integration.ocean.dto.receivableanticipation.OceanObtainAnticipationCreditedValueRequestDTO
import com.asaas.integration.ocean.dto.receivableanticipation.OceanAuthorizeAnticipationCreditResponseDTO
import com.asaas.integration.ocean.dto.user.OceanUserEnableRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class OceanManagerService {

    public void obtainAnticipationCreditedValue(ReceivableAnticipation receivableAnticipation) {
        if (!OceanManager.isAvailable() && !AsaasEnvironment.isProduction()) return

        OceanObtainAnticipationCreditedValueRequestDTO requestDTO = new OceanObtainAnticipationCreditedValueRequestDTO(receivableAnticipation)

        OceanManager manager = new OceanManager()
        manager.post("/api/v1/receivable-anticipation", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("OceanManagerService.obtainAnticipationCreditedValue >> Erro ao enviar antecipação [${receivableAnticipation.id}] ao Ocean")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public CreditAuthorizationInfoAdapter requestAcquisitionCreditAuthorization(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        if (!OceanManager.isAvailable() && !AsaasEnvironment.isProduction()) return new CreditAuthorizationInfoAdapter(new MockJsonUtils("ocean/OceanManagerService/requestCreditAuthorization.json").buildMock(OceanAuthorizeAnticipationCreditResponseDTO))

        OceanAcquisitionCreditAuthorizeRequestDTO requestDTO = new OceanAcquisitionCreditAuthorizeRequestDTO(partnerAcquisition)

        OceanManager manager = new OceanManager()
        manager.post("/api/v1/receivable-anticipation/acquisition", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("OceanManagerService.requestAcquisitionCreditAuthorization >> Erro ao enviar aquisição [${partnerAcquisition.id}] ao Ocean para aprovação de crédito")
            throw new RuntimeException(manager.getErrorMessage())
        }

        OceanAuthorizeAnticipationCreditResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), OceanAuthorizeAnticipationCreditResponseDTO) as OceanAuthorizeAnticipationCreditResponseDTO

        return new CreditAuthorizationInfoAdapter(responseDTO)
    }

    public void enableUser(Long userId, String username) {
        if (!OceanManager.isAvailable() && !AsaasEnvironment.isProduction()) return

        OceanUserEnableRequestDTO requestDTO = new OceanUserEnableRequestDTO(userId, username)

        OceanManager manager = new OceanManager()
        manager.post("/api/v1/user/enable", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("OceanManagerService.enableUser >> Erro ao habilitar usuário [userId: ${userId}]")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public void disableUser(Long userId) {
        if (!OceanManager.isAvailable() && !AsaasEnvironment.isProduction()) return

        OceanManager manager = new OceanManager()
        manager.put("/api/v1/user/disable/${userId}", null)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("OceanManagerService.disableUser >> Erro ao desabilitar usuário [userId: ${userId}]")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public CustomerAdapter saveCustomer(CreateCustomerAdapter createCustomerAdapter, List<CustomerDocumentAdapter> customerDocumentAdapterList) {
        if (!OceanManager.isAvailable() && !AsaasEnvironment.isProduction()) return new CustomerAdapter(new MockJsonUtils("ocean/OceanManagerService/requestSaveCustomer.json").buildMock(OceanCreateCustomerResponseDTO))

        OceanCreateCustomerRequestDTO requestDTO = new OceanCreateCustomerRequestDTO(createCustomerAdapter, customerDocumentAdapterList)

        OceanManager manager = new OceanManager()
        manager.enableLogging()
        manager.post("/api/v1/customer", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("OceanManagerService.saveCustomer >> Erro ao salvar customer [customerId: ${createCustomerAdapter.asaasId}]")
            throw new RuntimeException(manager.getErrorMessage())
        }

        OceanCreateCustomerResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), OceanCreateCustomerResponseDTO)
        return new CustomerAdapter(responseDTO)
    }
}
