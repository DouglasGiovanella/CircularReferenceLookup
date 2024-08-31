package com.asaas.service.cache.instantpaymentaccount

import com.asaas.pix.adapter.instantpaymentaccount.PixInstantPaymentAccountBalanceAdapter

import grails.plugin.cache.CachePut
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class PixInstantPaymentAccountCacheService {

    @Cacheable(value = "PixInstantPaymentAccountBalanceAdapter:getInstance", key = "'instance'")
    public PixInstantPaymentAccountBalanceAdapter getBalance() {
        return null
    }

    @CachePut(value = "PixInstantPaymentAccountBalanceAdapter:getInstance", key = "'instance'")
    public PixInstantPaymentAccountBalanceAdapter saveBalance(PixInstantPaymentAccountBalanceAdapter balanceAdapter) {
        return balanceAdapter
    }
}
