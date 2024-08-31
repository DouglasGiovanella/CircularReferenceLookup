package com.asaas.service.cache.paymentviewinginfo

import com.asaas.cache.paymentviewinginfo.PaymentViewingInfoCacheVO
import com.asaas.domain.notification.NotificationRequestViewingInfo
import com.asaas.domain.payment.PaymentViewingInfo
import grails.plugin.cache.Cacheable

class PaymentViewingInfoCacheService {

    private static final String GET_PAYMENT_VIEWING_INFO_DATA_CACHE_NAME = "PVI:gPVID"

    def grailsCacheManager

    @Cacheable(value = PaymentViewingInfoCacheService.GET_PAYMENT_VIEWING_INFO_DATA_CACHE_NAME)
    public PaymentViewingInfoCacheVO getPaymentViewingInfoData(Long paymentId) {
        Map paymentViewingInfoMap = PaymentViewingInfo.query([paymentId: paymentId, disableSort: true, columnList: ["invoiceViewed", "invoiceViewedDate", "boletoViewed", "boletoViewedDate"]]).get()

        PaymentViewingInfoCacheVO paymentViewingInfoCacheVO = new PaymentViewingInfoCacheVO()

        if (!paymentViewingInfoMap) return paymentViewingInfoCacheVO

        paymentViewingInfoCacheVO.invoiceViewed = paymentViewingInfoMap.invoiceViewed
        paymentViewingInfoCacheVO.invoiceViewedDate = paymentViewingInfoMap.invoiceViewedDate
        paymentViewingInfoCacheVO.boletoViewed = paymentViewingInfoMap.boletoViewed
        paymentViewingInfoCacheVO.boletoViewedDate = paymentViewingInfoMap.boletoViewedDate

        paymentViewingInfoCacheVO.notificationRequestInvoiceViewedList = NotificationRequestViewingInfo.query([
            column: "notificationRequest.id",
            paymentId: paymentId,
            invoiceViewed: true,
            disableSort: true
        ]).list() as List<Long>

        return paymentViewingInfoCacheVO
    }

    public void evictGetPaymentViewingInfoData(Long paymentId) {
        grailsCacheManager.getCache(PaymentViewingInfoCacheService.GET_PAYMENT_VIEWING_INFO_DATA_CACHE_NAME).evict(paymentId)
    }
}
