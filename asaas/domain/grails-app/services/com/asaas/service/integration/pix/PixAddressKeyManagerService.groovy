package com.asaas.service.integration.pix

import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.timerecordlog.PixAddressKeyTimeRecordLog
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.addresskey.HermesGetAddressKeyResponseDTO
import com.asaas.integration.pix.dto.addresskey.list.HermesListAddressKeyResponseDTO
import com.asaas.integration.pix.dto.addresskey.PixCustomerAddressKeyResponseDTO
import com.asaas.integration.pix.dto.addresskey.delete.HermesDeleteAddressKeyRequestDTO
import com.asaas.integration.pix.dto.addresskey.findExternally.HermesFindExternallyAddressKeyRequestDTO
import com.asaas.integration.pix.dto.addresskey.findExternally.HermesFindExternallyAddressKeyResponseDTO
import com.asaas.integration.pix.dto.addresskey.list.HermesListAddressKeyRequestDTO
import com.asaas.integration.pix.dto.addresskey.save.HermesSaveAddressKeyRequestDTO
import com.asaas.integration.pix.dto.addresskey.timerecordlog.PixAddressKeyTimeRecordLogRequestDTO
import com.asaas.integration.pix.dto.base.BaseResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixAddressKeyDeleteReason
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.adapter.addresskey.AddressKeyAdapter
import com.asaas.pix.adapter.addresskey.PixCustomerAddressKeyAdapter
import com.asaas.pix.adapter.addresskey.PixAddressKeyListAdapter
import com.asaas.pix.adapter.addresskey.PixCustomerInfoAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixAddressKeyManagerService {

    public PixCustomerInfoAdapter getCustomerAddressKeyInfoList(Customer customer) {
        if (AsaasEnvironment.isDevelopment()) return new PixCustomerInfoAdapter(new MockJsonUtils("pix/PixAddressKeyManagerService/getCustomerAddressKeyInfoList.json").buildMock(PixCustomerAddressKeyResponseDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}", [:])

        if (hermesManager.isNotFound()) return null

        if (!hermesManager.isSuccessful()) {
            AsaasLogger.error("PixAddressKeyManagerService.getCustomerAddressKeyInfoList() -> O seguinte erro foi retornado ao consultar os dados do cliente ${customer.id}: ${hermesManager.getErrorMessage()}")
            throw new BusinessException(hermesManager.getErrorMessage())
        }

        PixCustomerAddressKeyResponseDTO customerAddressKeyResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), PixCustomerAddressKeyResponseDTO)

        return new PixCustomerInfoAdapter(customerAddressKeyResponseDTO)
    }


    public PixCustomerAddressKeyAdapter find(String id, Customer customer) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/keys/${id}", [:])

        if (hermesManager.isSuccessful()) {
            HermesGetAddressKeyResponseDTO pixAddressKeyExternalClaimDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetAddressKeyResponseDTO)
            return new PixCustomerAddressKeyAdapter(pixAddressKeyExternalClaimDTO)
        }

        if (hermesManager.isNotFound()) return null

        AsaasLogger.error("PixAddressKeyExternalClaimManagerService.find() -> O seguinte erro foi retornado ao buscar a chave com id ${id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixAddressKeyListAdapter list(Customer customer, Map filters, Integer limit, Integer offset) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/keys", new HermesListAddressKeyRequestDTO(filters, limit, offset).properties)

        if (hermesManager.isSuccessful()) {
            HermesListAddressKeyResponseDTO pixAddressKeyListDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListAddressKeyResponseDTO)
            return new PixAddressKeyListAdapter(pixAddressKeyListDTO)
        }

        AsaasLogger.error("PixAddressKeyManagerService.list() -> O seguinte erro foi retornado ao buscar as chaves do cliente ${customer.id}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixAddressKeyListAdapter listAll(Customer customer, Map filters) {
        Integer limit = 100
        Integer offset = 0

        PixAddressKeyListAdapter pixAdressKeysList = list(customer, filters, limit, offset)
        Boolean hasMore = pixAdressKeysList.hasMore
        limit = pixAdressKeysList.limit
        offset = pixAdressKeysList.offset

        while (hasMore) {
            offset += limit
            PixAddressKeyListAdapter listAdapter = list(customer, filters, limit, offset)
            pixAdressKeysList.data.addAll(listAdapter.data)
            hasMore = listAdapter.hasMore
            limit = listAdapter.limit
            offset = listAdapter.offset
        }

        return pixAdressKeysList
    }

    public Map validateIfCustomerCanRequestPixAddressKey(Customer customer) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customer.id}/canRequestKey", [:])

        if (hermesManager.isSuccessful()) return [success: true]

        if (hermesManager.isClientError()) {
            BaseResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
            return [success: false, errorMessage: responseDTO.errorMessage]
        }

        return [success: false, errorMessage: Utils.getMessageProperty("unknow.error")]
    }

    public AddressKeyAdapter findExternallyPixAddressKey(String key, PixAddressKeyType type, Customer currentCustomer) {
        if (AsaasEnvironment.isDevelopment()) {
            HermesFindExternallyAddressKeyResponseDTO mockedResponseDto = new MockJsonUtils("pix/PixAddressKeyManagerService/findExternallyPixAddressKey.json").buildMock(HermesFindExternallyAddressKeyResponseDTO)
            mockedResponseDto.endToEndIdentifier = UUID.randomUUID()

            return new AddressKeyAdapter(mockedResponseDto, currentCustomer)
        }

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/keys", new HermesFindExternallyAddressKeyRequestDTO(key, type, currentCustomer).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesFindExternallyAddressKeyResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesFindExternallyAddressKeyResponseDTO)

            return new AddressKeyAdapter(responseDTO, currentCustomer)
        }

        if (hermesManager.isNotFound()) return null

        if (hermesManager.isForbidden()) throw new BusinessException(hermesManager.getErrorMessage())

        if (hermesManager.isTooManyRequests()) {
            AsaasLogger.warn("PixAddressKeyManagerService.findExternallyPixAddressKey() -> O seguinte warn foi retornado ao consultar uma chave Pix: ${Utils.getMessageProperty("pix.hermesConnection.tooManyRequests")}")
            throw new BusinessException(Utils.getMessageProperty("pix.hermesConnection.tooManyRequests"))
        }

        AsaasLogger.error("PixAddressKeyManagerService.findExternallyPixAddressKey() -> O seguinte erro foi retornado ao consultar uma chave Pix: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    public PixCustomerAddressKeyAdapter save(Customer customer, String pixKey, PixAddressKeyType type, Boolean saveSynchronously) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${customer.id}/keys", new HermesSaveAddressKeyRequestDTO(pixKey, type, customer, saveSynchronously).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesGetAddressKeyResponseDTO pixAddressKeyDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetAddressKeyResponseDTO)
            return new PixCustomerAddressKeyAdapter(pixAddressKeyDTO)
        }

        if (hermesManager.isTooManyRequests()) {
            throw new BusinessException(Utils.getMessageProperty("pix.hermesConnection.tooManyRequests"))
        }

        if (hermesManager.isClientError()) throw new BusinessException(hermesManager.getErrorMessage())

        AsaasLogger.error("PixAddressKeyManagerService.save() -> O seguinte erro foi retornado ao salvar uma chave Pix: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public PixCustomerAddressKeyAdapter delete(Customer customer, String id, PixAddressKeyDeleteReason deleteReason) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.delete("/accounts/${customer.id}/keys/${id}", new HermesDeleteAddressKeyRequestDTO(deleteReason).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesGetAddressKeyResponseDTO pixAddressKeyDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetAddressKeyResponseDTO)
            return new PixCustomerAddressKeyAdapter(pixAddressKeyDTO)
        }

        AsaasLogger.error("PixAddressKeyManagerService.delete() -> O seguinte erro foi retornado ao deletar a chave [id: ${id}]: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public void sendPixAddressKeyTimeRecordLog(List<PixAddressKeyTimeRecordLog> pixAddressKeyTimeRecordLogList) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/timeRecordLog", new PixAddressKeyTimeRecordLogRequestDTO(pixAddressKeyTimeRecordLogList).properties, null)

        if (hermesManager.isSuccessful()) return

        AsaasLogger.error("PixAddressKeyManagerService.sendPixAddressKeyTimeRecordLog() -> O seguinte erro foi retornado ao sincronizar: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }
}
