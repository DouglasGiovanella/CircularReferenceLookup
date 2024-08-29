package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCardTransactionEvent
import com.asaas.domain.creditcard.CreditCardTransactionLog
import com.asaas.domain.creditcard.CreditCardTransactionLogCde
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditCardTransactionLogService {

	public Long save(Customer customer, CustomerAccount customerAccount, Payment payment, CreditCardTransactionEvent originEvent, Boolean success, Map gatewayInfo) {
		CreditCardTransactionEvent event = buildEvent(originEvent, success)

		Map logMap = [:]

        if (payment?.id) {
            logMap.putAt("subscriptionId", payment.subscription?.id)
        }

		logMap.putAt("providerId", customer.id)
		logMap.putAt("customerAccountId", customerAccount?.id)
		logMap.putAt("paymentId", payment?.id)
		logMap.putAt("installmentId", payment?.installment?.id)
		logMap.putAt("event", event)
		logMap.putAt("value", gatewayInfo.amountInCents ? (gatewayInfo.amountInCents / 100) : null)
		logMap.putAt("mundiPaggOrderKey", gatewayInfo.orderKey)
		logMap.putAt("mundiPaggTransactionKey", gatewayInfo.transactionKey)
		logMap.putAt("transactionIdentifier", gatewayInfo.transactionIdentifier)
		logMap.putAt("message", gatewayInfo.message)
		logMap.putAt("responseJson", gatewayInfo.apiResponseJson)
		logMap.putAt("httpStatus", gatewayInfo.httpStatus)
		logMap.putAt("processed", false)
		logMap.putAt("creditCardBin", gatewayInfo.creditCardBin)
		logMap.putAt("acquirerReturnCode", gatewayInfo.acquirerReturnCode)
        logMap.putAt("gateway", gatewayInfo.gateway)
        logMap.putAt("billingInfoId", gatewayInfo.billingInfoId)
        logMap.putAt("softDescriptor", gatewayInfo.softDescriptor)
        logMap.putAt("fallback", gatewayInfo.fallback ?: false)

		return save(logMap)
	}

	public Long save(Map properties) {
        Long creditCardTransactionLogId

        Utils.withNewTransactionAndRollbackOnError ( {
            CreditCardTransactionLog creditCardTransactionLog = new CreditCardTransactionLog(properties)
            creditCardTransactionLog.responseJson = null
            creditCardTransactionLog.save(flush: true, failOnError: true)

            CreditCardTransactionLogCde creditCardTransactionLogCde = new CreditCardTransactionLogCde()
            creditCardTransactionLogCde.creditCardTransactionLogId = creditCardTransactionLog.id
            creditCardTransactionLogCde.responseJson = properties.responseJson
            creditCardTransactionLogCde.save(failOnError: true)

            creditCardTransactionLogId = creditCardTransactionLog.id
        }, [logErrorMessage: "CreditCardTransactionLogService.save >> Erro ao salvar o log"] )

        return creditCardTransactionLogId
	}

    private CreditCardTransactionEvent buildEvent(CreditCardTransactionEvent originEvent, Boolean success) {
        if (originEvent.isAuthorization()) return success ? CreditCardTransactionEvent.AUTHORIZATION_SUCCESS : CreditCardTransactionEvent.AUTHORIZATION_FAIL
        if (originEvent.isAuthorization3ds()) return success ? CreditCardTransactionEvent.AUTHORIZATION_3DS_SUCCESS : CreditCardTransactionEvent.AUTHORIZATION_3DS_FAIL
        if (originEvent.isCapture()) return success ? CreditCardTransactionEvent.CAPTURE_SUCCESS : CreditCardTransactionEvent.CAPTURE_FAIL

        return originEvent
    }
}
