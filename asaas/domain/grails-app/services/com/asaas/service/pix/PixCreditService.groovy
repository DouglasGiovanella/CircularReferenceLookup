package com.asaas.service.pix

import com.asaas.api.ApiMobileUtils
import com.asaas.checkout.CheckoutValidator
import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.pix.PixTransaction
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionType
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.pix.adapter.transaction.credit.CreditRefundAdapter
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class PixCreditService {

    def criticalActionService
    def receivedPaymentCreationService
    def pixTransactionService

    public PixTransaction save(CreditAdapter creditAdapter) {
        PixTransaction validatedTransaction = validateCredit(creditAdapter)
        if (validatedTransaction.hasErrors()) return validatedTransaction

        if (shouldCreatePayment(creditAdapter)) creditAdapter.payment = receivedPaymentCreationService.saveFromPixTransaction(creditAdapter)

        return pixTransactionService.saveCredit(creditAdapter)
    }

    public PixTransaction validateCredit(CreditAdapter creditInfo) {
        PixTransaction validatedTransaction = new PixTransaction()

        Boolean hasInvalidValue = (creditInfo.value <= 0)
        if (hasInvalidValue) return refuseCredit(creditInfo, PixTransactionRefusalReason.DENIED, "O valor informado é inválido.")

        if (creditInfo.externalIdentifier) {
            Boolean externalIdentifierAlreadyUsed = PixTransaction.credit([exists: true, externalIdentifier: creditInfo.externalIdentifier]).get().asBoolean()
            if (externalIdentifierAlreadyUsed) return refuseCredit(creditInfo, PixTransactionRefusalReason.DENIED, "Identificador externo já utilizado.")
        } else {
            return refuseCredit(creditInfo, PixTransactionRefusalReason.DENIED, "Necessário informar um identificador externo.")
        }

        if (creditInfo.payment) {
            Boolean isQrCodeFromAnotherCustomer = (creditInfo.customer != creditInfo.payment.provider)
            if (isQrCodeFromAnotherCustomer) return refuseCredit(creditInfo, PixTransactionRefusalReason.DENIED, "O QR Code pertence à outro recebedor.")
        }

        return validatedTransaction
    }

    public CriticalActionGroup requestPaymentRefundToken(Payment payment, BigDecimal value, String reasonDescription) {
        PixTransaction pixTransaction = PixTransaction.credit([payment: payment]).get()
        if (!pixTransaction) throw new RuntimeException("A cobrança ${payment.id} não está relacionada a uma transação Pix.")

        return requestRefundToken(pixTransaction, value, PixTransactionRefundReason.getDefaultReason(), reasonDescription)
    }

    public CriticalActionGroup requestRefundToken(PixTransaction refundedTransaction, BigDecimal value, PixTransactionRefundReason reason, String reasonDescription) {
        CreditRefundAdapter creditRefundAdapter = new CreditRefundAdapter(value, reason, reasonDescription)
        String hash = buildRefundCriticalActionHash(refundedTransaction, creditRefundAdapter)
        return criticalActionService.saveAndSendSynchronous(refundedTransaction.customer, CriticalActionType.PIX_REFUND_CREDIT, hash)
    }

    public PixTransaction refundPayment(Payment payment, BigDecimal refundValue, PixTransactionRefundReason reason, String reasonDescription, Boolean bypassCustomerValidation, Map tokenParams) {
        PixTransaction pixTransaction = PixTransaction.credit([payment: payment]).get()
        if (!pixTransaction) throw new RuntimeException("A cobrança ${payment.id} não está relacionada a uma transação Pix.")

        return refund(pixTransaction, refundValue, reason, reasonDescription, bypassCustomerValidation, tokenParams)
    }

    public PixTransaction refundWithoutPayment(PixTransaction refundedTransaction, BigDecimal value, PixTransactionRefundReason reason, String reasonDescription, Boolean bypassCustomerValidation, Map tokenParams) {
        if (refundedTransaction.payment) throw new BusinessException("Para estornos Pix vinculados a uma cobrança é necessário solicitar o estorno pela cobrança")

        refundedTransaction.lock()

        return refund(refundedTransaction, value, reason, reasonDescription, bypassCustomerValidation, tokenParams)
    }

    private PixTransaction refund(PixTransaction refundedTransaction, BigDecimal value, PixTransactionRefundReason reason, String reasonDescription, Boolean bypassCustomerValidation, Map tokenParams) {
        validateRefund(refundedTransaction, value, reason, reasonDescription, bypassCustomerValidation)

        CreditRefundAdapter creditRefundAdapter = new CreditRefundAdapter(value, reason, reasonDescription)
        if (bypassCustomerValidation) creditRefundAdapter.bypassCustomerValidation = true

        if (tokenParams.containsKey("authorizeSynchronous")) {
            creditRefundAdapter.authorizeSynchronous = tokenParams.authorizeSynchronous.asBoolean()
        } else {
            AsaasLogger.info("${this.class.simpleName}.refund >> O parâmetro 'authorizeSynchronous' não foi informado. Utilizando valor padrão. [refundedTransactionId: ${refundedTransaction.id}]")
            creditRefundAdapter.authorizeSynchronous = true
        }
        if (creditRefundAdapter.authorizeSynchronous) {
            CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: refundedTransaction.customer.id]).get()

            Boolean isAuthorizationEnabled = customerCriticalActionConfig?.isPixTransactionCreditRefundAuthorizationEnabled()
            if (ApiMobileUtils.isMobileAppRequest() && !ApiMobileUtils.appSupportsPixCreditRefundCriticalActionConfig()) isAuthorizationEnabled = customerCriticalActionConfig?.isPixTransactionAuthorizationEnabled()

            if (isAuthorizationEnabled) {
                BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(refundedTransaction.customerId, Utils.toLong(tokenParams.groupId), tokenParams.token, CriticalActionType.PIX_REFUND_CREDIT, buildRefundCriticalActionHash(refundedTransaction, creditRefundAdapter))
                if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())
            }
        }

        return pixTransactionService.refundCredit(refundedTransaction, creditRefundAdapter)
    }

    public Map buildRefundParams(BigDecimal value, PixTransactionRefundReason reason, String reasonDescription) {
        Map refundInfo = [:]
        refundInfo.value = (value * -1)
        refundInfo.type = PixTransactionType.CREDIT_REFUND
        refundInfo.reason = reason
        refundInfo.reasonDescription = reasonDescription
        return refundInfo
    }

    private void validateRefund(PixTransaction refundedTransaction, BigDecimal value, PixTransactionRefundReason reason, String reasonDescription, Boolean bypassCustomerValidation) {
        Boolean canBeRefunded = (refundedTransaction.canBeRefunded().isValid() && refundedTransaction.type.isCredit())
        if (!canBeRefunded) throw new BusinessException("Esta transação não pode ser estornada.")

        if (refundedTransaction.payment) {
            if (reason.isFraud()) {
                Boolean validPaymentStatusForFraudRefund = refundedTransaction.payment.status.isReceivedOrConfirmed()
                if (!validPaymentStatusForFraudRefund) throw new BusinessException("Este pagamento não pode ser estornado.")
            } else {
                if (refundedTransaction.payment.status.isConfirmed()) {
                    if (!refundedTransaction.status.isAwaitingCashInRiskAnalysisRequest()) throw new BusinessException("Este pagamento não pode ser estornado.")
                } else {
                    if (!refundedTransaction.payment.status.isReceived()) throw new BusinessException("Este pagamento não pode ser estornado.")
                }
            }
        }

        BigDecimal totalCompromisedValue = BigDecimalUtils.abs(value)
        if (refundedTransaction.refundedValue) totalCompromisedValue += refundedTransaction.refundedValue

        if (totalCompromisedValue  > refundedTransaction.value) throw new BusinessException("O valor total a ser estornado é superior ao valor do crédito.")

        Boolean isPaymentPartialRefund = refundedTransaction.payment && totalCompromisedValue != refundedTransaction.value
        if (isPaymentPartialRefund) {
            BusinessValidation businessValidation = PaymentRefund.paymentCanBePartiallyRefunded(refundedTransaction.payment)
            if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        Boolean reasonRequiresDescription = (reason.isOther() && !reasonDescription)
        if (reasonRequiresDescription) throw new BusinessException("Descreva o motivo deste estorno.")

        if (!bypassCustomerValidation) {
            Boolean bypassPixCheckoutLimit = CustomerParameter.getValue(refundedTransaction.customer, CustomerParameterName.PIX_ASYNC_CHECKOUT)
            if (!bypassPixCheckoutLimit) {
                AsaasError pixCheckoutLimitAsaasError = PixCheckoutValidator.validateCurrentDayTransactionLimit(refundedTransaction.customer, value.abs())
                if (pixCheckoutLimitAsaasError) throw new BusinessException(pixCheckoutLimitAsaasError.getMessage())
            }

            CheckoutValidator checkoutValidator = new CheckoutValidator(refundedTransaction.customer)
            checkoutValidator.isPixTransaction = true
            checkoutValidator.bypassSufficientBalance = refundedTransaction.isAwaitingCashInRiskAnalysis()

            if (!checkoutValidator.customerCanUsePix()) {
                throw new BusinessException(Utils.getMessageProperty("pix.denied.proofOfLife.${ refundedTransaction.customer.getProofOfLifeType() }.notApproved"))
            }

            List<AsaasError> asaasErrorList = checkoutValidator.validate(value)
            if (asaasErrorList) throw new BusinessException(Utils.getMessageProperty("pixTransaction.${asaasErrorList.first().code}"))
        }
    }

    private String buildRefundCriticalActionHash(PixTransaction refundedTransaction, CreditRefundAdapter creditRefundAdapter) {
        String operation = ""
        operation += creditRefundAdapter.value.toString()
        operation += creditRefundAdapter.type.toString()
        operation += creditRefundAdapter.reason.toString()
        operation += creditRefundAdapter.reasonDescription
        operation += refundedTransaction.id.toString()
        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    private Boolean shouldCreatePayment(CreditAdapter creditAdapter) {
        if (creditAdapter.payment) {
            if (creditAdapter.payment.canBeReceived()) return false
            return true
        }

        Boolean enablePixCreditTransactionWithoutPayment = CustomerParameter.getValue(creditAdapter.customer, CustomerParameterName.ENABLE_PIX_CREDIT_TRANSACTION_WITHOUT_PAYMENT)
        if (enablePixCreditTransactionWithoutPayment) return false

        return true
    }

    private PixTransaction refuseCredit(CreditAdapter creditInfo, PixTransactionRefusalReason refusalReason, String reasonDescription) {
        AsaasLogger.info("PixCreditService -> ${reasonDescription} [Informações do crédito: [endToEndIdentifier: ${creditInfo.endToEndIdentifier}]]")

        PixTransaction validatedTransaction = new PixTransaction()
        validatedTransaction.refusalReason = refusalReason
        validatedTransaction.refusalReasonDescription = reasonDescription
        DomainUtils.addError(validatedTransaction, Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason}", [reasonDescription]))

        return validatedTransaction
    }

}
