package com.asaas.service.debitcard

import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoCardType
import com.asaas.cardtransactioncapturedrawinfo.CardTransactionCapturedRawInfoVO
import com.asaas.creditcard.CreditCard
import com.asaas.debitcard.CapturedDebitCardTransactionVo
import com.asaas.debitcard.DebitCard
import com.asaas.debitcard.DebitCardAcquirer
import com.asaas.debitcard.DebitCardBrand
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.debitcard.DebitCardTransactionInfo
import com.asaas.domain.payment.DebitCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.MoneyUtils
import grails.transaction.Transactional

@Transactional
class DebitCardService {

    def adyenDebitCardService
    def asaasSegmentioService
    def cardTransactionCapturedRawInfoService
    def grailsLinkGenerator
    def mobilePushNotificationService
    def paymentDebitCardService
    def paymentDiscountConfigService
    def paymentInterestConfigService

    public Map authorize(Payment payment, DebitCard debitCard) {
        if (!payment.canConfirm()) throw new Exception("Cobrança [${payment.id}] já confirmada.")

        if (!debitCard.validate()) throw new BusinessException(debitCard.invalidMessage)

        Map authorizationResultMap = adyenDebitCardService.authorize(payment, debitCard, calculateTransactionValue(payment))

        if (!authorizationResultMap.success) return authorizationResultMap

        DebitCardAuthorizationInfo debitCardAuthorization = new DebitCardAuthorizationInfo()
        debitCardAuthorization.payment = payment
        debitCardAuthorization.transactionIdentifier = authorizationResultMap.transactionIdentifier
        debitCardAuthorization.amountInCents = authorizationResultMap.amountInCents
        debitCardAuthorization.brand = debitCard.getBrand()
        debitCardAuthorization.lastDigits = debitCard.number[-4..-1]
        debitCardAuthorization.cardHash = CreditCard.buildNumberHash(debitCard.number)
        debitCardAuthorization.save(failOnError: true)

        authorizationResultMap.capturePaymentUrl = grailsLinkGenerator.link(controller: "debitCard", action: 'capture', id: payment.externalToken, absolute: true)

    	return authorizationResultMap
    }

    public Boolean capture(Payment payment, Map authorizedInfoMap, String customerIp) {
        if (payment.isPaid()) throw new Exception("Cobrança [${payment.id}] já confirmada.")

        try {
            Map authorization3dResultMap
            String transactionIdentifier

            if (authorizedInfoMap.md || authorizedInfoMap.paResponse) {
                authorization3dResultMap = adyenDebitCardService.authorize3d(payment, authorizedInfoMap.md, authorizedInfoMap.paResponse, customerIp)
                if (!authorization3dResultMap.success) return false

                transactionIdentifier = authorization3dResultMap.transactionIdentifier
            } else {
                transactionIdentifier = authorizedInfoMap.transactionIdentifier
            }

            if (!transactionIdentifier) return false

            DebitCardAuthorizationInfo debitCardAuthorization = DebitCardAuthorizationInfo.query([payment: payment, transactionIdentifier: transactionIdentifier]).get()

            if (!debitCardAuthorization) throw new Exception("Não foi encontrada uma autorização pendente para a cobrança [${payment.getInvoiceNumber()}]")

            paymentDiscountConfigService.applyPaymentDiscountIfNecessary(payment)
            paymentInterestConfigService.applyPaymentInterestIfNecessary(payment)

            if (payment.value != MoneyUtils.valueFromCents(debitCardAuthorization.amountInCents.toString())) throw new RuntimeException("Valor da transação foi alterado durante o processo.")

            Boolean captured = adyenDebitCardService.capturePreviouslyAuthorized(debitCardAuthorization, payment.value)

            if (captured) {
                CardTransactionCapturedRawInfoVO cardTransactionCapturedRawInfoVO = new CardTransactionCapturedRawInfoVO(payment, debitCardAuthorization)
                cardTransactionCapturedRawInfoService.saveWithNewTransaction(CardTransactionCapturedRawInfoCardType.DEBIT, cardTransactionCapturedRawInfoVO)
            }

            asaasSegmentioService.track(payment.provider.id, "Service :: Cartão de Débito :: ${captured ? 'Transação autorizada' : 'Transação negada'}", buildTransactionTrackingMap(payment, debitCardAuthorization.brand))

            if (!captured) {
                AsaasLogger.info("Failure while attempting to capture debit card payment >> ${payment.id}")
                payment.discard()
                return false
            }

            CapturedDebitCardTransactionVo capturedDebitCardTransactionVo = buildTransactionVo(payment, debitCardAuthorization.transactionIdentifier, debitCardAuthorization.brand)
            paymentDebitCardService.confirmDebitCardCapture(capturedDebitCardTransactionVo)
            mobilePushNotificationService.notifyPaymentConfirmed(payment)

            payment.save(failOnError: true)

            return true
        } catch (Exception exception) {
            AsaasLogger.warn("DebitCardService.capture >> Exception ao processar transação via cartão de débito. Payment [${payment.id}]", exception)
            throw e
        }
    }

    public void refund(Long id) {
        DebitCardTransactionInfo debitCardTransactionInfo = DebitCardTransactionInfo.query([paymentId: id]).get()

        Map refundResponse = refund(debitCardTransactionInfo.payment.provider, debitCardTransactionInfo.payment.customerAccount, debitCardTransactionInfo.transactionIdentifier)

        if (!refundResponse.success) throw new RuntimeException("Erro ao estornar cobrança [${id}]: Falha no gateway: ${refundResponse.message}.")

        AsaasLogger.info("Payment >> ${id} successfully refunded")
    }

    public Map refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier) {
        return adyenDebitCardService.refund(customer, customerAccount, transactionIdentifier)
    }

    private Map buildTransactionTrackingMap(Payment payment, DebitCardBrand brand) {
		return [
            paymentId: payment.id,
            valor: payment.value,
            bandeira: brand,
            tipo: payment.subscriptionPayments ? 'Assinatura' : 'Avulso'
        ]
	}

    private CapturedDebitCardTransactionVo buildTransactionVo(Payment payment, String transactionIdentifier, DebitCardBrand brand) {
        CapturedDebitCardTransactionVo capturedDebitCardTransactionVo = new CapturedDebitCardTransactionVo()
        capturedDebitCardTransactionVo.payment = payment
        capturedDebitCardTransactionVo.transactionIdentifier = transactionIdentifier
        capturedDebitCardTransactionVo.acquirer = DebitCardAcquirer.ADYEN
        capturedDebitCardTransactionVo.brand = brand

        return capturedDebitCardTransactionVo
    }

    private BigDecimal calculateTransactionValue(Payment payment) {
        Map paymentDiscountInfo = paymentDiscountConfigService.calculatePaymentDiscountInfo(payment)
        if (paymentDiscountInfo) return paymentDiscountInfo.value

        Map paymentInterestInfo = paymentInterestConfigService.calculatePaymentInterestInfo(payment)
        if (paymentInterestInfo) return paymentInterestInfo.value

        return payment.value
    }
}
