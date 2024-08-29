package com.asaas.service.asaascardbillpayment

import com.asaas.asaascardbillpayment.AsaasCardBillPaymentMethod
import com.asaas.asaascardbillpayment.AsaasCardBillPaymentRequestStatus
import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPaymentRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class AsaasCardBillPaymentRequestService {

    def asaasCardBillPaymentService
    def customerAccountService
    def grailsApplication
    def paymentService
    def pixQrCodeService

    public AsaasCardBillPaymentRequest savePaymentRequest(AsaasCard asaasCard, BigDecimal value, AsaasCardBillPaymentMethod method) {
        validateRequestValue(value)
        deletePendingRequestIfNecessary(asaasCard)
        Payment payment = createPayment(asaasCard.customer, method, value)

        return save(payment, asaasCard)
    }

    public Map getPaymentMethodInfo(AsaasCardBillPaymentRequest asaasCardBillPaymentRequest) {
        Map paymentMethodInfo = [paymentRequestId: asaasCardBillPaymentRequest.id]

        switch (asaasCardBillPaymentRequest.payment.billingType) {
            case BillingType.PIX:
                return paymentMethodInfo += pixQrCodeService.createQrCodeForPayment(asaasCardBillPaymentRequest.payment)
            default:
                throw new BusinessException("Forma de pagamento não suportada")
        }
    }

    public void processReceivedPayments() {
        List<Long> paymentRequestIdList = AsaasCardBillPaymentRequest.query([column: "id", paymentStatus: PaymentStatus.RECEIVED, status: AsaasCardBillPaymentRequestStatus.PENDING]).list(max:250)

        Utils.forEachWithFlushSession(paymentRequestIdList, 50, { Long paymentRequestId ->
            Boolean errorProcessingReceivedPayment = false

            Utils.withNewTransactionAndRollbackOnError( {
                AsaasCardBillPaymentRequest asaasCardBillPaymentRequest = AsaasCardBillPaymentRequest.get(paymentRequestId)
                processPayment(asaasCardBillPaymentRequest)
            },
                [
                    logErrorMessage: "AsaasCardBillPaymentRequestService.processReceivedPayments >> Erro ao processar requisição de pagamento de fatura [id: ${paymentRequestId}]",
                    onError: { errorProcessingReceivedPayment = true }
                ]
            )

            if (errorProcessingReceivedPayment) {
                Utils.withNewTransactionAndRollbackOnError( {
                    updateStatus(AsaasCardBillPaymentRequest.get(paymentRequestId), AsaasCardBillPaymentRequestStatus.ERROR)
                }, [logErrorMessage: "AsaasCardBillPaymentRequestService.processReceivedPayments >> Erro ao definir status da requisição de pagamento de fatura [id: ${paymentRequestId}]"])
            }
        })
    }

    public Customer getAsaasCreditCardBillPaymentProvider() {
        return Customer.read(Utils.toLong(grailsApplication.config.asaas.asaasCreditCardBillPayment.customer.id))
    }

    private void validateRequestValue(BigDecimal value) {
        BigDecimal minimumPaymentValue = Payment.getMinimumBankSlipAndPixValue(getAsaasCreditCardBillPaymentProvider())
        if (value < minimumPaymentValue) throw new BusinessException("Valor mínimo para forma de pagamento é de ${FormUtils.formatCurrencyWithMonetarySymbol(minimumPaymentValue)}")
    }

    private void deletePendingRequestIfNecessary(AsaasCard asaasCard) {
        AsaasCardBillPaymentRequest asaasCardBillPaymentRequest = AsaasCardBillPaymentRequest.query([status: AsaasCardBillPaymentRequestStatus.PENDING, asaasCard: asaasCard]).get()
        if (!asaasCardBillPaymentRequest) return

        if (asaasCardBillPaymentRequest.payment.status.isReceived()) return

        paymentService.delete(asaasCardBillPaymentRequest.payment, false)
        asaasCardBillPaymentRequest.deleted = true
        asaasCardBillPaymentRequest.save(failOnError: true)
    }

    private Payment createPayment(Customer customer, AsaasCardBillPaymentMethod method, BigDecimal value) {
        Map paymentParams = [:]

        paymentParams.customerAccount = findOrCreateCustomerAccount(customer)
        paymentParams.customerAccountId = paymentParams.customerAccount.id
        paymentParams.billingType = parseBillingType(method)
        paymentParams.chargeType = ChargeType.DETACHED
        paymentParams.value = value
        paymentParams.description = "Pagamento de fatura do Cartão Asaas"
        paymentParams.dueDate = CustomDateUtils.setTimeToEndOfDay(CustomDateUtils.sumDays(new Date(), 1))

        Payment payment = paymentService.save(paymentParams, true, false)
        if (payment.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(payment))

        return payment
    }

    private CustomerAccount findOrCreateCustomerAccount(Customer customer) {
        return customerAccountService.saveOrUpdateAsaasCustomerAccountFromProvider(getAsaasCreditCardBillPaymentProvider(), customer)
    }

    private BillingType parseBillingType(AsaasCardBillPaymentMethod method) {
        switch (method) {
            case AsaasCardBillPaymentMethod.PIX:
                return BillingType.PIX
            default:
                throw new NotImplementedException("Forma de pagamento não implementada.")
        }
    }

    private AsaasCardBillPaymentRequest save(Payment payment, AsaasCard asaasCard) {
        AsaasCardBillPaymentRequest asaasCardBillPaymentRequest = new AsaasCardBillPaymentRequest()
        asaasCardBillPaymentRequest.payment = payment
        asaasCardBillPaymentRequest.asaasCard = asaasCard
        asaasCardBillPaymentRequest.status = AsaasCardBillPaymentRequestStatus.PENDING
        asaasCardBillPaymentRequest.save(failOnError: true)

        return asaasCardBillPaymentRequest
    }

    private void processPayment(AsaasCardBillPaymentRequest asaasCardBillPaymentRequest) {
        asaasCardBillPaymentService.savePayment(asaasCardBillPaymentRequest.asaasCard, asaasCardBillPaymentRequest.payment.value, parsePaymentMethod(asaasCardBillPaymentRequest.payment.billingType), asaasCardBillPaymentRequest.payment)
        updateStatus(asaasCardBillPaymentRequest, AsaasCardBillPaymentRequestStatus.PROCESSED)
    }

    private void updateStatus(AsaasCardBillPaymentRequest asaasCardBillPaymentRequest, AsaasCardBillPaymentRequestStatus status) {
        asaasCardBillPaymentRequest.status = status
        asaasCardBillPaymentRequest.save(failOnError: true)
    }

    private AsaasCardBillPaymentMethod parsePaymentMethod(BillingType billingType) {
        switch (billingType) {
            case BillingType.PIX:
                return AsaasCardBillPaymentMethod.PIX
            default:
                throw new NotImplementedException("Forma de pagamento não implementada.")
        }
    }
}
