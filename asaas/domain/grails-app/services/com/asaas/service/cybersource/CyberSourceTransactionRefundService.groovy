package com.asaas.service.cybersource

import com.asaas.creditcard.CyberSourceTransactionRefundStatus
import com.asaas.domain.creditcard.CyberSourceTransactionRefund
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CyberSourceTransactionRefundService {

    def cyberSourceCreditCardService

    public CyberSourceTransactionRefund save(Customer customer, CustomerAccount customerAccount, Map authorizationMap) {
        CyberSourceTransactionRefund transactionRefund = new CyberSourceTransactionRefund()
        transactionRefund.customer = customer
        transactionRefund.customerAccount = customerAccount
        transactionRefund.requestKey = authorizationMap.requestKey
        transactionRefund.amountInCents = authorizationMap.amountInCents
        transactionRefund.transactionReference = authorizationMap.transactionReference
        transactionRefund.transactionIdentifier = authorizationMap.transactionIdentifier
        transactionRefund.status = CyberSourceTransactionRefundStatus.PENDING
        transactionRefund.transactionDate = new Date()
        transactionRefund.save(failOnError: true)

        return transactionRefund
    }

    public void processPendingCyberSourceRefunds() {
        final Integer minutesAfterAuthorizationToProcessRefund = 10
        List<Long> cyberSourceTransactionRefundIdList = CyberSourceTransactionRefund.query([column: "id", status: CyberSourceTransactionRefundStatus.PENDING, "transactionDate[le]": CustomDateUtils.sumMinutes(new Date(), (-1 * minutesAfterAuthorizationToProcessRefund))]).list(max: 300)

        for (Long cyberSourceTransactionRefundId in cyberSourceTransactionRefundIdList) {
            try {
                Utils.withNewTransactionAndRollbackOnError({
                    CyberSourceTransactionRefund transactionRefund = CyberSourceTransactionRefund.get(cyberSourceTransactionRefundId)

                    Map resultMap = cyberSourceCreditCardService.refund(transactionRefund.customer, transactionRefund.customerAccount, transactionRefund.requestKey, transactionRefund.amountInCents, transactionRefund.transactionReference, transactionRefund.transactionIdentifier, null)
                    if (resultMap.success) {
                        transactionRefund.status = CyberSourceTransactionRefundStatus.PROCESSED
                    } else {
                        transactionRefund.status = CyberSourceTransactionRefundStatus.ERROR
                        AsaasLogger.error("Erro ao processar o estorno da transação na CyberSource Id: [${cyberSourceTransactionRefundId}]")
                    }

                    transactionRefund.save(flush: true, failOnError: true)
                }, [onError: { Exception e -> throw e }])
            } catch (Exception e) {
                CyberSourceTransactionRefund transactionRefund = CyberSourceTransactionRefund.get(cyberSourceTransactionRefundId)
                transactionRefund.status = CyberSourceTransactionRefundStatus.ERROR
                transactionRefund.save(flush: true, failOnError: true)
                AsaasLogger.error("Erro ao processar cyberSourceTransactionRefund Id: [${cyberSourceTransactionRefundId}]", e)
            }
        }
    }
}
