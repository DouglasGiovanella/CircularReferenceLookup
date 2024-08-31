package com.asaas.service.api.pix

import com.asaas.api.ApiMobileUtils
import com.asaas.api.pix.ApiPixAsaasClaimParser
import com.asaas.pix.PixAddressKeyClaimCancellationReason
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyClaimAdapter
import com.asaas.pix.adapter.claim.PixAddressKeyClaimListAdapter
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiPixAsaasClaimService extends ApiBaseService {

    def apiResponseBuilderService
    def pixAddressKeyAsaasClaimService

    public Map show(Map params) {
        withValidation({
            PixCustomerAddressKeyClaimAdapter pixCustomerAddressKeyClaimAdapter = pixAddressKeyAsaasClaimService.find(params.id, getProviderInstance(params))

            if (!pixCustomerAddressKeyClaimAdapter) return apiResponseBuilderService.buildNotFoundItem()
            return apiResponseBuilderService.buildSuccess(ApiPixAsaasClaimParser.buildResponseItem(pixCustomerAddressKeyClaimAdapter, [:]))
        })
    }

    public Map list(Map params) {
        withValidation({
            Map filters = ApiPixAsaasClaimParser.parseListFilters(params)

            PixAddressKeyClaimListAdapter pixAddressKeyListAdapter = pixAddressKeyAsaasClaimService.list(getProviderInstance(params), filters, getLimit(params), getOffset(params))
            return apiResponseBuilderService.buildSuccess(ApiPixAsaasClaimParser.buildResponseItem(pixAddressKeyListAdapter, [:]))
        })
    }

    public Map cancel(Map params) {
        withValidation({
            PixCustomerAddressKeyClaimAdapter pixCustomerAddressKeyClaimAdapter = pixAddressKeyAsaasClaimService.cancel(getProviderInstance(params), params.id, PixAddressKeyClaimCancellationReason.USER_REQUESTED)
            return apiResponseBuilderService.buildSuccess(ApiPixAsaasClaimParser.buildResponseItem(pixCustomerAddressKeyClaimAdapter, [:]))
        })
    }

    private withValidation(Closure action) {
        if (ApiMobileUtils.isMobileAppRequest()) return action()

        return apiResponseBuilderService.buildNotFoundItem()
    }
}
