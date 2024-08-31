package com.asaas.service.cache.paymentstatistic

import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class PaymentStatisticCacheService {

    def paymentStatisticService

    @Cacheable(value = "PaymentStatistic:buildPaymentStatisticsMap", key = "#root.target.generateCacheKey(#params)")
    public Map buildPaymentStatisticsMap(Map params) {
        return paymentStatisticService.buildPaymentStatisticsMap(params)
    }

    @Cacheable(value = "PaymentStatistic:buildReceiptCalendarMap", key = "#root.target.generateCacheKey(#params)")
    public List<Map> buildReceiptCalendarMap(Map params) {
        return paymentStatisticService.buildReceiptCalendarMap(params)
    }

    public String generateCacheKey(Map params) {
        return "CUSTOMER:${params.providerId}:PARAMS:${params.toString().encodeAsMD5()}"
    }
}
