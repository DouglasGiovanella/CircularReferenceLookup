package com.asaas.service.api.pix

import com.asaas.api.ApiMobileUtils
import com.asaas.api.pix.ApiPixExternalClaimParser
import com.asaas.pix.PixAddressKeyClaimCancellationReason
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyExternalClaimAdapter
import com.asaas.pix.adapter.claim.PixAddressKeyExternalClaimListAdapter
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiPixExternalClaimService extends ApiBaseService {

    def apiResponseBuilderService
    def pixAddressKeyExternalClaimService

    public Map show(Map params) {
        withValidation({
            PixCustomerAddressKeyExternalClaimAdapter pixCustomerAddressKeyExternalClaimAdapter = pixAddressKeyExternalClaimService.find(params.id, getProviderInstance(params))

            if (!pixCustomerAddressKeyExternalClaimAdapter) return apiResponseBuilderService.buildNotFoundItem()
            return apiResponseBuilderService.buildSuccess(ApiPixExternalClaimParser.buildResponseItem(pixCustomerAddressKeyExternalClaimAdapter, [:]))
        })
    }

    public Map list(Map params) {
        withValidation({
            Map filters = ApiPixExternalClaimParser.parseListFilters(params)

            PixAddressKeyExternalClaimListAdapter pixAddressKeyExternalClaimListAdapter = pixAddressKeyExternalClaimService.list(getProviderInstance(params), filters, getLimit(params), getOffset(params))
            return apiResponseBuilderService.buildSuccess(ApiPixExternalClaimParser.buildResponseItem(pixAddressKeyExternalClaimListAdapter, [:]))
        })
    }

    public Map approve(Map params) {
        withValidation({
            PixCustomerAddressKeyExternalClaimAdapter pixCustomerAddressKeyExternalClaimAdapter = pixAddressKeyExternalClaimService.approve(getProviderInstance(params), params.id)
            return apiResponseBuilderService.buildSuccess(ApiPixExternalClaimParser.buildResponseItem(pixCustomerAddressKeyExternalClaimAdapter, [:]))
        })
    }

    public Map cancel(Map params) {
        withValidation({
            PixCustomerAddressKeyExternalClaimAdapter pixCustomerAddressKeyExternalClaimAdapter = pixAddressKeyExternalClaimService.cancel(getProviderInstance(params), params.id, PixAddressKeyClaimCancellationReason.USER_REQUESTED)
            return apiResponseBuilderService.buildSuccess(ApiPixExternalClaimParser.buildResponseItem(pixCustomerAddressKeyExternalClaimAdapter, [:]))
        })
    }

    private withValidation(Closure action) {
        if (ApiMobileUtils.isMobileAppRequest()) return action()

        return apiResponseBuilderService.buildNotFoundItem()
    }
}
