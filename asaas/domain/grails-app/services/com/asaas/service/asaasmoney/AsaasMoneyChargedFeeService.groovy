package com.asaas.service.asaasmoney

import com.asaas.asaasmoney.AsaasMoneyChargedFeeStatus
import com.asaas.asaasmoney.AsaasMoneyChargedFeeType
import com.asaas.domain.asaasmoney.AsaasMoneyChargedFee
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class AsaasMoneyChargedFeeService {

    def financialTransactionService

    public AsaasMoneyChargedFee save(Customer payerCustomer, AsaasMoneyTransactionInfo asaasMoneyTransactionInfo, BigDecimal feeValue, AsaasMoneyChargedFeeType type) {
        AsaasMoneyChargedFee asaasMoneyChargedFee = new AsaasMoneyChargedFee()
        asaasMoneyChargedFee.payerCustomer = payerCustomer
        asaasMoneyChargedFee.asaasMoneyTransactionInfo = asaasMoneyTransactionInfo
        asaasMoneyChargedFee.value = feeValue
        asaasMoneyChargedFee.status = AsaasMoneyChargedFeeStatus.PENDING
        asaasMoneyChargedFee.type = type
        asaasMoneyChargedFee.save(failOnError: true)

        return asaasMoneyChargedFee
    }

    public AsaasMoneyChargedFee executeDebit(AsaasMoneyChargedFee asaasMoneyChargedFee) {
        asaasMoneyChargedFee.status = AsaasMoneyChargedFeeStatus.DEBITED
        asaasMoneyChargedFee.save(failOnError: true)

        String feeDescription = Utils.getMessageProperty("AsaasMoneyChargedFeeType.${asaasMoneyChargedFee.type.toString()}")

        AsaasMoneyTransactionInfo asaasMoneyTransactionInfo = asaasMoneyChargedFee.asaasMoneyTransactionInfo
        String financialDescription
        if (asaasMoneyTransactionInfo.destinationBill) {
            String billDescription = asaasMoneyTransactionInfo.destinationBill.description ?: ""
            financialDescription = "${feeDescription} - Pagamento de conta ${billDescription}"
        } else if (asaasMoneyTransactionInfo.destinationPixTransaction) {
            financialDescription = "${feeDescription} - Pagamento via Pix"
        } else {
            throw new NotImplementedException("Tipo de taxa não implementado")
        }

        financialTransactionService.saveAsaasMoneyChargedFee(asaasMoneyChargedFee, financialDescription)

        return asaasMoneyChargedFee
    }

    public void refund(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo, AsaasMoneyChargedFeeType type) {
        AsaasMoneyChargedFee asaasMoneyChargedFee = AsaasMoneyChargedFee.query([asaasMoneyTransactionInfo: asaasMoneyTransactionInfo, type: type]).get()
        if (!asaasMoneyChargedFee) return

        if (asaasMoneyChargedFee.status.isPending()) {
            asaasMoneyChargedFee.status = AsaasMoneyChargedFeeStatus.CANCELLED
            asaasMoneyChargedFee.save(failOnError: true)
            return
        }

        asaasMoneyChargedFee.status = AsaasMoneyChargedFeeStatus.REFUNDED
        asaasMoneyChargedFee.save(failOnError: true)

        String feeDescription = Utils.getMessageProperty("AsaasMoneyChargedFeeType.${type.toString()}").toLowerCase()

        String financialDescription
        if (asaasMoneyTransactionInfo.destinationBill) {
            String billDescription = asaasMoneyTransactionInfo.destinationBill.description ?: ""
            financialDescription = "Estorno da ${feeDescription} - Pagamento de conta ${billDescription}"
        } else if (asaasMoneyTransactionInfo.destinationPixTransaction) {
            financialDescription = "Estorno da ${feeDescription} - Pagamento via Pix"
        } else {
            throw new NotImplementedException("Tipo de estorno não implementado")
        }

        financialTransactionService.reverseAsaasMoneyChargedFee(asaasMoneyChargedFee, financialDescription)
    }
}
