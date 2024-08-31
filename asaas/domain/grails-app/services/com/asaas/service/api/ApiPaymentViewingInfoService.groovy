package com.asaas.service.api

import com.asaas.api.paymentViewingInfo.parser.ApiPaymentViewingInfoParser
import com.asaas.cache.paymentviewinginfo.PaymentViewingInfoCacheVO
import com.asaas.domain.payment.Payment
import com.asaas.service.cache.paymentviewinginfo.PaymentViewingInfoCacheService
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class ApiPaymentViewingInfoService extends ApiBaseService {

    PaymentViewingInfoCacheService paymentViewingInfoCacheService

    public Map find(Map params) {
        Long paymentId = Payment.validateOwnerAndRetrieveId(params.id.toString(), getProvider(params))
        PaymentViewingInfoCacheVO paymentViewingInfoCacheVO = paymentViewingInfoCacheService.getPaymentViewingInfoData(paymentId)
        return ApiPaymentViewingInfoParser.buildResponse(paymentViewingInfoCacheVO)
    }
}
