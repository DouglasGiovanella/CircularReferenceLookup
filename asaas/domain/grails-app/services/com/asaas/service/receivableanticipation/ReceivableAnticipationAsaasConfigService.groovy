package com.asaas.service.receivableanticipation

import com.asaas.domain.receivableanticipation.ReceivableAnticipationAsaasConfig
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationAsaasConfigService {

    public ReceivableAnticipationAsaasConfig update(BigDecimal fidcPdd, BigDecimal fidcMaxCompromisedLimitPerCustomer, BigDecimal fidcMaxCompromisedLimitPerCustomerAccountCpfCnpj, BigDecimal oceanMaxCompromisedLimit) {
        ReceivableAnticipationAsaasConfig config = ReceivableAnticipationAsaasConfig.getInstance()
        config.fidcPdd = fidcPdd
        config.fidcMaxCompromisedLimitPerCustomer = fidcMaxCompromisedLimitPerCustomer
        config.fidcMaxCompromisedLimitPerCustomerAccountCpfCnpj = fidcMaxCompromisedLimitPerCustomerAccountCpfCnpj
        config.save(failOnError: false)

        return config
    }
}
