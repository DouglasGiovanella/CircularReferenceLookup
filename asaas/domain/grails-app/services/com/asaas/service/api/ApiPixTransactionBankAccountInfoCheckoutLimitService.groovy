package com.asaas.service.api

import com.asaas.api.ApiPixTransactionBankAccountInfoCheckoutLimitParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiPixTransactionBankAccountInfoCheckoutLimitService extends ApiBaseService {

    def apiResponseBuilderService
    def pixTransactionBankAccountInfoCheckoutLimitService

     public Map cancel(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixTransactionBankAccountInfoCheckoutLimitParser.parseRequestParams(params)

        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([bankAccountInfoId: fields.bankAccountInfoId, customer: customer]).get()

        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimitDeleted = pixTransactionBankAccountInfoCheckoutLimitService.delete(pixTransactionBankAccountInfoCheckoutLimit)

        if (pixTransactionBankAccountInfoCheckoutLimitDeleted.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransactionBankAccountInfoCheckoutLimitDeleted)
        }

        return apiResponseBuilderService.buildSuccess([:])
    }

}
