package com.asaas.service.paymentoriginchannelfeeconfig

import com.asaas.billinginfo.BillingType
import com.asaas.cache.paymentoriginchannelfeeconfig.PaymentOriginChannelFeeConfigCacheVO
import com.asaas.originrequesterinfo.OriginChannel
import com.asaas.utils.BigDecimalUtils

import grails.transaction.Transactional

@Transactional
class PaymentOriginChannelFeeConfigService {

    def paymentOriginChannelFeeConfigCacheService

    public BigDecimal calculateFee(OriginChannel originChannel, BillingType billingType, BigDecimal value) {
        PaymentOriginChannelFeeConfigCacheVO paymentOriginChannelFeeConfigCacheVO = paymentOriginChannelFeeConfigCacheService.getPaymentOriginChannelFeeConfig(originChannel)
        if (!paymentOriginChannelFeeConfigCacheVO) return null

        if (!paymentOriginChannelFeeConfigCacheVO.bankSlipPercentageFee || !paymentOriginChannelFeeConfigCacheVO.creditCardPercentageFee || !paymentOriginChannelFeeConfigCacheVO.pixPercentageFee) throw new RuntimeException("Campos obrigatórios para calculo da taxa não foram informados. [bankSlipPercentageFee, creditCardPercentageFee e pixPercentageFee]")

        BigDecimal percentageFee
        if (billingType == BillingType.BOLETO) {
            percentageFee = paymentOriginChannelFeeConfigCacheVO.bankSlipPercentageFee
        } else if (billingType == BillingType.MUNDIPAGG_CIELO) {
            percentageFee = paymentOriginChannelFeeConfigCacheVO.creditCardPercentageFee
        } else if (billingType == BillingType.PIX) {
            percentageFee = paymentOriginChannelFeeConfigCacheVO.pixPercentageFee
        } else {
            throw new RuntimeException("Tipo de cobrança não suportado para calculo da taxa por canal de origem. [${billingType}]")
        }

        return BigDecimalUtils.calculateValueFromPercentageWithRoundDown(value, percentageFee)
    }
}
