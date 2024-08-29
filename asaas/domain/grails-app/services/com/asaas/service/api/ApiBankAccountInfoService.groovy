package com.asaas.service.api

import com.asaas.api.ApiBankAccountInfoParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiMobileUtils
import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoUpdateRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.exception.BankAccountInfoNotFoundException

import grails.transaction.Transactional

@Transactional
class ApiBankAccountInfoService extends ApiBaseService {

    def bankAccountInfoUpdateRequestService
    def apiResponseBuilderService
    def bankAccountInfoService

    def find(params) {
        Customer customer = getProviderInstance(params)

        BaseBankAccount bankAccountInfo
        if (customer.multipleBankAccountsEnabled()) {
            bankAccountInfo = params.id ? BankAccountInfo.find(Long.valueOf(params.id), customer.id) : BankAccountInfo.findMainAccount(customer.id)
        } else {
            bankAccountInfo = BankAccountInfoUpdateRequest.findLatest(customer)
        }

        return ApiBankAccountInfoParser.buildResponseItem(bankAccountInfo)
    }

    def list(params) {
        Customer customer = getProviderInstance(params)

        Map filters = ApiBankAccountInfoParser.parseRequestParams(customer, params)

        List<BankAccountInfo> bankAccountInfoList = BankAccountInfo.listBankAccountInfoOrderedByMainAccount(customer, filters)
        List<Map> responseItems = bankAccountInfoList.collect { bankAccountInfo -> ApiBankAccountInfoParser.buildResponseItem(bankAccountInfo) }

        List<Map> extraData = []

        if (ApiMobileUtils.isMobileAppRequest()) {
            extraData << [cpfCnpjAlreadyInformed: customer.cpfCnpj != null]
            extraData << [shouldOpenDocumentationAfterSave: bankAccountInfoList.isEmpty() && customer.customerRegisterStatus.documentStatusIsPending()]
            extraData << [providerName: customer.name]
        }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), bankAccountInfoList.size(), extraData)
    }

    def save(params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiBankAccountInfoParser.parseRequestParams(customer, params)

        BaseBankAccount bankAccountInfo = bankAccountInfoUpdateRequestService.save(customer.id, fields)

        if (bankAccountInfo.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(bankAccountInfo)
        }

        Map responseMap = ApiBankAccountInfoParser.buildResponseItem(bankAccountInfo, [buildCriticalAction: true])

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    def delete(params) {
        try {
            Customer customer = getProviderInstance(params)
            def deleteResponse = bankAccountInfoService.deleteByCustomer(Long.valueOf(params.id), customer.id)

            Map responseMap = apiResponseBuilderService.buildDeleted(params.id)

            if (deleteResponse instanceof CriticalAction) {
                responseMap.awaitingCriticalActionAuthorization = true
                responseMap.criticalAction = ApiCriticalActionParser.buildResponseItem(deleteResponse)
            }

            return responseMap
        } catch (BankAccountInfoNotFoundException e) {
            return apiResponseBuilderService.buildNotFoundItem()
        }
    }

    def updateMainAccount(params) {
        Bank bank = Bank.ignoreAsaasBank([code: params.bank]).get()

        if (!bank) {
            return apiResponseBuilderService.buildErrorFrom("invalid_action", "No momento não possuímos suporte ao banco informado.")
        }

        Customer customer = getProviderInstance(params)

        Map fields = ApiBankAccountInfoParser.parseRequestParams(customer, params)

        fields.bankAccountInfoId = BankAccountInfo.findMainAccount(customer.id)?.id
        fields.bank = bank.id
        fields.mainAccount = true

        BaseBankAccount bankAccountInfo = bankAccountInfoUpdateRequestService.save(customer.id, fields)
        if (bankAccountInfo.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(bankAccountInfo)
        }

        Map responseMap = ApiBankAccountInfoParser.buildResponseItem(bankAccountInfo)

        return apiResponseBuilderService.buildSuccess(responseMap)
    }
}
