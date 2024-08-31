package com.asaas.service.api

import com.asaas.api.ApiBankParser
import com.asaas.domain.bank.Bank

import grails.transaction.Transactional

@Transactional
class ApiBankService extends ApiBaseService {

    def apiResponseBuilderService

    public Map list(Map params) {
        def bankList = Bank.ignoreAsaasBank([:]).list()

        List<Map> banks = bankList.collect { ApiBankParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(banks, getLimit(params), getOffset(params), bankList.size())
    }
}
