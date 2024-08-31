package com.asaas.service.integration.acquiring

import com.asaas.customer.CustomerCreditCardAbusePreventionParameterName
import com.asaas.domain.customer.Customer
import com.asaas.integration.acquiring.api.AcquiringManager
import com.asaas.integration.acquiring.dto.AcquiringBaseResponseDTO
import com.asaas.integration.acquiring.dto.merchant.cardabusepreventionparameter.AcquiringSaveMerchantCardAbusePreventionParameterRequestDTO
import com.asaas.integration.acquiring.dto.merchant.save.AcquiringSaveMerchantRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class AcquiringMerchantManagerService {

    public Boolean save(Customer customer) {
        AcquiringSaveMerchantRequestDTO acquiringSaveMerchantRequestDTO = new AcquiringSaveMerchantRequestDTO(customer)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.post("/api/v1/merchants", acquiringSaveMerchantRequestDTO.properties)

        AcquiringBaseResponseDTO acquiringBaseResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringBaseResponseDTO)

        final Boolean isSuccess = acquiringManager.isSuccessful() && acquiringBaseResponseDTO.success

        if (!isSuccess) AsaasLogger.warn("AcquiringMerchantManagerService.save >>> Não foi possível salvar o cliente no Acquiring [customerId: ${customer.id} | message: ${acquiringBaseResponseDTO.message}]")

        return isSuccess
    }

    public Boolean saveCardAbusePreventionParameter(Customer customer, CustomerCreditCardAbusePreventionParameterName parameterName, String value) {
        AcquiringSaveMerchantCardAbusePreventionParameterRequestDTO acquiringSaveMerchantCardAbusePreventionParameterRequestDTO = new AcquiringSaveMerchantCardAbusePreventionParameterRequestDTO(customer, parameterName, value)

        AcquiringManager acquiringManager = new AcquiringManager()
        acquiringManager.post("/api/v1/merchants/cardAbusePreventionParameters", acquiringSaveMerchantCardAbusePreventionParameterRequestDTO.properties)

        AcquiringBaseResponseDTO acquiringBaseResponseDTO = GsonBuilderUtils.buildClassFromJson((acquiringManager.responseBody as JSON).toString(), AcquiringBaseResponseDTO)

        final Boolean isSuccess = acquiringManager.isSuccessful() && acquiringBaseResponseDTO.success

        if (!isSuccess) AsaasLogger.warn("AcquiringMerchantManagerService.saveCardAbusePreventionParameter >>> Não foi possível salvar parâmetro da prevenção a abuso no Acquiring [customerId: ${customer.id} | parameterName: ${parameterName} | message: ${acquiringBaseResponseDTO.message}]")

        return isSuccess
    }
}
