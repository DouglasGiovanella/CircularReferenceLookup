package com.asaas.service.pix.instantpaymentaccount

import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.instantpaymentaccount.PixInstantPaymentAccountBalanceAdapter
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class PixInstantPaymentAccountService {

    def pixInstantPaymentAccountCacheService

    public Boolean hasEnoughBalanceForCheckout(PixTransaction pixTransaction) {
        if (!pixTransaction.type.isEquivalentToDebit()) throw new RuntimeException("O tipo da transação é inválido")
        if (pixTransaction.receivedWithAsaasQrCode) throw new RuntimeException("A validação de saldo não está habilitada para transações do Pix Bradesco")

        PixInstantPaymentAccountBalanceAdapter balanceAdapter = pixInstantPaymentAccountCacheService.getBalance()
        if (!balanceAdapter) return true

        if (!balanceAdapter.availableBalance) {
            AsaasLogger.info("PixInstantPaymentAccountService.hasEnoughBalanceForCheckout >> Não há saldo disponível para checkout [pixTransaction.id: ${pixTransaction.id}, balanceAdapter: [availableBalance: ${balanceAdapter.availableBalance}, maximumPixTransactionCheckoutValue: ${balanceAdapter.maximumPixTransactionCheckoutValue}, lastUpdateDate: ${balanceAdapter.lastUpdateDate}]]")
            return false
        }

        BigDecimal pixTransactionValue = BigDecimalUtils.abs(pixTransaction.value)
        if (pixTransactionValue > balanceAdapter.maximumPixTransactionCheckoutValue) {
            AsaasLogger.info("PixInstantPaymentAccountService.hasEnoughBalanceForCheckout >> O valor da transação excedeu o valor máximo para checkout [pixTransaction.id: ${pixTransaction.id}, pixTransactionValue: ${pixTransactionValue}, balanceAdapter: [availableBalance: ${balanceAdapter.availableBalance}, maximumPixTransactionCheckoutValue: ${balanceAdapter.maximumPixTransactionCheckoutValue}, lastUpdateDate: ${balanceAdapter.lastUpdateDate}]]")
            return false
        }

        return true
    }

    public Boolean isCriticalPeriod() {
        Date currentTime = new Date()
        Date criticalPeriodStart = CustomDateUtils.setTime(currentTime, 18, 15, 0)
        Date criticalPeriodEnd = CustomDateUtils.setTime(currentTime, 18, 45, 0)

        return currentTime >= criticalPeriodStart && currentTime < criticalPeriodEnd
    }

    public BigDecimal calculateMaximumPixTransactionCheckoutValue(BigDecimal availableBalance, Boolean isCriticalPeriod) {
        BigDecimal availablePercentage = getAvailableBalancePercentageForCheckout(availableBalance, isCriticalPeriod)
        return BigDecimalUtils.calculateValueFromPercentageWithRoundDown(availableBalance, availablePercentage)
    }

    private BigDecimal getAvailableBalancePercentageForCheckout(BigDecimal availableBalance, Boolean isCriticalPeriod) {
        if (!isCriticalPeriod) return 55

        if (availableBalance >= 8_000_000) return 40
        if (availableBalance >= 5_000_000) return 35
        if (availableBalance >= 2_000_000) return 30
        if (availableBalance >= 1_000_000) return 25

        return 20
    }
}
