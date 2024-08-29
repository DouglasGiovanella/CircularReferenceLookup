package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentDunningAgreement
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.paymentdunning.PaymentDunningCustomerAccountInfo
import com.asaas.domain.paymentdunning.PaymentDunningDocument
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.payment.PaymentDunningStatus
import com.asaas.payment.PaymentStatus
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.gorm.PagedResultList

@Transactional
class PaymentDunningService {

    def chargedFeeService
    def creditBureauDunningBatchItemService
    def customerAlertNotificationService
    def customerMessageService
    def messageService
    def mobilePushNotificationService
    def notificationDispatcherPaymentNotificationOutboxService
    def originRequesterInfoService
    def paymentDunningAgreementService
    def paymentDunningCustomerAccountInfoService
    def paymentDunningDocumentService
    def paymentDunningStatusHistoryService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationBlocklistService
    def asaasErpAccountingStatementService

    public void setDefaultFeeValue(PaymentDunning paymentDunning) {
        setFeeAndNetValue(paymentDunning, PaymentDunning.calculateFeeValue(paymentDunning.payment, paymentDunning.type))
    }

    public void setFeeAndNetValue(PaymentDunning paymentDunning, BigDecimal fee) {
        paymentDunning.fee = fee
        paymentDunning.netValue = PaymentDunning.calculateNetValue(paymentDunning.value, paymentDunning.fee)
    }

    public PaymentDunning resendToAnalysis(Long customerId, Long paymentDunningId, List<Long> temporaryFileIdList) {
        PaymentDunning paymentDunning = PaymentDunning.find(paymentDunningId, Customer.get(customerId))

        PaymentDunning validatedDunning = validateIfCanBeResendToAnalysis(paymentDunning, temporaryFileIdList)
        if (validatedDunning.hasErrors()) return validatedDunning

        paymentDunningDocumentService.delete(paymentDunning)

        for (Long temporaryFileId in temporaryFileIdList) {
            PaymentDunningDocument paymentDunningDocument = paymentDunningDocumentService.save(paymentDunning, temporaryFileId)
            if (paymentDunningDocument.hasErrors()) {
                transactionStatus.setRollbackOnly()
                validatedDunning = DomainUtils.copyAllErrorsFromObject(paymentDunningDocument, validatedDunning)
                return validatedDunning
            }
        }

        setAsAwaitingApproval(paymentDunning)

        if (paymentDunning.type.isCreditBureau() && paymentDunning.fee > 0) {
            chargedFeeService.saveDunningRequestFee(paymentDunning)
        }

        paymentDunningStatusHistoryService.save(paymentDunning, "Reenviado para análise")

        return paymentDunning
    }

