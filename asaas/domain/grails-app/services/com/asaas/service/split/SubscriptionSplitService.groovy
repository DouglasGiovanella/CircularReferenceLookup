package com.asaas.service.split

import com.asaas.domain.split.SubscriptionSplit
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.wallet.Wallet
import com.asaas.exception.BusinessException
import com.asaas.payment.PaymentFeeVO
import com.asaas.paymentsplit.PaymentSplitCalculator
import com.asaas.paymentsplit.repository.SubscriptionSplitRepository
import com.asaas.service.payment.PaymentFeeService
import com.asaas.split.SplitVO
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils
import com.asaas.wallet.WalletRepository
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class SubscriptionSplitService {

    PaymentFeeService paymentFeeService
    PaymentSplitService paymentSplitService

    public void saveSplitInfo(Subscription subscription, Map params) {
        if (!params.containsKey("splitInfo")) return

        List<Map> splitInfoMapList = params.splitInfo as List<Map>

        List<SubscriptionSplit> subscriptionSplitList = SubscriptionSplitRepository.query([subscriptionId: subscription.id]).list() as List<SubscriptionSplit>

        List<SplitVO> subscriptionSplitVoList = subscriptionSplitList.collect({ new SplitVO(it) })
        List<SplitVO> splitVoList = []
        if (splitInfoMapList) splitVoList = splitInfoMapList.collect( { new SplitVO(it) } )

        if (!paymentSplitService.splitInfoHasChanged(subscriptionSplitVoList, splitVoList)) return

        subscriptionSplitList.each {
            it.deleted = true
            it.save(failOnError: true)
        }

        if (!splitInfoMapList) return

        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(subscription.value, subscription.billingType, subscription.provider, null)
        BigDecimal subscriptionNetValue = paymentFeeService.calculateNetValue(paymentFeeVO)

        for (Map splitInfoMap in splitInfoMapList) {
            paymentSplitService.validateSplit(splitInfoMap)

            SubscriptionSplit subscriptionSplit = new SubscriptionSplit()
            subscriptionSplit.subscription = subscription
            subscriptionSplit.externalReference = splitInfoMap.externalReference

            Wallet wallet = WalletRepository.query([publicId: splitInfoMap.walletId]).readOnly().get()
            subscriptionSplit.wallet = wallet

            if (!subscriptionSplit.wallet) throw new BusinessException("Wallet [${splitInfoMap.walletId}] inexistente.")

            BigDecimal fixedValue = Utils.toBigDecimal(splitInfoMap.fixedValue)
            if (fixedValue) subscriptionSplit.fixedValue = BigDecimalUtils.roundDown(fixedValue)

            BigDecimal percentualValue = Utils.toBigDecimal(splitInfoMap.percentualValue)
            if (percentualValue) subscriptionSplit.percentualValue = BigDecimalUtils.roundDown(percentualValue, 4)

            BigDecimal totalValue = PaymentSplitCalculator.calculateTotalValue(subscriptionNetValue, subscriptionSplit.percentualValue, subscriptionSplit.fixedValue)
            if (BigDecimalUtils.roundDown(totalValue) <= 0) throw new BusinessException("Informe um valor de split superior a R\$ 0,00.")

            subscriptionSplit.save(flush: true, failOnError: true)
        }

        validateSplitTotalValue(subscription)
    }

    public void validateSplitTotalValue(Subscription subscription) {
        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(subscription.value, subscription.billingType, subscription.provider, null)
        BigDecimal subscriptionNetValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        BigDecimal splitedValue = 0

        List<SubscriptionSplit> subscriptionSplitList = SubscriptionSplitRepository.query([subscriptionId: subscription.id]).list() as List<SubscriptionSplit>
        for (SubscriptionSplit subscriptionSplit in subscriptionSplitList) {
            splitedValue += PaymentSplitCalculator.calculateTotalValue(subscriptionNetValue, subscriptionSplit.percentualValue, subscriptionSplit.fixedValue)
        }

        if (splitedValue > subscriptionNetValue) throw new BusinessException("Valor total do split [${splitedValue}] excede o valor líquido da assinatura [${subscriptionNetValue}].")
    }
}
