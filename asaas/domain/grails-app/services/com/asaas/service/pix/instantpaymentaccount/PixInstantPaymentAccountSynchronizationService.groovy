package com.asaas.service.pix.instantpaymentaccount

import com.asaas.log.AsaasLogger
import com.asaas.pix.PixInstantPaymentAccountBalanceSource
import com.asaas.pix.adapter.instantpaymentaccount.PixInstantPaymentAccountBalanceAdapter

import grails.transaction.Transactional

@Transactional
class PixInstantPaymentAccountSynchronizationService {

    def hermesInstantPaymentAccountManagerService
    def pixInstantPaymentAccountCacheService
    def pixInstantPaymentAccountService

    public void synchronizeAvailableBalance() {
        try {
            Boolean isCriticalPeriod = pixInstantPaymentAccountService.isCriticalPeriod()

            PixInstantPaymentAccountBalanceSource source = isCriticalPeriod
                ? PixInstantPaymentAccountBalanceSource.BACEN
                : PixInstantPaymentAccountBalanceSource.PARTNER

            PixInstantPaymentAccountBalanceAdapter balanceAdapter = hermesInstantPaymentAccountManagerService.getBalance(source)
            balanceAdapter.maximumPixTransactionCheckoutValue = pixInstantPaymentAccountService.calculateMaximumPixTransactionCheckoutValue(balanceAdapter.availableBalance, isCriticalPeriod)

            pixInstantPaymentAccountCacheService.saveBalance(balanceAdapter)
        } catch (Exception exception) {
            AsaasLogger.error("PixInstantPaymentAccountSynchronizationService.synchronizeAvailableBalance >> Não foi possível sincronizar o saldo da Conta PI", exception)
        }
    }
}
