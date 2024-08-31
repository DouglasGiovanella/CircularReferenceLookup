package com.asaas.service.integration.pix

import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.claim.HermesGetExternalClaimResponseDTO
import com.asaas.integration.pix.dto.claim.HermesListExternalClaimResponseDTO
import com.asaas.integration.pix.dto.claim.cancel.HermesCancelClaimRequestDTO
import com.asaas.integration.pix.dto.claim.list.HermesListExternalClaimRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixAddressKeyClaimCancellationReason
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyExternalClaimAdapter
import com.asaas.pix.adapter.claim.PixAddressKeyExternalClaimListAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixAddressKeyExternalClaimManagerService {

    public PixCustomerAddressKeyExternalClaimAdapter approve(Customer customer, String id) {
        if (AsaasEnvironment.isDevelopment()) return new PixCustomerAddressKeyExternalClaimAdapter(new MockJsonUtils("pix/PixAddressKeyExternalClaimManagerService/approve.json").buildMock(HermesGetExternalClaimResponseDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${customer.id}/externalClaims/${id}/approve", [:], null)

        if (hermesManager.isSuccessful()) {
            HermesGetExternalClaimResponseDTO hermesGetExternalClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetExternalClaimResponseDTO)
            return new PixCustomerAddressKeyExternalClaimAdapter(hermesGetExternalClaimResponseDTO)
        }

        AsaasLogger.error("PixAddressKeyExternalClaimManagerService.approve() -> O seguinte erro foi retornado ao aprovar o external claim da chave com id ${id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixCustomerAddressKeyExternalClaimAdapter find(String id, Customer customer) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/externalClaims/${id}", [:])

        if (hermesManager.isSuccessful()) {
            HermesGetExternalClaimResponseDTO hermesGetExternalClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetExternalClaimResponseDTO)
            return new PixCustomerAddressKeyExternalClaimAdapter(hermesGetExternalClaimResponseDTO)
        }

        AsaasLogger.error("PixAddressKeyExternalClaimManagerService.find() -> O seguinte erro foi retornado ao buscar o external claim da chave com id ${id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixAddressKeyExternalClaimListAdapter list(Customer customer, Map filters, Integer limit, Integer offset) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/externalClaims", new HermesListExternalClaimRequestDTO(filters, limit, offset).properties)

        if (hermesManager.isSuccessful()) {
            HermesListExternalClaimResponseDTO hermesListExternalClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListExternalClaimResponseDTO)
             return new PixAddressKeyExternalClaimListAdapter(hermesListExternalClaimResponseDTO)
        }

        if (hermesManager.isNotFound()) return null

        AsaasLogger.error("PixAddressKeyExternalClaimManagerService.list() -> O seguinte erro foi retornado ao buscar external claims do cliente ${customer.id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixCustomerAddressKeyExternalClaimAdapter cancel(Customer customer, String id, PixAddressKeyClaimCancellationReason cancellationReason) {
        if (AsaasEnvironment.isDevelopment()) return new PixCustomerAddressKeyExternalClaimAdapter(new MockJsonUtils("pix/PixAddressKeyExternalClaimManagerService/cancel.json").buildMock(HermesGetExternalClaimResponseDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${customer.id}/externalClaims/${id}/cancel", new HermesCancelClaimRequestDTO(cancellationReason).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesGetExternalClaimResponseDTO hermesGetExternalClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetExternalClaimResponseDTO)
            return new PixCustomerAddressKeyExternalClaimAdapter(hermesGetExternalClaimResponseDTO)
        }

        AsaasLogger.error("PixAddressKeyExternalClaimManagerService.cancel() -> O seguinte erro foi retornado ao cancelar o external claim da chave com id ${id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }
}
