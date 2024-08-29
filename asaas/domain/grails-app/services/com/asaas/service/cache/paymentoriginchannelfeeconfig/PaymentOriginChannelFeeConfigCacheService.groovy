package com.asaas.service.cache.paymentoriginchannelfeeconfig

import com.asaas.cache.paymentoriginchannelfeeconfig.PaymentOriginChannelFeeConfigCacheVO
import com.asaas.domain.paymentgatewayfeeconfig.PaymentOriginChannelFeeConfig
import com.asaas.originrequesterinfo.OriginChannel
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable

class PaymentOriginChannelFeeConfigCacheService {

    private static final String GET_PAYMENT_ORIGIN_CHANNEL_FEE_CONFIG_CACHE_NAME = "POCFC:gPOCFC"

    def grailsCacheManager

    @Cacheable(value = PaymentOriginChannelFeeConfigCacheService.GET_PAYMENT_ORIGIN_CHANNEL_FEE_CONFIG_CACHE_NAME, key = "#originChannel")
    public PaymentOriginChannelFeeConfigCacheVO getPaymentOriginChannelFeeConfig(OriginChannel originChannel) {
        Map paymentOriginChannelFeeConfigInfoMap = PaymentOriginChannelFeeConfig.query([originChannel: originChannel, disableSort: true, columnList: ["bankSlipPercentageFee", "pixPercentageFee", "creditCardPercentageFee"]]).get()

        PaymentOriginChannelFeeConfigCacheVO paymentOriginChannelFeeConfigCacheVO = new PaymentOriginChannelFeeConfigCacheVO()
        if (!paymentOriginChannelFeeConfigInfoMap) return null

        paymentOriginChannelFeeConfigCacheVO.bankSlipPercentageFee = paymentOriginChannelFeeConfigInfoMap.bankSlipPercentageFee
        paymentOriginChannelFeeConfigCacheVO.pixPercentageFee = paymentOriginChannelFeeConfigInfoMap.pixPercentageFee
        paymentOriginChannelFeeConfigCacheVO.creditCardPercentageFee = paymentOriginChannelFeeConfigInfoMap.creditCardPercentageFee

        return paymentOriginChannelFeeConfigCacheVO
    }

    @CacheEvict(value = PaymentOriginChannelFeeConfigCacheService.GET_PAYMENT_ORIGIN_CHANNEL_FEE_CONFIG_CACHE_NAME)
    public void evictGetPaymentOriginChannelFeeConfig(OriginChannel originChannel) { }
}
