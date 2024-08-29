package com.asaas.service.integration.sandbox

import com.asaas.domain.customer.Customer
import com.asaas.integration.sandbox.SandboxManager
import com.asaas.integration.sandbox.dto.CreateSandboxCustomerDTO
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class SandboxManagerService {

    public Map getCustomerDetails(String email, String cpfCnpj) {
        SandboxManager sandboxManager = new SandboxManager()
        sandboxManager.get("/customers", ["email": email, "cpfCnpj": cpfCnpj])

        if (!sandboxManager.isSuccessful()) AsaasLogger.error("SandboxManagerService.getCustomerDetails -> Falha na requisição GET. Email: [${email}]. ${buildCommonErrorMessage(sandboxManager)}")
        return sandboxManager.httpRequestManager.responseBodyMap
    }

    public String createCustomer(Customer customer) {
        SandboxManager sandboxManager = new SandboxManager()

        Map params = new CreateSandboxCustomerDTO(customer).properties
        sandboxManager.post("/customers", params)

        if (!sandboxManager.isSuccessful()) AsaasLogger.error("SandboxManagerService.createCustomer -> Falha na requisição POST. Params: [${params}]. ${buildCommonErrorMessage(sandboxManager)}")
        return sandboxManager.httpRequestManager.responseBodyMap?.customerPublicId
    }

    public String generateAuthToken(String customerPublicId) {
        SandboxManager sandboxManager = new SandboxManager()
        sandboxManager.post("/auth/tokens", [publicId: customerPublicId])

        if (!sandboxManager.isSuccessful()) AsaasLogger.error("SandboxManagerService.generateAuthToken -> Falha na requisição POST. CustomerPublicId: [${customerPublicId}]. ${buildCommonErrorMessage(sandboxManager)}")
        return sandboxManager.httpRequestManager.responseBodyMap?.sandboxAuthToken
    }

    private String buildCommonErrorMessage(SandboxManager sandboxManager) {
        return "Status: [${sandboxManager.httpRequestManager.responseHttpStatus}]. Body: [${sandboxManager.httpRequestManager.responseBody}]. ErrorMessage: [${sandboxManager.httpRequestManager.errorMessage}]"
    }
}
