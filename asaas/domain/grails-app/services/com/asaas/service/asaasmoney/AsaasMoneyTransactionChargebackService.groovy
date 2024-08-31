package com.asaas.service.asaasmoney

import com.asaas.asaasmoney.AsaasMoneyCashbackStatus
import com.asaas.asaasmoney.AsaasMoneyTransactionChargebackStatus
import com.asaas.domain.asaasmoney.AsaasMoneyCashback
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionChargeback
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.chargeback.Chargeback
import grails.transaction.Transactional

@Transactional
class AsaasMoneyTransactionChargebackService {

    def customerStatusService
    def financialTransactionService

    public void saveIfNecessary(Chargeback chargeback) {
        Map queryFilter
        if (chargeback.payment) {
            queryFilter = [backingPayment: chargeback.payment]
        } else {
            queryFilter = [backingInstallment: chargeback.installment]
        }

        AsaasMoneyTransactionInfo asaasMoneyTransactionInfo = AsaasMoneyTransactionInfo.query(queryFilter).get()
        if (!asaasMoneyTransactionInfo) return

        AsaasMoneyTransactionChargeback asaasMoneyTransactionChargeback = new AsaasMoneyTransactionChargeback()
        asaasMoneyTransactionChargeback.payerCustomer = asaasMoneyTransactionInfo.payerCustomer
        asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo = asaasMoneyTransactionInfo
        asaasMoneyTransactionChargeback.chargeback = chargeback
        asaasMoneyTransactionChargeback.value = calculateChargebackBlockedValue(asaasMoneyTransactionInfo)
        asaasMoneyTransactionChargeback.status = AsaasMoneyTransactionChargebackStatus.DEBITED
        asaasMoneyTransactionChargeback.save(failOnError: true)

        financialTransactionService.saveAsaasMoneyTransactionChargeback(asaasMoneyTransactionChargeback)

        customerStatusService.block(asaasMoneyTransactionChargeback.payerCustomer.id, false, "Cliente com saldo bloqueado devido a chargeback no pagamento Asaas Money")
    }

    public void reverseIfNecessary(Chargeback chargeback) {
        AsaasMoneyTransactionChargeback asaasMoneyTransactionChargeback = AsaasMoneyTransactionChargeback.query([chargeback: chargeback]).get()
        if (!asaasMoneyTransactionChargeback) return

        asaasMoneyTransactionChargeback.status = AsaasMoneyTransactionChargebackStatus.REFUNDED
        asaasMoneyTransactionChargeback.save(failOnError: true)

        financialTransactionService.reverseAsaasMoneyTransactionChargeback(asaasMoneyTransactionChargeback)

        Boolean hasAnyAsaasMoneyChargebackDebited = AsaasMoneyTransactionChargeback.debited([exists: true, payerCustomer: asaasMoneyTransactionChargeback.payerCustomer]).get().asBoolean()
        if (hasAnyAsaasMoneyChargebackDebited) return

        customerStatusService.unblock(asaasMoneyTransactionChargeback.payerCustomer.id)
    }

    private BigDecimal calculateChargebackBlockedValue(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        BigDecimal chargebackBlockedValue = asaasMoneyTransactionInfo.getBackingValue()

        BigDecimal cashbackValue = AsaasMoneyCashback.query([column: "value", asaasMoneyTransactionInfo: asaasMoneyTransactionInfo, status: AsaasMoneyCashbackStatus.CREDITED]).get()
        if (cashbackValue) chargebackBlockedValue += cashbackValue

        return chargebackBlockedValue
    }
}
