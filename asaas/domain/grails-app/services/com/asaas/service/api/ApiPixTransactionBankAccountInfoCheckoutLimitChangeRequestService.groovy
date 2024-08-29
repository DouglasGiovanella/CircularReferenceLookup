package com.asaas.service.api

import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiPixTransactionBankAccountInfoCheckoutLimitChangeRequestParser
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequest
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiPixTransactionBankAccountInfoCheckoutLimitChangeRequestService extends ApiBaseService {

    def apiResponseBuilderService
    def criticalActionService
    def pixTransactionBankAccountInfoCheckoutLimitChangeRequestService

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionBankAccountInfoCheckoutLimitChangeRequestParser.parseRequestParams(params)

        BankAccountInfo bankAccountInfo = BankAccountInfo.find(fields.bankAccountInfoId, customer.id)

        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.save(customer, bankAccountInfo, fields.value, fields.tokenParams)
        if (pixTransactionBankAccountInfoCheckoutLimitChangeRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
        }

        return apiResponseBuilderService.buildSuccess([:])
    }

    public Map requestToken(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionBankAccountInfoCheckoutLimitChangeRequestParser.parseRequestParams(params)

        CriticalActionGroup criticalActionGroup = pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.requestAuthorizationToken(customer, fields.bankAccountInfoId, fields.value)
        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }

     public Map cancel(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionBankAccountInfoCheckoutLimitChangeRequestParser.parseRequestParams(params)

        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.query([id: fields.id, bankAccountInfoId: fields.bankAccountInfoId, customer: customer]).get()

        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequestCancelled = pixTransactionBankAccountInfoCheckoutLimitChangeRequestService.cancel(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
        if (pixTransactionBankAccountInfoCheckoutLimitChangeRequestCancelled.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransactionBankAccountInfoCheckoutLimitChangeRequestCancelled)
        }

        return apiResponseBuilderService.buildSuccess([:])
    }

}
