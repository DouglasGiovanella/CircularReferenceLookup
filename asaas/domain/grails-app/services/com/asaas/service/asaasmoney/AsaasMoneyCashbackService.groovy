package com.asaas.service.asaasmoney

import com.asaas.asaasmoney.AsaasMoneyCashbackStatus
import com.asaas.domain.asaasmoney.AsaasMoneyCashback
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.customer.Customer
import grails.transaction.Transactional

@Transactional
class AsaasMoneyCashbackService {

    def financialTransactionService

    public AsaasMoneyCashback save(Customer payerCustomer, AsaasMoneyTransactionInfo asaasMoneyTransactionInfo, BigDecimal value) {
        Boolean hasCashbackForCustomer = AsaasMoneyCashback.query([exists: true,  payerCustomer: payerCustomer, "status[ne]": AsaasMoneyCashbackStatus.REFUNDED]).get().asBoolean()
        if (hasCashbackForCustomer) throw new RuntimeException("Este pagador já recebeu cashback")

        AsaasMoneyCashback asaasMoneyCashback = new AsaasMoneyCashback()
        asaasMoneyCashback.payerCustomer = payerCustomer
        asaasMoneyCashback.asaasMoneyTransactionInfo = asaasMoneyTransactionInfo
        asaasMoneyCashback.value = value
        asaasMoneyCashback.status = AsaasMoneyCashbackStatus.PENDING
        asaasMoneyCashback.save(failOnError: true)

        return asaasMoneyCashback
    }

    public AsaasMoneyCashback executeCredit(AsaasMoneyCashback asaasMoneyCashback) {
        asaasMoneyCashback.status = AsaasMoneyCashbackStatus.CREDITED
        asaasMoneyCashback.save(failOnError: true)

        financialTransactionService.saveAsaasMoneyCashback(asaasMoneyCashback)

        return asaasMoneyCashback
    }

    public void refund(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        AsaasMoneyCashback asaasMoneyCashback = AsaasMoneyCashback.query([asaasMoneyTransactionInfo: asaasMoneyTransactionInfo, status: AsaasMoneyCashbackStatus.CREDITED]).get()
        if (!asaasMoneyCashback) return

        asaasMoneyCashback.status = AsaasMoneyCashbackStatus.REFUNDED
        asaasMoneyCashback.save(failOnError: true)

        financialTransactionService.refundAsaasMoneyCashback(asaasMoneyCashback)
    }
}
