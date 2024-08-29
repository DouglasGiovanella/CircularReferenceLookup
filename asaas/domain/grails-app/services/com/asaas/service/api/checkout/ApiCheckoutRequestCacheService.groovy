package com.asaas.service.api.checkout

import com.asaas.cache.api.ApiTransferExternalIdentifierCacheVO
import com.asaas.domain.transfer.Transfer
import com.asaas.transfer.TransferStatus

import grails.plugin.cache.CachePut
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class ApiCheckoutRequestCacheService {

    def grailsCacheManager

    @SuppressWarnings("UnusedMethodParameter")
    @CachePut(value = "ApiCheckoutRequestCache:byCustomerIdAndExternalCheckoutId", key = "#customerId + ':' + #encryptedRequest")
    public ApiTransferExternalIdentifierCacheVO save(Long customerId, encryptedRequest) {
        ApiTransferExternalIdentifierCacheVO apiTransferExternalIdentifierCacheVO = new ApiTransferExternalIdentifierCacheVO()
        apiTransferExternalIdentifierCacheVO.encryptedRequest = encryptedRequest
        return apiTransferExternalIdentifierCacheVO
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CachePut(value = "ApiCheckoutRequestCache:byCustomerIdAndExternalCheckoutId", key = "#customerId + ':' + #encryptedRequest")
    public ApiTransferExternalIdentifierCacheVO setTransferPublicId(Long customerId, String encryptedRequest, ApiTransferExternalIdentifierCacheVO externalIdentifierCacheVO, String transferPublicId) {
        externalIdentifierCacheVO.transferPublicId = transferPublicId
        return externalIdentifierCacheVO
    }

    @SuppressWarnings("UnusedMethodParameter")
    @Cacheable(value = "ApiCheckoutRequestCache:byCustomerIdAndExternalCheckoutId", key = "#customerId + ':' + #encodedOperation")
    public ApiTransferExternalIdentifierCacheVO get(Long customerId, String encodedOperation) {
        return null
    }

    public void evict(Long customerId, String encodedOperation) {
        grailsCacheManager.getCache("ApiCheckoutRequestCache:byCustomerIdAndExternalCheckoutId").evict(customerId + ":" + encodedOperation)
    }

    public String validate(Long customerId, ApiTransferExternalIdentifierCacheVO externalIdentifierCacheVO) {
        if (!externalIdentifierCacheVO.transferPublicId) return "Saque em processamento"

        TransferStatus status = Transfer.query([customerId: customerId, publicId: externalIdentifierCacheVO.transferPublicId, column: "status"]).get()
        if (status.isCancelled() || status.isFailed()) return null

        return "Saque ${externalIdentifierCacheVO.transferPublicId} já solicitado"
    }

    public String buildHash(Long customerId, Map transferInfo) {
        String operation = new StringBuilder(customerId.toString())
            .append(transferInfo.value.toString())
            .append(transferInfo.pixAddressKey.toString())
            .toString()

        return operation.encodeAsMD5()
    }
}
