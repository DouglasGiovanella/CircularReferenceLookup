package com.asaas.service.pix

import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransaction
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.vo.transaction.PixDebitVO

import grails.transaction.Transactional

@Transactional
class PixTransactionFeeService {

    public Boolean shouldChargeCreditFee(Customer customer, PixTransactionOriginType originType, String payerCpfCnpj) {
        if (originType && !originType.isDynamicQrCode()) {
            if (originType.isTransfer() && customer.cpfCnpj) {
                Boolean isPixTransactionBetweenSamePerson = customer.cpfCnpj == payerCpfCnpj
                if (isPixTransactionBetweenSamePerson) return false
            }

            if (!PixTransaction.shouldChargeFeeForDetachedCredits(customer)) return false
        }

        return true
    }

    public Boolean shouldChargeDebitFee(Customer customer, PixDebitVO pixDebitVO) {
        if (!pixDebitVO.type.isDebit()) return false
        if (pixDebitVO.isScheduledTransaction) return false
        if (pixDebitVO.asynchronousCheckout) return false

        if (pixDebitVO.originType.isQrCode() && pixDebitVO.cashValueFinality) {
            return CustomerCheckoutFee.shouldChargePixDebitWithCashValueFee(customer)
        } else {
            return CustomerCheckoutFee.shouldChargePixDebitFee(customer, pixDebitVO.originType, pixDebitVO.toSameOwnership(), pixDebitVO.value)
        }
    }
}
