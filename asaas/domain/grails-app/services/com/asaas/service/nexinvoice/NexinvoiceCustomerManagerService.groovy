package com.asaas.service.nexinvoice

import com.asaas.integration.nexinvoice.NexinvoiceManager
import com.asaas.integration.nexinvoice.dto.customer.CustomerAuthenticateRequestDTO
import com.asaas.integration.nexinvoice.dto.customer.CustomerAuthenticateResponseDTO
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceCreateCustomerRequestDTO
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceCreateCustomerResponseDTO
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceDisableCustomerRequestDTO
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceGetJwtTokenRequestDTO
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceUpdateCustomerRequestDTO
import com.asaas.integration.nexinvoice.dto.user.NexinvoiceCreateUserRequestDTO
import com.asaas.integration.nexinvoice.dto.user.NexinvoiceCreateUserResponseDTO
import com.asaas.integration.nexinvoice.dto.user.NexinvoiceDisableUserRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.nexinvoice.adapter.NexinvoiceCustomerCreateAdapter
import com.asaas.nexinvoice.adapter.NexinvoiceUserCreateAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class NexinvoiceCustomerManagerService {

    public String authenticate(String customerEmail, String nexinvoiceCustomerPublicId) {
        CustomerAuthenticateRequestDTO customerRequest = new CustomerAuthenticateRequestDTO(customerEmail, nexinvoiceCustomerPublicId)

        NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()
        nexinvoiceManager.post("api/Login/autenticarasaas", Utils.bindPropertiesFromDomainClass(customerRequest, []))

        if (!nexinvoiceManager.isSuccessful()) {
            AsaasLogger.error("NexinvoiceCustomerManagerService.authenticate -> O seguinte erro foi retornado ao realizar a autenticação [cliente: ${customerEmail}]: ${nexinvoiceManager.getErrorMessage()}")
            throw new RuntimeException("Não foi possível realizar a autenticação.")
        }

        CustomerAuthenticateResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((nexinvoiceManager.responseBody as JSON).toString(), CustomerAuthenticateResponseDTO)

        return responseDTO.urlRedirectFromAsaasToNexinvoice
    }

    public NexinvoiceCreateCustomerResponseDTO createCustomer(NexinvoiceCustomerCreateAdapter nexinvoiceCustomerCreateAdapter) {
        NexinvoiceCreateCustomerRequestDTO nexinvoiceCreateCustomerRequestDTO = new NexinvoiceCreateCustomerRequestDTO(nexinvoiceCustomerCreateAdapter)
        NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()
        nexinvoiceManager.post("api/Cliente/CriarAsaas", Utils.bindPropertiesFromDomainClass(nexinvoiceCreateCustomerRequestDTO, []))

        if (!nexinvoiceManager.isSuccessful()) {
            AsaasLogger.error("NexinvoiceCustomerManagerService.create >>> Não foi possível cadastrar o cliente [customerName: ${nexinvoiceCustomerCreateAdapter.customerName} | error: ${nexinvoiceManager.getErrorMessage()} ]")
            throw new RuntimeException("Não foi possível cadastrar o cliente.")
        }

        NexinvoiceCreateCustomerResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((nexinvoiceManager.responseBody as JSON).toString(), NexinvoiceCreateCustomerResponseDTO)
        return responseDTO
    }

    public String addNewUser(NexinvoiceUserCreateAdapter nexinvoiceUserCreateAdapter, String jwtToken) {
        NexinvoiceCreateUserRequestDTO nexinvoiceCreateUserRequestDTO = new NexinvoiceCreateUserRequestDTO(nexinvoiceUserCreateAdapter)
        NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()
        nexinvoiceManager.jwtToken = jwtToken

        nexinvoiceManager.post("api/Usuario/CriarAsaas", Utils.bindPropertiesFromDomainClass(nexinvoiceCreateUserRequestDTO, []))

        if (!nexinvoiceManager.isSuccessful()) {
            AsaasLogger.error("NexinvoiceCustomerManagerService.addNewUser >>> Não foi possível cadastrar o usuário [userId: ${nexinvoiceUserCreateAdapter.id} | error: ${nexinvoiceManager.getErrorMessage()}]")
            throw new RuntimeException("Não foi possível cadastrar o usuário.")
        }

        NexinvoiceCreateUserResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((nexinvoiceManager.responseBody as JSON).toString(), NexinvoiceCreateUserResponseDTO)
        return responseDTO.idUsuarioNexinvoice
    }

    public String getJwtToken(String customerEmail, String nexinvoiceCustomerPublicId) {
        NexinvoiceGetJwtTokenRequestDTO nexinvoiceCreateCustomerRequestDTO = new NexinvoiceGetJwtTokenRequestDTO(customerEmail, nexinvoiceCustomerPublicId)
        NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()
        nexinvoiceManager.post("api/Login/GerarTokenAsaas", Utils.bindPropertiesFromDomainClass(nexinvoiceCreateCustomerRequestDTO, []))

        if (!nexinvoiceManager.isSuccessful()) {
            AsaasLogger.error("NexinvoiceCustomerManagerService.getJwtToken >>> Não foi possível gerar o token JWT [customerEmail: ${customerEmail} | error: ${nexinvoiceManager.getErrorMessage()} ]")
            throw new RuntimeException("Não foi possível gerar o token JWT.")
        }

        return nexinvoiceManager.responseBody.data
    }

    public NexinvoiceCreateCustomerResponseDTO updateCustomer(String customerName, String jwtToken) {
        NexinvoiceUpdateCustomerRequestDTO nexinvoiceUpdateCustomerRequestDTO = new NexinvoiceUpdateCustomerRequestDTO(customerName)
        NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()
        nexinvoiceManager.jwtToken = jwtToken

        nexinvoiceManager.post("api/Cliente/AlterarNome", Utils.bindPropertiesFromDomainClass(nexinvoiceUpdateCustomerRequestDTO, []))

        if (nexinvoiceManager.isSuccessful()) return

        AsaasLogger.error("NexinvoiceCustomerManagerService.updateCustomer >>> Não foi possível atualizar o cliente [customerName: ${nexinvoiceCustomerUpdateAdapter.customerName} | error: ${nexinvoiceManager.getErrorMessage()} ]")
        throw new RuntimeException("Não foi possível atualizar o cliente.")
    }

    public void disableCustomer(String nexinvoiceCustomerPublicId) {
      NexinvoiceDisableCustomerRequestDTO nexinvoiceDisableCustomerRequestDTO = new NexinvoiceDisableCustomerRequestDTO(nexinvoiceCustomerPublicId)
      NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()
      nexinvoiceManager.post("api/Cliente/inativarAsaas", Utils.bindPropertiesFromDomainClass(nexinvoiceDisableCustomerRequestDTO, []))

      if (nexinvoiceManager.isSuccessful()) return

      AsaasLogger.error("NexinvoiceCustomerManagerService.disableCustomer >>> Não foi possível inativar o cliente [nexinvoiceCustomerPublicId: ${nexinvoiceCustomerPublicId} | error: ${nexinvoiceManager.getErrorMessage()} ]")
      throw new RuntimeException("Não foi possível inativar o cliente.")
    }

    public void disableUser(Long userId, String nexinvoiceCustomerPublicId) {
      NexinvoiceDisableUserRequestDTO nexinvoiceDisableUserRequestDTO = new NexinvoiceDisableUserRequestDTO(userId, nexinvoiceCustomerPublicId)
      NexinvoiceManager nexinvoiceManager = new NexinvoiceManager()

      nexinvoiceManager.post("api/Usuario/Inativar", Utils.bindPropertiesFromDomainClass(nexinvoiceDisableUserRequestDTO, []))

      if (nexinvoiceManager.isSuccessful()) return

      AsaasLogger.error("NexinvoiceCustomerManagerService.disableUser >>> Não foi possível desativar o usuário [userId: ${userId} | error: ${nexinvoiceManager.getErrorMessage()} ]")
      throw new RuntimeException("Não foi possível desativar o usuário.")
    }
}
