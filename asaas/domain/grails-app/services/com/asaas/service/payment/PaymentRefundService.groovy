package com.asaas.service.payment

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillAsaasPayment
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.installment.Installment
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentAfterConfirmEvent
import com.asaas.domain.payment.PaymentAfterCreditEvent
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.exception.BusinessException
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.invoice.InvoiceStatus
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentRefundStatus
import com.asaas.payment.PaymentStatus
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.PixTransactionType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.PaymentFeeChargedByAnticipationVO
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.split.PaymentSplitCancellationReason
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentRefundService {

    def asaasErpAccountingStatementService
    def asaasMoneyTransactionInfoService
    def billService
    def creditCardService
    def customerInvoiceService
    def debitCardService
    def financialTransactionService
    def installmentService
    def internalLoanService
    def mobilePushNotificationService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentCustodyService
    def paymentRefundSplitService
    def paymentSplitService
    def pixCreditService
    def planPaymentService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationCancellationService
    def receivableAnticipationService
    def receivableAnticipationValidationService
    def receivableAnticipationPartnerSettlementService
    def receivableRegistrationEventQueueService
    def receivableHubPaymentOutboxService
    def tedTransactionService
    def transactionReceiptService

    public Payment executeRefundRequestedByProvider(Long paymentId, Customer customer, Map options) {
        Payment payment = Payment.find(paymentId, customer.id)

        payment = validateRefundRequestedByProvider(payment, options)
        if (payment.hasErrors()) return payment

        return refund(paymentId, options + [refundOnAcquirer: true])
    }

    public Payment validateRefundRequestedByProvider(Payment payment, Map options) {
        Boolean isPartialRefund = isPaymentPartialRefund(payment, options.value)

        BigDecimal refundValue = options.value ?: payment.getRemainingRefundValue()
        if (!payment.canBeRefundedByUser(isPartialRefund, refundValue)) DomainUtils.addError(payment, payment.refundDisabledReason)

        return payment
    }

    public Payment refund(Long paymentId, Map options) {
        return refund(Payment.get(paymentId), options)
    }

    public Payment refund(Payment payment, Map options) {
        if (!payment.canBeRefundedByAdmin(options.refundOnAcquirer, options.value)) throw new RuntimeException("Não é possível estornar a cobrança [${payment.id}]: ${payment.refundDisabledReason}")

        AsaasLogger.info("Refunding payment [${payment.id}]")

        if (payment.billingType.isPix()) {
            requestPixRefund(payment, options)
        } else if (payment.billingType.isCreditCard() && isPaymentPartialRefund(payment, options.value)) {
            creditCardPartialRefund(payment, options)
        } else {
            savePaymentRefund(payment, payment.provider, null, payment.value, options, buildPaymentRefundFinancialDescription(payment))

            if (BillAsaasPayment.canRefundBill(payment)) {
                Bill bill = BillAsaasPayment.query([column: "bill", payment: payment]).get()
                billService.refund(bill)
            }
        }

        return payment
    }

    public void executeAllPendingPaymentRefundIfNecessary(Payment payment) {
        List<PaymentRefund> paymentRefundList = PaymentRefund.query([payment: payment, status: PaymentRefundStatus.PENDING, disableSort: true]).list()

        for (PaymentRefund paymentRefund : paymentRefundList) {
            executeRefund(paymentRefund, false)
        }
    }

    public void executeRefund(PaymentRefund paymentRefund, Boolean refundOnAcquirer) {
        if (paymentRefund.status != PaymentRefundStatus.PENDING) throw new BusinessException("Essa solicitação de estorno não pode ser executada.")

        Payment payment = paymentRefund.payment

        if (payment.status.isReceivedOrConfirmed()) {
            if (payment.status.isReceived() && !paymentRefund.valueDebited) reversePayment(paymentRefund, buildFinancialDescription(payment), null)

            Boolean hasChargedPaymentFee = FinancialTransaction.query([exists: true, payment: payment, transactionType: FinancialTransactionType.PAYMENT_FEE]).get()
            if (paymentRefund.shouldRefundFee && hasChargedPaymentFee) {
                PaymentFeeChargedByAnticipationVO paymentFeeChargedByAnticipation = receivableAnticipationService.getInfoPaymentFeeChargedByAnticipation(payment)
                if (paymentFeeChargedByAnticipation) reversePaymentFeeChargedByAnticipation(payment, paymentFeeChargedByAnticipation)

                financialTransactionService.reversePaymentFee(payment, null)
            } else if (payment.status.isReceived() && !payment.isCreditCard()) {
                paymentRefund.shouldRefundFee = false
                payment.refundFee = payment.getAsaasValue()
                payment.save(flush: false, failOnError: true)
            }
        }

        paymentRefund.status = PaymentRefundStatus.DONE
        paymentRefund.save(failOnError: true)

        if (!isFullyRefunded(payment)) {
            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_PARTIALLY_REFUNDED)
        }

        executeRefund(payment, refundOnAcquirer)
    }

    public void executeRefund(Payment payment) {
        executeRefund(payment, false)
    }

    public void executeRefund(Payment payment, Boolean refundOnAcquirer) {
        cancelInvoice(payment)

        if (!isFullyRefunded(payment)) return

        if (existsPaymentAfterConfirmOrCreditEvent(payment.id)) throw new BusinessException(Utils.getMessageProperty("paymentRefund.cannotBeRefunded.atTheMoment"))

        if (keepRefundInProgress(payment)) {
            payment.status = PaymentStatus.REFUND_IN_PROGRESS
            payment.save(flush: true, failOnError: true)
            receivableAnticipationValidationService.onPaymentChange(payment)
            receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_REFUNDED)
            asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)

            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_REFUND_IN_PROGRESS)
            return
        }

        Boolean isCreditCardPartialRefund = (payment.billingType.isCreditCard() && isPaymentPartialRefund(payment, null))

        payment.status = PaymentStatus.REFUNDED
        payment.refundedDate = new Date()
        payment.save(flush: true, failOnError: true)
        payment.hasRefundInProgress = false

        receivableAnticipationValidationService.onPaymentChange(payment)

        if (payment.isPlanPayment()) planPaymentService.refund(payment)

        paymentSplitService.refundSplit(payment, PaymentSplitCancellationReason.PAYMENT_REFUNDED)
        receivableAnticipationService.debit(payment, false)
        receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_REFUNDED)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)

        if (!payment.isCreditCardInstallment()) {
            if (!isCreditCardPartialRefund) transactionReceiptService.savePaymentRefunded(payment)

            if (PaymentUndefinedBillingTypeConfig.equivalentToBoleto(payment) || refundOnAcquirer || payment.isDebitCard()) {
                mobilePushNotificationService.notifyPaymentRefunded(payment)
            }
        }

        if (payment.isCreditCard() && !isCreditCardPartialRefund) processCreditCardRefund(payment, refundOnAcquirer)

        if (payment.isDebitCard()) processDebitCardRefund(payment, refundOnAcquirer)

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_REFUNDED)
        receivableHubPaymentOutboxService.savePaymentRefunded(payment)

        if (payment.billingType.isTransfer()) tedTransactionService.rejectTedAfterPaymentRefundIfNecessary(payment)
    }

    public PaymentRefund cancel(PaymentRefund paymentRefund) {
        if (!paymentRefund.status.isCancellable()) throw new BusinessException("Não é possível cancelar esta solicitação de estorno")

        paymentRefund.status = PaymentRefundStatus.CANCELLED
        paymentRefund.valueDebited = false
        paymentRefund.save(failOnError: true)

        if (!paymentRefund.payment.status.isConfirmed()) {
            FinancialTransaction financialTransaction = financialTransactionService.cancelPaymentRefundIfNecessary(paymentRefund)
            internalLoanService.cancelPendingLoan(financialTransaction.reversedTransaction)
        }

        return paymentRefund
    }

    @Deprecated
    public void cancel(Payment payment) {
        Boolean canBeCancelled = (payment.isRefundRequested() && payment.billingType.isPix())
        if (!canBeCancelled) throw new BusinessException("O estorno desta cobrança não pode ser cancelado.")

        payment.status = PaymentStatus.RECEIVED
        payment.refundedDate = new Date()
        payment.save(flush: true, failOnError: true)

        receivableAnticipationValidationService.onPaymentChange(payment)
        financialTransactionService.cancelPaymentRefund(payment)
    }

    public void refundInstallmentsWithExpiredAuthorization(Date startDate, Date endDate) {
        List<Long> installmentIdList = CreditCardTransactionInfo.query(["column": "installment.id", paymentStatus: PaymentStatus.AUTHORIZED, 'dateCreated[ge]': startDate, 'dateCreated[le]': endDate, installmentIsNotNull: true, distinct: 'installment.id']).list()

        Utils.forEachWithFlushSession(installmentIdList, 50, { Long installmentId ->
            Utils.withNewTransactionAndRollbackOnError({
                Installment installment = Installment.get(installmentId)
                installmentService.refundCreditCard(installment, true, [:])
            }, [logErrorMessage: "PaymentRefundService.refundInstallmentsWithExpiredAuthorization >> Erro ao processar estorno do parcelamento [${installmentId}]"])
        })
    }

    public void refundPaymentsWithExpiredAuthorization(Date startDate, Date endDate) {
        List<Long> paymentIdList = CreditCardTransactionInfo.query(["column": "payment.id", paymentStatus: PaymentStatus.AUTHORIZED, 'dateCreated[ge]': startDate, 'dateCreated[le]': endDate, installmentIsNull: true]).list()

        Utils.forEachWithFlushSession(paymentIdList, 50, { Long paymentId ->
            Utils.withNewTransactionAndRollbackOnError({
                Payment payment = Payment.get(paymentId)
                refund(payment, [refundOnAcquirer: true, value: payment.getRemainingRefundValue()])
            }, [logErrorMessage: "PaymentRefundService.refundPaymentWithExpiredAuthorization >> Erro ao processar estorno da cobrança [${paymentId}]"])
        })
    }

    public Boolean generateRetroactivePaymentRefund(Long customerId) {
        final Integer maxItemsPerCycle = 4000
        Map searchPaymentRefunded = [column: "id", statusList: [PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUND_IN_PROGRESS, PaymentStatus.REFUNDED], "paymentRefund[notExists]": true, customerId: customerId]

        List<Long> paymentIdList = Payment.query(searchPaymentRefunded).list(max: maxItemsPerCycle)
        if (!paymentIdList) return false

        Boolean hasErrors = false
        Integer flushEvery = 100
        Utils.withNewTransactionAndRollbackOnError({
            Utils.forEachWithFlushSession(paymentIdList, flushEvery, { Long paymentId ->
                Payment payment = Payment.read(paymentId)

                PaymentRefund paymentRefund = new PaymentRefund()
                paymentRefund.customer = payment.provider
                paymentRefund.payment = payment
                paymentRefund.value = BigDecimalUtils.abs(payment.value)
                paymentRefund.status = payment.status.isRefundRequested() ? PaymentRefundStatus.PENDING : PaymentRefundStatus.DONE

                if (payment.billingType.isPix()) {
                    PixTransaction pixTransaction = PixTransaction.query([payment: payment, type: PixTransactionType.CREDIT_REFUND, readOnly: true]).get()
                    paymentRefund.pixTransaction = pixTransaction
                }

                Boolean existsPaymentReversalFinancialTransaction = FinancialTransaction.query([exists: true, payment: payment, "transactionType": FinancialTransactionType.PAYMENT_REVERSAL]).get().asBoolean()
                paymentRefund.valueDebited = existsPaymentReversalFinancialTransaction

                Boolean existsPaymentFeeReversalFinancialTransaction = FinancialTransaction.query([exists: true, payment: payment, "transactionType": FinancialTransactionType.PAYMENT_FEE_REVERSAL]).get().asBoolean()
                paymentRefund.shouldRefundFee = existsPaymentFeeReversalFinancialTransaction

                paymentRefund.save(failOnError: true)
            })

            Customer customer = Customer.read(customerId)
            financialTransactionService.recalculateBalance(customer)
        }, [logErrorMessage: "PaymentRefundService.generateRetroactivePaymentRefund >> Erro ao criar paymentRefund retroativos para o customer [${customerId}]",
            onError: { hasErrors = true }])

        return hasErrors
    }

    public void reversePaymentFeeChargedByAnticipation(Payment payment, PaymentFeeChargedByAnticipationVO paymentFeeChargedByAnticipation) {
        Boolean canReversePaymentFee = receivableAnticipationService.canReversePaymentFee(payment, paymentFeeChargedByAnticipation)
        if (canReversePaymentFee) {
            payment.netValue = ReceivableAnticipationCalculator.getPaymentNetValueChargedByAnticipation(payment)
            payment.save(failOnError: true)

            receivableAnticipationPartnerSettlementService.savePaymentFeeRefundValue(paymentFeeChargedByAnticipation.partnerAcquisition, payment)
        }
    }

    public void onCriticalActionAuthorization(PaymentRefund paymentRefund) {
        if (!paymentRefund.status.isAwaitingCriticalActionAuthorization()) throw new BusinessException("A situação do estorno não permite autorização.")

        paymentRefund.status = PaymentRefundStatus.PENDING
        paymentRefund.save(failOnError: true)
    }

    private void processCreditCardRefund(Payment payment, Boolean refundOnAcquirer) {
        if (refundOnAcquirer) {
            creditCardService.refund(payment.id, null)
        } else if (!payment.isCreditCardInstallment()) {
            CreditCardTransactionInfo creditCardTransactionInfo = CreditCardTransactionInfo.query([paymentId: payment.id]).get()
            if (creditCardTransactionInfo) {
                creditCardTransactionInfo.chargeback = true
                creditCardTransactionInfo.save(flush: true, failOnError: true)
            }
        }

        if (!payment.installment) asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(payment)

        Map eventData = [paymentId: payment.id]
        receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.RECEIVABLE_UNIT_ITEM_REFUNDED, eventData, eventData.encodeAsMD5())
    }

    private void processDebitCardRefund(Payment payment, Boolean refundOnAcquirer) {
        if (refundOnAcquirer) {
            debitCardService.refund(payment.id)
        }

        Map eventData = [paymentId: payment.id]
        receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.RECEIVABLE_UNIT_ITEM_REFUNDED, eventData, eventData.encodeAsMD5())
    }

    private void cancelInvoice(Payment payment) {
        Invoice invoice = payment.getInvoice()

        if (!invoice && payment.installment) invoice = payment.installment.getInvoice()

        if (!invoice) return

        if (invoice.status == InvoiceStatus.AUTHORIZED && !customerInvoiceService.supportsCancellation(invoice)) return

        if (invoice.originType.isInstallment()) {
            customerInvoiceService.updateInstallmentInvoice(payment)
        } else if (invoice.originType.isPayment()) {
            customerInvoiceService.cancelInvoice(payment)
        }
    }

    private void requestPixRefund(Payment payment, Map options) {
        BigDecimal refundValue = options.value ?: payment.value
        Boolean bypassCustomerValidation = (options.bypassCustomerValidation) ?: false

        PixTransactionRefundReason reason = options.reason ?: PixTransactionRefundReason.getDefaultReason()
        Map tokenParams = [
            groupId: options.groupId,
            token: options.token,
            authorizeSynchronous: options.authorizeSynchronous
        ]
        PixTransaction pixTransaction = pixCreditService.refundPayment(payment, refundValue, reason, options.description, bypassCustomerValidation, tokenParams)

        if (pixTransaction.hasErrors()) throw new RuntimeException("Erro ao fazer transação de estorno de Pix ${pixTransaction.errors}")

        String financialDescription = buildPixFinancialDescription(payment, reason)
        savePaymentRefund(payment, pixTransaction.customer, pixTransaction, refundValue, options, financialDescription)
    }

    private Boolean isFullyRefunded(Payment payment) {
        if (!payment.billingType.isPix() && !payment.billingType.isCreditCard()) return true
        if (payment.status.isRefundRequested()) return true

        if (payment.billingType.isCreditCard()) {
            Boolean existsPendingPaymentRefund = PaymentRefund.query([payment: payment, status: PaymentRefundStatus.PENDING, exists: true]).get().asBoolean()
            if (existsPendingPaymentRefund) return false
        }

        BigDecimal refundedValue = PaymentRefund.sumValueAbs([payment: payment, status: PaymentRefundStatus.DONE]).get()

        if (payment.billingType.isCreditCard() && refundedValue == 0) return true

        return refundedValue >= payment.value
    }

    private PaymentRefund savePaymentRefund(Payment payment, Customer customer, PixTransaction pixTransaction, BigDecimal refundValue, Map options, String financialDescription) {
        payment.lock()

        validatePaymentRefundSave(payment, refundValue)

        PaymentRefund paymentRefund = new PaymentRefund()
        paymentRefund.pixTransaction = pixTransaction
        paymentRefund.customer = customer
        paymentRefund.payment = payment
        paymentRefund.value = BigDecimalUtils.abs(refundValue)
        if (pixTransaction && pixTransaction.status.isAwaitingCriticalActionAuthorization()) {
            paymentRefund.status = PaymentRefundStatus.AWAITING_CRITICAL_ACTION_AUTHORIZATION
        } else {
            paymentRefund.status = PaymentRefundStatus.PENDING
        }
        paymentRefund.valueDebited = false

        Boolean isFullyRefund = paymentRefund.value == payment.getRemainingRefundValue()
        paymentRefund.shouldRefundFee = shouldRefundFee(payment, isFullyRefund, options)
        paymentRefund.save(failOnError: true)

        if (options.paymentRefundSplitVoList) paymentRefundSplitService.savePartialRefundSplit(paymentRefund, options.paymentRefundSplitVoList)

        if (!isFullyRefund) {
            BigDecimal totalPaymentCompromisedValue = payment.getAlreadyRefundedValue() + payment.getValueCompromisedWithPaymentSplit()
            if (totalPaymentCompromisedValue > payment.value) {
                throw new BusinessException("Valor da cobrança insuficiente para o estorno solicitado.")
            }
        }

        if (payment.isReceived()) reversePayment(paymentRefund, financialDescription, options.refundRequest)

        if (shouldExecuteRefund(payment, options)) executeRefund(paymentRefund, options.refundOnAcquirer?.asBoolean())

        receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_REFUNDED)

        if (payment.isCreditCard()) {
            if (isFullyRefund && isPaymentPartialRefund(payment, refundValue)) executeAllPendingPaymentRefundIfNecessary(payment)

            Map eventData = [paymentId: payment.id]
            receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.RECEIVABLE_UNIT_ITEM_REFUNDED, eventData, eventData.encodeAsMD5())
            if (!isFullyRefund) receivableHubPaymentOutboxService.savePaymentPartiallyRefunded(payment)

            Boolean shouldSaveTransactionReceiptPartialRefund = options.isInstallmentPartialRefund || isPaymentPartialRefund(payment, refundValue)
            if (shouldSaveTransactionReceiptPartialRefund) transactionReceiptService.savePaymentCreditCardPartialRefund(paymentRefund)
        }

        return paymentRefund
    }

    private Boolean shouldRefundFee(Payment payment, Boolean isFullyRefund, Map options) {
        if (!isFullyRefund) return false
        if (payment.isCreditCard()) return true
        if (payment.isBoleto() && options.reverseBoletoPaymentFee.asBoolean()) return true

        return false
    }

    private Boolean shouldExecuteRefund(Payment payment, Map options) {
        if (payment.billingType.isPix()) return false
        if (options.refundRequest) return false
        if (payment.isReceived()) return true
        if (isPaymentPartialRefund(payment, options.value)) return false

        return true
    }

    private void validatePaymentRefundSave(Payment payment, BigDecimal refundValue) {
        if (!refundValue) throw new BusinessException("Informe algum valor para processar o estorno.")

        if (keepRefundInProgress(payment)) throw new BusinessException("Não é possível solicitar estorno parcial um dia útil antes da data de crédito.")

        if (existsPaymentAfterConfirmOrCreditEvent(payment.id)) throw new BusinessException(Utils.getMessageProperty("paymentRefund.cannotBeRefunded.atTheMoment"))

        BigDecimal alreadyRefundedValue = PaymentRefund.sumValueAbs([payment: payment, "status[in]": PaymentRefundStatus.listCompromised()]).get()
        if (alreadyRefundedValue + refundValue > payment.value) {
            BigDecimal availableRefundValue = payment.value - alreadyRefundedValue
            throw new BusinessException("O valor máximo disponível de estorno para essa cobrança é de ${FormUtils.formatCurrencyWithMonetarySymbol(availableRefundValue)}.")
        }
    }

    private String buildPixFinancialDescription(Payment payment, PixTransactionRefundReason reason) {
        if (reason.isExternalRefundRequest()) {
            return "Estorno via mecanismo especial de devolução - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"
        }

        return buildPaymentRefundFinancialDescription(payment)
    }

    private String buildPaymentRefundFinancialDescription(Payment payment) {
        return "Estorno - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"
    }

    private void creditCardPartialRefund(Payment payment, Map params) {
        BigDecimal refundValue = params.value ?: payment.getRemainingRefundValue()

        savePaymentRefund(payment, payment.provider, null, refundValue, params, buildFinancialDescription(payment))

        if (params.refundOnAcquirer) {
            Map creditCardTransactionInfoMap = CreditCardTransactionInfo.query([columnList: ["gateway", "transactionIdentifier"], paymentId: payment.id]).get()
            creditCardService.refund(creditCardTransactionInfoMap.gateway, creditCardTransactionInfoMap.transactionIdentifier, refundValue)
        }
    }

    private Boolean isPaymentPartialRefund(Payment payment, BigDecimal refundValue) {
        if (!refundValue) refundValue = payment.getRemainingRefundValue()

        if (refundValue > 0 && refundValue < payment.value) return true

        BigDecimal alreadyRefundedValue = PaymentRefund.query([column: "value", payment: payment, "status[in]": PaymentRefundStatus.listCompromised()]).get()
        if (alreadyRefundedValue && alreadyRefundedValue <  payment.value) return true

        return false
    }

    private void reversePayment(PaymentRefund paymentRefund, String financialDescription, RefundRequest refundRequest) {
        paymentRefundSplitService.executePartialRefundSplitIfNecessary(paymentRefund)

        paymentCustodyService.onPaymentRefund(paymentRefund)

        FinancialTransaction financialTransaction = financialTransactionService.reversePayment(paymentRefund, financialDescription, refundRequest)
        internalLoanService.saveIfNecessary(financialTransaction)

        paymentRefund.valueDebited = true
        paymentRefund.save(failOnError: true)
    }

    private String buildFinancialDescription(Payment payment) {
        switch (payment.billingType) {
            case BillingType.MUNDIPAGG_CIELO:
                return "Estorno - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"
            default:
                throw new RuntimeException("Descrição da transação financeira não configurada para ${payment.billingType.getLabel()}")
        }
    }

    private Boolean keepRefundInProgress(Payment payment) {
        if (payment.hasRefundInProgress) return false

        if (!payment.isConfirmed()) return false

        if (!payment.billingType.isCreditCardOrDebitCard()) return false

        if (payment.creditDate == new Date().clearTime()) return true

        Date settlementScheduleLimitDate = CustomDateUtils.subtractMinutesAndSetForLastBusinessDay(payment.creditDate, AsaasApplicationHolder.config.payment.settlement.minutesToScheduleBeforeSettlement)
        if (new Date() > settlementScheduleLimitDate) return true

        return false
    }

    private Boolean existsPaymentAfterConfirmOrCreditEvent(Long paymentId) {
        return PaymentAfterConfirmEvent.existsForPayment(paymentId) || PaymentAfterCreditEvent.existsForPayment(paymentId)
    }
}