    public void processPendingCreditBureauDunningWithMinimumOverdueDays() {
        Map search = [:]
        search.column = "id"
        search.type = PaymentDunningType.CREDIT_BUREAU
        search.status = PaymentDunningStatus.PENDING
        search.'paymentStatus[in]' = PaymentStatus.OVERDUE
        search.'paymentDueDate[lt]' = CustomDateUtils.sumDays(new Date(), (PaymentDunning.MINIMUM_OVERDUE_DAYS * -1))

        List<Long> paymentDunningIdList = PaymentDunning.query(search).list()

        for (Long paymentDunningId in paymentDunningIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PaymentDunning paymentDunning = PaymentDunning.get(paymentDunningId)
                processPending(paymentDunning)
            }, [logErrorMessage: "PaymentDunningService.processPendingCreditBureauDunningWithMinimumOverdueDays >> Erro ao processar negativação pendente [id: ${paymentDunningId}]"])
        }
    }

    public void setAsPaid(PaymentDunning paymentDunning) {
        paymentDunning.status = PaymentDunningStatus.PAID
        paymentDunning.save(failOnError: true)

        paymentDunning.payment.status = PaymentStatus.DUNNING_RECEIVED
        paymentDunning.payment.save(failOnError: true)

        customerAlertNotificationService.notifyDunningPaid(paymentDunning.customer, paymentDunning.id)
        paymentPushNotificationRequestAsyncPreProcessingService.save(paymentDunning.payment, PushNotificationRequestEvent.PAYMENT_DUNNING_RECEIVED)

        asaasErpAccountingStatementService.onPaymentUpdate(paymentDunning.payment.provider, paymentDunning.payment, null, true)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentDunning(paymentDunning)

        paymentDunningStatusHistoryService.save(paymentDunning)

        if (PaymentUndefinedBillingTypeConfig.equivalentToBoleto(paymentDunning.payment) || paymentDunning.payment.billingType.isPix()) {
            receivableAnticipationBlocklistService.setOverdueBankSlipPaymentToBeReanalyzeTomorrow(paymentDunning.payment.customerAccount)
        }
    }

    public PaymentDunning activate(paymentDunningId, Customer customer) {
        PaymentDunning paymentDunning = PaymentDunning.find(paymentDunningId, customer)
        BusinessValidation canBeActivate = paymentDunning.canBeActivate()

        if (!canBeActivate.isValid()) throw new BusinessException(canBeActivate.getFirstErrorMessage())

        paymentDunning.status = PaymentDunningStatus.PENDING
        paymentDunning.save(failOnError: true)
        paymentDunningStatusHistoryService.save(paymentDunning)

        return paymentDunning
    }

    public void processPending (PaymentDunning paymentDunning) {
        setDefaultFeeValue(paymentDunning)

        if (paymentDunning.customerHasBalanceToDunning().isValid()) {
            if (paymentDunning.type.isCreditBureau() && paymentDunning.fee > 0) chargedFeeService.saveDunningRequestFee(paymentDunning)
            setAsAwaitingApproval(paymentDunning)
            paymentDunningStatusHistoryService.save(paymentDunning)
            approve(paymentDunning.id)
        } else {
            paymentDunning = cancel(paymentDunning, PaymentDunningCancellationReason.INSUFFICIENT_BALANCE, "Para efetuar novamente a Negativação deposite o saldo de ${FormUtils.formatCurrencyWithMonetarySymbol(CustomerFee.getDunningCreditBureauFeeValue(paymentDunning.customerId))} referente à taxa da Negativação via Serasa.")
            customerMessageService.sendPaymentDunningCancelledByInsufficientBalanceEmail(paymentDunning)
            customerAlertNotificationService.notifyDunningCanceledByInsufficientBalance(paymentDunning)
        }
    }

    public PaymentDunning approve(Long paymentDunningId) {
        PaymentDunning paymentDunning = PaymentDunning.get(paymentDunningId)
        if (!paymentDunning) throw new Exception("Negativação inexistente.")

        PaymentDunning validatedDunning = validateIfCanBeApproved(paymentDunning)
        if (validatedDunning.hasErrors()) return validatedDunning

        paymentDunning.status = PaymentDunningStatus.APPROVED
        paymentDunning.save(failOnError: true)

        paymentDunningDocumentService.setAsApproved(paymentDunning)

        creditBureauDunningBatchItemService.saveCreationItem(paymentDunning)

        paymentDunningStatusHistoryService.save(paymentDunning)

        return paymentDunning
    }

    public PaymentDunning deny(Long paymentDunningId, String denialReason) {
        PaymentDunning paymentDunning = PaymentDunning.get(paymentDunningId)
        if (!paymentDunning) throw new Exception("Negativação inexistente.")

        PaymentDunning validatedDunning = validateIfCanBeDenied(paymentDunning, denialReason)
        if (validatedDunning.hasErrors()) return validatedDunning

        paymentDunning.status = PaymentDunningStatus.DENIED
        paymentDunning.denialReason = denialReason
        paymentDunning.save(failOnError: true)

        paymentDunningDocumentService.setAsDenied(paymentDunning)

        customerMessageService.sendPaymentDunningDeniedEmail(paymentDunning)
        customerAlertNotificationService.notifyDunningDenied(paymentDunning)
        mobilePushNotificationService.notifyDunningDenied(paymentDunning)

        if (paymentDunning.fee > 0) chargedFeeService.refundDunningRequestFee(paymentDunning)

        paymentDunningStatusHistoryService.save(paymentDunning, denialReason)

        return paymentDunning
    }

	public PaymentDunning save(Customer customer, User user, Long paymentId, Map params) {
        Payment payment = Payment.find(paymentId, customer.id)

        if (!PaymentDunningAgreement.customerHasCurrentAgreementVersion(payment.provider)) {
            PaymentDunningAgreement agreement = paymentDunningAgreementService.save(payment.provider, user, params)

            if (agreement.hasErrors()) {
                return DomainUtils.copyAllErrorsFromObject(agreement, new PaymentDunning())
            }
        }

        PaymentDunning validatedDunning = validateSave(payment.provider, payment)
        if (validatedDunning.hasErrors()) return validatedDunning

        PaymentDunning dunning = new PaymentDunning()
        dunning.customer = payment.provider
        dunning.payment = payment
        dunning.value = payment.value
        dunning.type = PaymentDunningType.CREDIT_BUREAU
        dunning.publicId = UUID.randomUUID()

        setDefaultFeeValue(dunning)

        if (dunning.type.isCreditBureau() && !params.description?.trim()) params.description = null
        dunning.description = params.description
        dunning.save()

        if (dunning.hasErrors()) {
            AsaasLogger.warn("PaymentDunningService.save >> Encontrado erros ao salvar domínio. ${dunning.errors.allErrors}")
            transactionStatus.setRollbackOnly()
            return dunning
        }

        PaymentDunningCustomerAccountInfo paymentDunningCustomerAccountInfo = paymentDunningCustomerAccountInfoService.save(dunning, params)
        if (paymentDunningCustomerAccountInfo.hasErrors()) {
            transactionStatus.setRollbackOnly()
            validatedDunning = DomainUtils.copyAllErrorsFromObject(paymentDunningCustomerAccountInfo, validatedDunning)
            return validatedDunning
        }

        CustomerAccount customerAccount = paymentDunningCustomerAccountInfoService.updateCustomerAccountIfNecessary(paymentDunningCustomerAccountInfo)
        if (customerAccount.hasErrors()) {
            transactionStatus.setRollbackOnly()
            validatedDunning = DomainUtils.copyAllErrorsFromObject(customerAccount, validatedDunning)
            return validatedDunning
        }

        List<Long> temporaryFileIdList = params.addedFilesList?.tokenize(",").collect { Long.valueOf(it) } ?: []
        for (Long temporaryFileId in temporaryFileIdList) {
            PaymentDunningDocument paymentDunningDocument = paymentDunningDocumentService.save(dunning, temporaryFileId)
            if (paymentDunningDocument.hasErrors()) {
                transactionStatus.setRollbackOnly()
                validatedDunning = DomainUtils.copyAllErrorsFromObject(paymentDunningDocument, validatedDunning)
                return validatedDunning
            }
        }

        paymentDunningStatusHistoryService.save(dunning)

        originRequesterInfoService.save(dunning)

        notificationDispatcherPaymentNotificationOutboxService.savePaymentDunning(dunning)

		return dunning
	}

    public PaymentDunning update(PaymentDunning dunning) {
        if (dunning.status.isAwaitingApproval() || dunning.status.isApproved()) {
            DomainUtils.addError(dunning, "Não é permitido atualizar uma negativação em análise.")
            return dunning
        }

        if (dunning.status.isAwaitingCancellation() || dunning.status.isAwaitingPartnerCancellation()) {
            DomainUtils.addError(dunning, "Não é permitido atualizar uma negativação aguardando cancelamento.")
            return dunning
        }

        if (PaymentDunningStatus.transmittedList().contains(dunning.status) || dunning.status.isAwaitingPartnerApproval()) {
            DomainUtils.addError(dunning, "Não é permitido atualizar uma negativação iniciada.")
            return dunning
        }

        dunning.value = dunning.payment.value
        setDefaultFeeValue(dunning)

		dunning.save(failOnError: true)

		return dunning
	}

    private PaymentDunning validateSave(Customer customer, Payment payment) {
        PaymentDunning dunning = new PaymentDunning()

        BusinessValidation validatedBusiness = PaymentDunning.canRequestDunning(payment, PaymentDunningType.CREDIT_BUREAU)
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(dunning, validatedBusiness.getFirstErrorMessage())
            return dunning
        }

        if (!PaymentDunningAgreement.customerHasCurrentAgreementVersion(customer)) {
            DomainUtils.addError(dunning, "É necessário assinar o aditivo de termos de uso para ativar uma negativação.")
        }

        return dunning
    }

    public void delete(Payment payment) {
        PaymentDunning paymentDunning = PaymentDunning.query([payment: payment]).get()
        if (!paymentDunning) return

        paymentDunning.deleted = true
        paymentDunning.save(flush: true, failOnError: true)
    }

    public PaymentDunning cancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason) {
        return cancel(paymentDunning, cancellationReason, null)
    }

    public PaymentDunning cancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason, String description) {
        setPaymentAsOverdueIfNecessary(paymentDunning)

        paymentDunning.status = PaymentDunningStatus.CANCELLED
        paymentDunning.cancellationReason = cancellationReason

        if (cancellationReason.isRequestedByAsaas()) {
            paymentDunning.denialReason = Utils.getMessageProperty("paymentDunning.cancel.REQUESTED_BY_ASAAS.denialReason")
        } else if (description) {
            paymentDunning.denialReason = description
        }

        if (paymentDunning.type.isDebtRecoveryAssistance()) paymentDunning.deleted = true
        paymentDunning.save(flush: true, failOnError: true)

        paymentDunningStatusHistoryService.save(paymentDunning, description)

        notificationDispatcherPaymentNotificationOutboxService.savePaymentDunning(paymentDunning)

        return paymentDunning
    }

    public void setPaymentAsOverdueIfNecessary(PaymentDunning paymentDunning) {
        if (!paymentDunning.payment.status.isDunningRequested()) return

        Boolean canUpdatePaymentStatus = paymentDunning.isProcessed()
        if (!canUpdatePaymentStatus) canUpdatePaymentStatus = paymentDunning.status.isAwaitingProcessing()
        if (!canUpdatePaymentStatus) canUpdatePaymentStatus = paymentDunning.status.isAwaitingPartnerCancellation()
        if (!canUpdatePaymentStatus) canUpdatePaymentStatus = paymentDunning.customer.isAsaasDebtRecoveryProvider() && paymentDunning.status.isAwaitingCancellation()
        if (!canUpdatePaymentStatus) return

        paymentDunning.payment.status = PaymentStatus.OVERDUE
        paymentDunning.payment.save(flush: true, failOnError: true)
    }

    public void restoreDunningIfNecessary(Payment payment) {
		PaymentDunning paymentDunning = PaymentDunning.query([includeDeleted: true, payment: payment]).get()
		if (!paymentDunning) return

		paymentDunning.deleted = false
		paymentDunning.save(failOnError: true)
	}

    public Map buildPaymentDunningSummary(Long customerId, Long paymentId) {
        Payment payment = Payment.find(paymentId, customerId)
        BigDecimal feeValue = PaymentDunning.calculateFeeValue(payment, PaymentDunningType.CREDIT_BUREAU)

        return [dunningValue: payment.value,
                dunningNetValue: PaymentDunning.calculateNetValue(payment.value, feeValue),
                dunningFee: feeValue,
                dunningStartDate: CustomDateUtils.fromDate(PaymentDunning.calculateDunningStartDate(payment))]
    }

    public PagedResultList listPaymentsAvailableForDunning(Customer customer, Map filters, Integer limit, Integer offset) {
        BusinessValidation customerValidation = PaymentDunning.validateCustomer(customer)
        if (!customerValidation.isValid()) {
            throw new BusinessException(customerValidation.getFirstErrorMessage())
        }

        filters += [
            customer: customer,
            statusList: [PaymentStatus.PENDING, PaymentStatus.OVERDUE],
            withoutValidDunning: true,
            isNotCreditCardInstallment: true
        ]

        return Payment.query(filters).list(max: limit, offset: offset)
    }

    public void notifyCustomerCanRequestDunning() {
        return
        List<Long> customerIdList = Payment.readyToRequestDunning([distinct: "provider.id"]).list()

        for (Long customerId : customerIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)

                if (customer.isLegalPerson() && !customer.isNotFullyApproved()) {
                    List<Payment> paymentList = Payment.readyToRequestDunning([customer: customer]).list([max: 10])
                    if (paymentList) messageService.notifyCustomerCanRequestDunning(paymentList, customer)
                }
            }, [onError: { Exception e ->
                AsaasLogger.error("PaymentDunningService.notifyCustomerCanRequestDunning >> Erro ao notificar clientes com cobranças vencidas que podem ser recuperadas", e)
            }])
        }
    }

    public anyPaymentHasDebtRecoveryAssistanceRequestedOrReceived(Installment installment) {
        return installment.payments.any { it.getDunning()?.type?.isDebtRecoveryAssistance() && (it.isDunningRequested() || it.isDunningReceived()) }
    }

    private PaymentDunning validateIfCanBeApproved(PaymentDunning paymentDunning) {
        PaymentDunning validateDunning = new PaymentDunning()

        if (!paymentDunning.status.isAwaitingApproval()) {
            DomainUtils.addError(validateDunning, "Não é possível aprovar uma negativação que não esteja esperando aprovação.")
        }

        return validateDunning
    }

    private PaymentDunning validateIfCanBeDenied(PaymentDunning paymentDunning, String denialReason) {
        PaymentDunning validateDunning = new PaymentDunning()

        if (!paymentDunning.status.isAwaitingApproval()) {
            DomainUtils.addError(validateDunning, "Não é possível negar essa negativação.")
        }

        if (!denialReason) {
            DomainUtils.addError(validateDunning, "É obrigatório informar o motivo da negação.")
        }

        return validateDunning
    }

    private PaymentDunning validateIfCanBeResendToAnalysis(PaymentDunning paymentDunning, List<Long> temporaryFileIdList) {
        PaymentDunning validateDunning = new PaymentDunning()

        if (!paymentDunning.status.isDenied()) {
            DomainUtils.addError(validateDunning, "Não é possível ativar uma negativação que não esteja com a situação Precisa de Atenção.")
        }

        if (!temporaryFileIdList) {
            DomainUtils.addError(validateDunning, "É necessário enviar os documentos.")
        }

        return validateDunning
    }

    private void setAsAwaitingApproval(PaymentDunning paymentDunning) {
        paymentDunning.status = PaymentDunningStatus.AWAITING_APPROVAL
        paymentDunning.save(failOnError: true)
    }
}
