package com.asaas.service.debitcard

import com.asaas.debitcard.DebitCardTransactionEvent
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.debitcard.DebitCardTransactionLog
import com.asaas.domain.payment.Payment
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class DebitCardTransactionLogService {

	public void save(Payment payment, Customer customer, CustomerAccount customerAccount, DebitCardTransactionEvent event, Boolean success, Map gatewayInfo) {
		Map properties = [
		    providerId: customer.id,
		    customerAccountId: customerAccount.id,
		    subscriptionId: payment?.subscription?.id,
		    paymentId: payment?.id,
		    event: parseEvent(event, success),
		    message: gatewayInfo.message,
            transactionIdentifier: gatewayInfo.transactionIdentifier
        ]

        Utils.withNewTransactionAndRollbackOnError({
            DebitCardTransactionLog debitCardTransaction = new DebitCardTransactionLog(properties)
            debitCardTransaction.save(failOnError: true)
        })
	}

    private DebitCardTransactionEvent parseEvent(DebitCardTransactionEvent event, Boolean success) {
        if (event == DebitCardTransactionEvent.AUTHORIZATION) {
            return success ? DebitCardTransactionEvent.AUTHORIZATION_SUCCESS : DebitCardTransactionEvent.AUTHORIZATION_FAIL
        }

        if (event == DebitCardTransactionEvent.AUTHORIZATION_3D) {
            return success ? DebitCardTransactionEvent.AUTHORIZATION_3D_SUCCESS : DebitCardTransactionEvent.AUTHORIZATION_3D_FAIL
        }

        if (event == DebitCardTransactionEvent.CAPTURE) {
            return success ? DebitCardTransactionEvent.CAPTURE_SUCCESS : DebitCardTransactionEvent.CAPTURE_FAIL
        }

        return event
    }
}
