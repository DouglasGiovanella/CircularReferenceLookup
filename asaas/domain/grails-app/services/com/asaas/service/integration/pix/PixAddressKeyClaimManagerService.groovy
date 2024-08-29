package com.asaas.service.integration.pix

import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.claim.HermesGetClaimResponseDTO
import com.asaas.integration.pix.dto.claim.HermesListClaimResponseDTO
import com.asaas.integration.pix.dto.claim.cancel.HermesCancelClaimRequestDTO
import com.asaas.integration.pix.dto.claim.list.HermesListClaimRequestDTO
import com.asaas.integration.pix.dto.claim.save.HermesSaveClaimRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixAddressKeyClaimCancellationReason
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyClaimAdapter
import com.asaas.pix.adapter.claim.PixAddressKeyClaimListAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixAddressKeyClaimManagerService {

    public PixCustomerAddressKeyClaimAdapter find(String id, Customer customer) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/claims/${id}", [:])

        if (hermesManager.isSuccessful()) {
            HermesGetClaimResponseDTO hermesGetClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetClaimResponseDTO)
            return new PixCustomerAddressKeyClaimAdapter(hermesGetClaimResponseDTO)
        }

        if (hermesManager.isNotFound()) return null

        AsaasLogger.error("PixAddressKeyClaimManagerService.find() -> O seguinte erro foi retornado ao buscar a claim da chave com id ${id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixAddressKeyClaimListAdapter list(Customer customer, Map filters, Integer limit, Integer offset) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/claims", new HermesListClaimRequestDTO(filters, limit, offset).properties)

        if (hermesManager.isSuccessful()) {
            HermesListClaimResponseDTO hermesListClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListClaimResponseDTO)
            return new PixAddressKeyClaimListAdapter(hermesListClaimResponseDTO)
        }

        AsaasLogger.error("PixAddressKeyClaimManagerService.list() -> O seguinte erro foi retornado ao buscar as claims do cliente ${customer.id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixCustomerAddressKeyClaimAdapter save(Customer customer, String pixKey, PixAddressKeyType type) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${customer.id}/claims", new HermesSaveClaimRequestDTO(pixKey, type, customer).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesGetClaimResponseDTO hermesGetClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetClaimResponseDTO)
            return new PixCustomerAddressKeyClaimAdapter(hermesGetClaimResponseDTO)
        }

        if (hermesManager.isClientError()) throw new BusinessException(hermesManager.getErrorMessage())

        AsaasLogger.error("PixAddressKeyClaimManagerService.save() -> O seguinte erro foi retornado ao salvar uma claim: ${hermesManager.getErrorMessage()} [PixAddressKeyType: ${type}, customer.id: ${customer.id}]")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixCustomerAddressKeyClaimAdapter cancel(Customer customer, String id, PixAddressKeyClaimCancellationReason cancellationReason) {
        if (AsaasEnvironment.isDevelopment()) return new PixCustomerAddressKeyClaimAdapter(new MockJsonUtils("pix/PixAddressKeyClaimManagerService/cancel.json").buildMock(HermesGetClaimResponseDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${customer.id}/claims/${id}/cancel", new HermesCancelClaimRequestDTO(cancellationReason).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesGetClaimResponseDTO hermesGetClaimResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetClaimResponseDTO)
            return new PixCustomerAddressKeyClaimAdapter(hermesGetClaimResponseDTO)
        }

        AsaasLogger.error("PixAddressKeyClaimManagerService.cancel() -> O seguinte erro foi retornado ao cancelar o claim da chave com id ${id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

}
