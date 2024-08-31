package com.asaas.service.pix

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.authorizationrequest.AuthorizationRequestActionType
import com.asaas.authorizationrequest.AuthorizationRequestType
import com.asaas.billinginfo.BillingType
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReasonObject
import com.asaas.checkoutRiskAnalysis.adapter.CheckoutRiskAnalysisInfoAdapter
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequestReason
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitConsumation
import com.asaas.domain.pix.PixTransactionDestinationKey
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.domain.transfer.Transfer
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.pix.adapter.transaction.BaseRefundAdapter
import com.asaas.pix.adapter.transaction.bacen.PixTransactionBacenAdapter
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.pix.adapter.transaction.credit.CreditRefundAdapter
import com.asaas.pix.adapter.transaction.debitrefund.DebitRefundAdapter
import com.asaas.pix.adapter.transaction.refundrequest.base.BaseRefundRequestRefundTransactionAdapter
import com.asaas.pix.vo.transaction.PixAddressKeyDebitVO
import com.asaas.pix.vo.transaction.PixDebitVO
import com.asaas.pix.vo.transaction.PixExternalDebitVO
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.transfer.TransferDestinationBankAccountValidator
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class PixTransactionService {

    def anticipationDebitOverdueSettlementAsyncActionService
    def asaasMoneyPixPaymentService
    def asaasMoneyService
    def asaasMoneyTransactionInfoService
    def authorizationRequestService
    def chargedFeeService
    def checkoutNotificationService
    def checkoutRiskAnalysisService
    def customerAdminService
    def customerExternalAuthorizationRequestConfigService
    def customerExternalAuthorizationRequestCreateService
    def customerStageService
    def financialTransactionService
    def lastCheckoutInfoService
    def paymentConfirmService
    def paymentRefundService
    def pixAsyncInstantPaymentAccountBalanceValidationService
    def pixTransactionDestinationKeyService
    def pixTransactionExternalAccountService
    def pixTransactionExternalQrCodeInfoService
    def pixTransactionExternalReferenceService
    def pixTransactionFeeService
    def pixTransactionManagerService
    def pixTransactionNotificationService
    def pixTransactionRecurringScheduleService
    def pixTransactionRefundService
    def pushNotificationRequestPixEventService
    def transactionReceiptService
    def transferDestinationBankAccountRestrictionService
    def transferService

    public PixTransaction saveCredit(CreditAdapter creditAdapter) {
        PixTransaction transaction = new PixTransaction()
        transaction.customer = creditAdapter.customer
        transaction.type = PixTransactionType.CREDIT
        transaction.value = creditAdapter.value
        transaction.conciliationIdentifier = PixUtils.sanitizeHtml(creditAdapter.conciliationIdentifier)
        transaction.externalIdentifier = creditAdapter.externalIdentifier
        transaction.message = PixUtils.sanitizeHtml(creditAdapter.message)
        transaction.originType = creditAdapter.originType
        transaction.payment = creditAdapter.payment
        transaction.receivedWithAsaasQrCode = creditAdapter.receivedWithAsaasQrCode
        transaction.endToEndIdentifier = creditAdapter.endToEndIdentifier
        transaction.publicId = UUID.randomUUID()
        transaction.status = PixTransactionStatus.AWAITING_REQUEST
        transaction.save(flush: true, failOnError: true)

        if (creditAdapter.pixKey) pixTransactionDestinationKeyService.save(transaction, creditAdapter.pixKey, creditAdapter.pixAddressKeyType)
        if (creditAdapter.externalReference) pixTransactionExternalReferenceService.save(transaction, creditAdapter.externalReference)
        transaction.externalAccount = pixTransactionExternalAccountService.saveFromCredit(transaction, creditAdapter)

        transaction = executeCredit(transaction)

        customerStageService.processCashInReceived(transaction.customer)

        return transaction
    }

    public PixTransaction save(Customer customer, PixDebitVO pixDebitVO) {
        Boolean shouldChargeDebitFee = pixTransactionFeeService.shouldChargeDebitFee(customer, pixDebitVO)

        PixTransaction transaction = new PixTransaction()
        transaction.customer = customer
        transaction.type = pixDebitVO.type
        transaction.value = pixDebitVO.value
        transaction.message = PixUtils.sanitizeHtml(pixDebitVO.message)
        transaction.originType = pixDebitVO.originType
        if (pixDebitVO instanceof PixExternalDebitVO) transaction.initiatedByExternalInstitution = pixDebitVO.initiatedByExternalInstitution

        if (pixDebitVO.originType.isQrCode()) {
            transaction.conciliationIdentifier = pixDebitVO.conciliationIdentifier
            if (pixDebitVO.cashValueFinality) {
                transaction.cashValue = pixDebitVO.cashValue
                transaction.cashValueFinality = pixDebitVO.cashValueFinality
            }
        } else if (pixDebitVO.originType.isPaymentInitiationService()) {
            transaction.conciliationIdentifier = pixDebitVO.conciliationIdentifier
        }

        if (pixDebitVO.isScheduledTransaction) transaction.scheduledDate = pixDebitVO.scheduledDate.clearTime()
        if (pixDebitVO.endToEndIdentifier) transaction.endToEndIdentifier = pixDebitVO.endToEndIdentifier
        transaction.status = buildSavePixTransactionStatus(customer, pixDebitVO)
        transaction.publicId = UUID.randomUUID()
        transaction.save(flush: true, failOnError: true)

        Boolean shouldSaveAsaasMoneyTransactionInfo = pixDebitVO.originType.isQrCode() && asaasMoneyService.isAsaasMoneyRequest()
        if (shouldSaveAsaasMoneyTransactionInfo) asaasMoneyTransactionInfoService.save(customer, transaction, pixDebitVO.pixAsaasMoneyInfo ? pixDebitVO.pixAsaasMoneyInfo.properties : [:])

        if (pixDebitVO.originType.isAddresKey()) pixTransactionDestinationKeyService.save(transaction, pixDebitVO.externalAccount.pixKey, pixDebitVO.externalAccount.pixKeyType)

        transaction.externalAccount = pixTransactionExternalAccountService.save(transaction, pixDebitVO.externalAccount)

        Boolean shouldSaveExternalQrCodeInfo = (transaction.type.isDebit() && transaction.originType.isQrCode())
        if (shouldSaveExternalQrCodeInfo) transaction.externalQrCodeInfo = pixTransactionExternalQrCodeInfoService.save(transaction, pixDebitVO.externalQrCodeInfo)

        if (pixDebitVO.pixTransactionBankAccountInfoCheckoutLimit) saveBankAccountInfoCheckoutLimitConsumation(transaction, pixDebitVO.pixTransactionBankAccountInfoCheckoutLimit)

        if (transaction.isCompromised()) createDebitFinancialTransaction(transaction, shouldChargeDebitFee)

        Transfer transfer = transferService.save(transaction)

        saveCheckoutRiskAnalysisRequestIfNecessary(transaction)

        BusinessValidation businessValidation = TransferDestinationBankAccountValidator.validateRestrictionIfNecessary(customer, transfer.destinationBankAccount.cpfCnpj)
        if (!businessValidation.isValid()) {
            refuse(transaction, PixTransactionRefusalReason.RESTRICTED_EXTERNAL_ACCOUNT, "Conta de destino inválida", transaction.endToEndIdentifier)
            transferDestinationBankAccountRestrictionService.onTransferFailedByRestrictedDestination(transfer)
            return transaction
        }

        if (transaction.status.isAwaitingExternalAuthorization()) {
            saveExternalAuthorization(transfer, pixDebitVO.originType)
        } else if (transaction.status.isAwaitingCriticalActionAuthorization()) {
            CriticalAction.savePixTransaction(transaction)
        }

        if (pixDebitVO.checkoutNotificationVO) {
            checkoutNotificationService.saveConfig(transaction, pixDebitVO.checkoutNotificationVO)
        }

        Boolean isRecurring = pixDebitVO.retrieveRecurringSchedule() != null
        if (isRecurring) pixTransactionRecurringScheduleService.save(pixDebitVO)

        return transaction
    }

    public PixTransaction refundDebit(PixTransaction refundedTransaction, DebitRefundAdapter debitRefundAdapter) {
        PixTransaction refund = buildRefund(refundedTransaction, debitRefundAdapter)
        refund.status = PixTransactionStatus.AWAITING_REQUEST
        refund.endToEndIdentifier = debitRefundAdapter.refundEndToEndIdentifier
        refund.externalIdentifier = debitRefundAdapter.externalIdentifier
        refund.save(flush: true, failOnError: true)

        pixTransactionRefundService.save(refundedTransaction, refund, debitRefundAdapter.reason, debitRefundAdapter.reasonDescription)
        updateRefundedValue(refundedTransaction)

        refund = executeDebitRefund(refund, new Date())

        asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(refund)
        asaasMoneyPixPaymentService.createStatusChangeAsyncAction(refund)

        return refund
    }

    public PixTransaction refundCredit(PixTransaction refundedTransaction, CreditRefundAdapter creditRefundAdapter) {
        PixTransaction refund = buildRefund(refundedTransaction, creditRefundAdapter)
        refund.status = buildSaveCreditRefundStatus(refundedTransaction, creditRefundAdapter)
        refund.receivedWithAsaasQrCode = refundedTransaction.receivedWithAsaasQrCode
        refund.payment = refundedTransaction.payment
        refund.save(flush: true, failOnError: true)

        PixTransactionRefund transactionRefund = pixTransactionRefundService.save(refundedTransaction, refund, creditRefundAdapter.reason, creditRefundAdapter.reasonDescription)

        updateRefundedValue(refundedTransaction)

        if (refund.isCompromised()) {
            lastCheckoutInfoService.save(refund.customer)
            if (!transactionRefund.refundedTransaction.payment.asBoolean()) financialTransactionService.refundPixTransactionCredit(refund)
        }
        transferService.save(refund)

        if (refund.status.isAwaitingCriticalActionAuthorization()) CriticalAction.savePixRefundCredit(refund)

        return refund
    }

    public PixTransaction cancelDebitRefund(BaseRefundRequestRefundTransactionAdapter cancelDebitRefundAdapter, BigDecimal refundValue) {
        PixTransaction pixTransaction = new PixTransaction()
        pixTransaction.customer = cancelDebitRefundAdapter.customer
        pixTransaction.type = PixTransactionType.DEBIT_REFUND_CANCELLATION
        pixTransaction.value = BigDecimalUtils.negate(refundValue)
        pixTransaction.originType = PixTransactionOriginType.MANUAL
        pixTransaction.status = PixTransactionStatus.AWAITING_REQUEST
        pixTransaction.publicId = UUID.randomUUID()
        pixTransaction.save(flush: true, failOnError: true)

        PixTransaction refundedTransaction = cancelDebitRefundAdapter.pixTransaction.getRefundedTransaction()
        pixTransaction.externalAccount = pixTransactionExternalAccountService.saveFromPixTransactionExternalAccount(pixTransaction, refundedTransaction.externalAccount)

        PixTransactionDestinationKey pixTransactionDestinationKey = refundedTransaction.getDestinationKeyIfPossible()
        if (pixTransactionDestinationKey) pixTransactionDestinationKeyService.save(pixTransaction, pixTransactionDestinationKey.pixKey, pixTransactionDestinationKey.pixAddressKeyType)

        final Boolean shouldChargeDebitFee = false
        createDebitFinancialTransaction(pixTransaction, shouldChargeDebitFee)

        pixTransactionRefundService.save(cancelDebitRefundAdapter.pixTransaction, pixTransaction, cancelDebitRefundAdapter.pixTransactionRefundReason, null)

        return pixTransaction
    }

    public PixTransaction setAsRequested(PixTransaction transaction, String externalIdentifier, String endToEndIdentifier) {
        if (externalIdentifier) transaction.externalIdentifier = externalIdentifier
        if (endToEndIdentifier) transaction.endToEndIdentifier = endToEndIdentifier
        transaction.status = PixTransactionStatus.REQUESTED
        transaction.save(failOnError: true)
        return transaction
    }

    public PixTransaction setAsError(PixTransaction pixTransaction) {
        pixTransaction.status = PixTransactionStatus.ERROR
        pixTransaction.save(failOnError: true)

        asaasMoneyPixPaymentService.createStatusChangeAsyncAction(pixTransaction)

        return pixTransaction
    }

    public PixTransaction setAsAwaitingRequest(PixTransaction pixTransaction) {
        pixTransaction.status = PixTransactionStatus.AWAITING_REQUEST
        pixTransaction.save(failOnError: true)

        return pixTransaction
    }

    public PixTransaction setAsAwaitingExternalAuthorization(PixTransaction pixTransaction) {
        pixTransaction.status = PixTransactionStatus.AWAITING_EXTERNAL_AUTHORIZATION
        pixTransaction.save(failOnError: true)

        Transfer transfer = Transfer.query([pixTransaction: pixTransaction]).get()
        saveExternalAuthorization(transfer, pixTransaction.originType)

        return pixTransaction
    }

    public PixTransaction setAsAwaitingCriticalActionAuthorization(PixTransaction pixTransaction) {
        pixTransaction.status = PixTransactionStatus.AWAITING_CRITICAL_ACTION_AUTHORIZATION
        pixTransaction.save(failOnError: true)

        return pixTransaction
    }

    public PixTransaction awaitInstantPaymentAccountBalance(PixTransaction pixTransaction) {
        pixTransaction.status = PixTransactionStatus.AWAITING_INSTANT_PAYMENT_ACCOUNT_BALANCE
        pixTransaction.save(failOnError: true)

        pixAsyncInstantPaymentAccountBalanceValidationService.saveIfNecessary(pixTransaction)

        return pixTransaction
    }

    public PixTransaction awaitCashInRiskAnalysis(PixTransaction pixTransaction) {
        pixTransaction.status = PixTransactionStatus.AWAITING_CASH_IN_RISK_ANALYSIS_REQUEST
        pixTransaction.save(failOnError: true)

        pixTransactionNotificationService.sendAwaitingCashInRiskAnalysisNotification(pixTransaction)

        return pixTransaction
    }

    public PixTransaction setAsRefused(PixTransaction pixTransaction, PixTransactionRefusalReason refusalReason, String refusalReasonDescription, String endToEndIdentifier) {
        if (endToEndIdentifier) pixTransaction.endToEndIdentifier = endToEndIdentifier
        pixTransaction.status = PixTransactionStatus.REFUSED
        pixTransaction.refusalReason = refusalReason
        pixTransaction.refusalReasonDescription = refusalReasonDescription
        pixTransaction.save(flush: true, failOnError: true)

        transferService.updateStatusIfNecessary(pixTransaction)

        asaasMoneyPixPaymentService.createStatusChangeAsyncAction(pixTransaction)

        return pixTransaction
    }

    public PixTransaction refuse(PixTransaction pixTransaction, PixTransactionRefusalReason refusalReason, String refusalReasonDescription, String endToEndIdentifier) {
        BusinessValidation businessValidation = validateRefuse(pixTransaction)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        Boolean isCompromisedBeforeSetAsRefused = pixTransaction.isCompromised()
        setAsRefused(pixTransaction, refusalReason, refusalReasonDescription, endToEndIdentifier)

        PixTransaction refundedTransaction = pixTransaction.getRefundedTransaction()
        if (refundedTransaction) updateRefundedValue(refundedTransaction)

        switch (pixTransaction.type) {
            case PixTransactionType.CREDIT_REFUND:
                if (pixTransaction.payment.asBoolean()) {
                    PaymentRefund paymentRefund = PaymentRefund.query([pixTransaction: pixTransaction]).get()
                    if (paymentRefund) {
                        Boolean isCashInRiskAnalysisRefund = CashInRiskAnalysisRequestReason.query([exists: true, pixTransaction: refundedTransaction]).get().asBoolean()
                        if (isCashInRiskAnalysisRefund) AsaasLogger.error("PixTransactionService.refuse() -> Estorno de crédito referente a bloqueio cautelar foi recusado. [refundedTransaction.id: ${refundedTransaction.id}, payment.id: ${pixTransaction.payment.id}]")

                        paymentRefundService.cancel(paymentRefund)
                    } else {
                        paymentRefundService.cancel(pixTransaction.payment)
                    }

                    pixTransactionNotificationService.sendCreditRefundRefusedNotification(pixTransaction, refusalReason)
                    executeCreditForFailedRefundOnPrecautionaryBlockIfNecessary(refundedTransaction, pixTransaction)
                } else {
                    financialTransactionService.cancelPixTransactionCreditRefund(pixTransaction)
                    pushNotificationRequestPixEventService.saveTransaction(pixTransaction, PushNotificationRequestEvent.PIX_CREDIT_REFUND_REFUSED)
                    pixTransactionNotificationService.sendCreditRefundRefusedNotification(pixTransaction, refusalReason)
                }
                break
            case [PixTransactionType.DEBIT, PixTransactionType.DEBIT_REFUND_CANCELLATION]:
                if (isCompromisedBeforeSetAsRefused) refundDebitAndFeeFinancialTransaction(pixTransaction)
                pixTransactionNotificationService.sendDebitRefusedNotification(pixTransaction, refusalReason)
                break
            default:
                throw new BusinessException("Cancelamento não habilitado para o tipo de transação.")
        }

        return pixTransaction
    }

    public BusinessValidation validateRefuse(PixTransaction pixTransaction) {
        BusinessValidation validatedPixTransaction = new BusinessValidation()

        if (!PixTransactionStatus.getRefusableList().contains(pixTransaction.status)) {
            validatedPixTransaction.addError("pixTransaction.error.cannotBeRefused")
        }

        return validatedPixTransaction
    }

    public void executeCreditForFailedRefundOnPrecautionaryBlockIfNecessary(PixTransaction pixTransactionOrigin, PixTransaction pixTransactionRefund) {
        if (!pixTransactionOrigin.payment) return
        if (!pixTransactionOrigin.payment.status.isConfirmed()) return

        Boolean isRequestedByReceiver = PixTransactionRefund.query([exists: true, transaction: pixTransactionRefund, reason: PixTransactionRefundReason.REQUESTED_BY_RECEIVER]).get().asBoolean()
        if (isRequestedByReceiver) return

        Boolean isCashInRiskAnalysisRequestReason = CashInRiskAnalysisRequestReason.query([exists: true, pixTransaction: pixTransactionOrigin]).get().asBoolean()
        if (!isCashInRiskAnalysisRequestReason) return

        paymentConfirmService.executePaymentCredit(pixTransactionOrigin.payment)
        pixTransactionOrigin = executeRoutinesPostCreditReceived(pixTransactionOrigin)
        if (pixTransactionOrigin.hasErrors()) throw new RuntimeException("Falha ao liquidar transação após não ser possível estornar pelo bloqueio cautelar [pixTransactionOriginId: ${pixTransactionOrigin.id}, pixTransactionRefundId: ${pixTransactionRefund.id}, paymentId: ${pixTransactionOrigin.payment.id}]")

        AsaasLogger.info("PixTransactionService.executeCreditForFailedRefundOnPrecautionaryBlockIfNecessary >> Transação Pix suspeita de fraude liquidada para o cliente por falha na devolução via Bloqueio Cautelar [pixTransactionOriginId: ${pixTransactionOrigin.id}, pixTransactionRefundId: ${pixTransactionRefund.id}, customerId: ${pixTransactionOrigin.customerId}, paymentId: ${pixTransactionOrigin.payment.id}]")

        String disableCheckoutReason = "Identificamos uma transação suspeita, resultando no bloqueio do saque. Devido a limitações no processo de estorno, solicitamos que entre em contato para regularizar sua conta."
        customerAdminService.disableCheckout(pixTransactionOrigin.customer, disableCheckoutReason)
    }

    public void createDebitFinancialTransaction(PixTransaction transaction, Boolean shouldChargeDebitFee) {
        lastCheckoutInfoService.save(transaction.customer)

        asaasMoneyTransactionInfoService.setAsPaidIfNecessary(transaction)

        String financialDescription = PixUtils.buildDebitFinancialDescription(transaction.originType, transaction.initiatedByExternalInstitution, transaction.cashValueFinality, transaction.externalAccount.name)
        financialTransactionService.savePixTransactionDebit(transaction, financialDescription)

        if (shouldChargeDebitFee) chargedFeeService.savePixTransactionDebitFee(transaction)

        asaasMoneyTransactionInfoService.creditAsaasMoneyCashbackIfNecessary(transaction)
    }

    public void createDebitFinancialTransactionListForAsyncCheckout(Customer customer, List<PixTransaction> pixTransactionList) {
        lastCheckoutInfoService.save(customer)

        financialTransactionService.savePixTransactionDebitList(customer, pixTransactionList)

        for (PixTransaction pixTransaction : pixTransactionList) {
            chargedFeeService.savePixTransactionDebitFee(pixTransaction)
        }
    }

    public PixTransaction cancel(id, Customer customer) {
        return cancel(PixTransaction.find(id, customer))
    }

    public PixTransaction cancel(PixTransaction pixTransaction) {
        BusinessValidation validatedBusiness = pixTransaction.canBeCancelled()
        if (!validatedBusiness.isValid()) return DomainUtils.addError(pixTransaction, validatedBusiness.getFirstErrorMessage())

        if (pixTransaction.status.isAwaitingCriticalActionAuthorization()) CriticalAction.deleteNotAuthorized(pixTransaction)

        Boolean valueDebited = pixTransaction.isCompromised()

        pixTransaction.status = PixTransactionStatus.CANCELLED
        pixTransaction.save(failOnError: true)

        transferService.updateStatusIfNecessary(pixTransaction)

        if (pixTransaction.type.isDebit()) {
            if (valueDebited) refundDebitAndFeeFinancialTransaction(pixTransaction)
        } else if (pixTransaction.type.isCreditRefund()) {
            if (pixTransaction.payment) {
                PaymentRefund paymentRefund = PaymentRefund.query([pixTransaction: pixTransaction]).get()
                paymentRefundService.cancel(paymentRefund)
            } else {
                financialTransactionService.cancelPixTransactionCreditRefund(pixTransaction)
            }
            updateRefundedValue(pixTransaction.getRefundedTransaction())
        }

        asaasMoneyPixPaymentService.createStatusChangeAsyncAction(pixTransaction)

        return pixTransaction
    }

    public PixTransaction onDebitAuthorization(PixTransaction pixTransaction) {
        if (!pixTransaction.type.isDebit()) throw new RuntimeException("${this.getClass().getSimpleName()}.onDebitAuthorization > Apenas transações de débito podem passar por autorização crítica [pixTransactionId: ${pixTransaction.id}].")
        if (!pixTransaction.status.isAwaitingAuthorization()) throw new RuntimeException("${this.getClass().getSimpleName()}.onDebitAuthorization > Transação [${pixTransaction.id}] não está aguardando autorização.")

        if (needsCheckoutRiskAnalysisRequestOnDebitAuthorization(pixTransaction)) {
            pixTransaction.status = PixTransactionStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST
            saveCheckoutRiskAnalysisRequestIfNecessary(pixTransaction)
        } else {
            pixTransaction.status = pixTransaction.scheduledDate ? PixTransactionStatus.SCHEDULED : PixTransactionStatus.AWAITING_REQUEST
        }
        return pixTransaction.save(failOnError: true)
    }

    public PixTransactionBacenAdapter findInBacen(String endToEndIdentifier) {
        return pixTransactionManagerService.findInBacen(endToEndIdentifier)
    }

    public Boolean shouldSetAsWaitingExternalAuthorization(Customer customer, PixTransactionOriginType originType) {
        if (originType.isQrCode()) {
            return customerExternalAuthorizationRequestConfigService.hasPixQrCodeConfigEnabled(customer)
        }
        return customerExternalAuthorizationRequestConfigService.hasTransferConfigEnabled(customer)
    }

    public Date calculateDefaultMaximumSingleScheduledDate() {
        return CustomDateUtils.sumDays(new Date(), PixTransaction.SCHEDULING_LIMIT_DAYS)
    }

    public PixTransaction effectivate(PixTransaction transaction, Date effectivateDate, String endToEndIdentifier) {
        if (!transaction.type.isEquivalentToDebit()) throw new RuntimeException("PixTransactionService.effectivate >> Tipo da transação não permitido para efetivação [pixTransaction.id: ${transaction.id}]")

        transaction = setAsDone(transaction, effectivateDate, endToEndIdentifier)
        if (transaction.hasErrors()) return transaction

        Boolean shouldExecutePaymentRefund = transaction.type.isCreditRefund() && transaction.payment.asBoolean()
        if (shouldExecutePaymentRefund) transaction = executePaymentRefund(transaction)

        createReceipt(transaction)
        pixTransactionNotificationService.sendConclusionNotification(transaction)

        customerStageService.processTransferConfirmed(transaction.customer)

        return transaction
    }

    public PixTransaction executeRoutinesPostCreditReceived(PixTransaction transaction) {
        transaction = setAsDone(transaction, new Date(), null)
        if (transaction.hasErrors()) return transaction

        createReceipt(transaction)
        pixTransactionNotificationService.sendConclusionNotification(transaction)

        return transaction
    }

    public void cancelAllByCustomer(Customer customer) {
        List<PixTransaction> pixTransactionList = PixTransaction.query([customer: customer, "status[in]": PixTransactionStatus.getCancellableByCustomerList()]).list()
        pixTransactionList.forEach { PixTransaction pixTransaction -> cancel(pixTransaction) }
    }

    private PixTransaction setAsDone(PixTransaction transaction, Date effectiveDate, String endToEndIdentifier) {
        BusinessValidation validatedBusiness = transaction.canBeSetToDone()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(transaction, validatedBusiness.asaasErrors[0].getMessage())
            return transaction
        }

        if (endToEndIdentifier) transaction.endToEndIdentifier = endToEndIdentifier
        transaction.status = PixTransactionStatus.DONE
        transaction.effectiveDate = effectiveDate ?: new Date()
        AuditLogListener.withoutAuditLog({
            transaction.save(flush: true, failOnError: true)
        })
        transferService.updateStatusIfNecessary(transaction)
        asaasMoneyPixPaymentService.createStatusChangeAsyncAction(transaction)

        return transaction
    }

    private PixTransaction executePaymentRefund(PixTransaction transaction) {
        if (!transaction.type.isCreditRefund()) throw new RuntimeException("PixTransactionService.executePaymentRefund >> Tipo da transação não permitido para efetivação de estorno de crédito [pixTransaction.id: ${transaction.id}]")
        if (!transaction.payment) throw new RuntimeException("PixTransactionService.executePaymentRefund >> A transação precisa estar vinculada a uma cobrança [pixTransaction.id: ${transaction.id}]")

        PaymentRefund paymentRefund = PaymentRefund.query([pixTransaction: transaction]).get()
        if (paymentRefund) {
            paymentRefundService.executeRefund(paymentRefund, false)

            PixTransaction refundedTransaction = transaction.getRefundedTransaction()
            if (refundedTransaction.status.isAwaitingCashInRiskAnalysisRequest()) {
                refundedTransaction = setAsDone(refundedTransaction, new Date(), null)
                if (refundedTransaction.hasErrors()) throw new RuntimeException("PixTransactionService.executePaymentRefund >> Não foi possível concluir a transação que estava em bloqueio cautelar após tentativa de estorno. [pixTransactionId: ${refundedTransaction.id}, error: ${refundedTransaction.errors.allErrors.first().defaultMessage}]")
            }
        } else {
            paymentRefundService.executeRefund(transaction.payment)
        }

        return transaction
    }

    private void createReceipt(PixTransaction transaction) {
        switch (transaction.type) {
            case [PixTransactionType.DEBIT, PixTransactionType.DEBIT_REFUND_CANCELLATION, PixTransactionType.DEBIT_REFUND]:
                transactionReceiptService.savePixTransactionDone(transaction)
                break
            case PixTransactionType.CREDIT:
                if (!transaction.payment) transactionReceiptService.savePixTransactionDone(transaction)
                break
            case PixTransactionType.CREDIT_REFUND:
                if (transaction.payment) {
                    PaymentRefund paymentRefund = PaymentRefund.query([pixTransaction: transaction]).get()
                    if (paymentRefund && paymentRefund.isPartialRefund()) transactionReceiptService.savePixTransactionDone(transaction)
                } else {
                    transactionReceiptService.savePixTransactionDone(transaction)
                }
        }
    }

    private PixTransactionStatus buildSavePixTransactionStatus(Customer customer, PixDebitVO pixDebitVO) {
        if (pixDebitVO.asynchronousCheckout) return PixTransactionStatus.AWAITING_BALANCE_VALIDATION
        if (asaasMoneyService.isAsaasMoneyRequest()) return PixTransactionStatus.AWAITING_REQUEST

        AuthorizationRequestActionType authorizationRequestActionType = pixDebitVO.originType.isQrCode() ? AuthorizationRequestActionType.PIX_TRANSFER_QRCODE_SAVE : AuthorizationRequestActionType.PIX_TRANSFER_SAVE
        AuthorizationRequestType authorizationRequestType = authorizationRequestService.findAuthorizationRequestType(customer, authorizationRequestActionType)

        if (shouldSetAsAwaitingCriticalAction(pixDebitVO, authorizationRequestType)) {
            return PixTransactionStatus.AWAITING_CRITICAL_ACTION_AUTHORIZATION
        } else if (shouldSetAsAwaitingExternalAuthorization(pixDebitVO, authorizationRequestType)) {
            return PixTransactionStatus.AWAITING_EXTERNAL_AUTHORIZATION
        } else if (isSuspectedOfFraud(pixDebitVO)) {
            return PixTransactionStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST
        }

        return pixDebitVO.isScheduledTransaction ? PixTransactionStatus.SCHEDULED : PixTransactionStatus.AWAITING_REQUEST
    }

    private PixTransactionStatus buildSaveCreditRefundStatus(PixTransaction transactionToRefund, CreditRefundAdapter creditRefundAdapter) {
        if (!transactionToRefund.paymentId && CustomerParameter.getValue(transactionToRefund.customerId, CustomerParameterName.PIX_ASYNC_CHECKOUT)) return PixTransactionStatus.AWAITING_BALANCE_VALIDATION

        Boolean bypassAsynchronousAuthorization = creditRefundAdapter.bypassCustomerValidation || creditRefundAdapter.authorizeSynchronous
        if (!bypassAsynchronousAuthorization) {
            CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: transactionToRefund.customer.id, readOnly: true]).get()
            if (customerCriticalActionConfig?.isPixTransactionCreditRefundAuthorizationEnabled()) return PixTransactionStatus.AWAITING_CRITICAL_ACTION_AUTHORIZATION
        }

        return PixTransactionStatus.AWAITING_REQUEST
    }

    private Boolean shouldSetAsAwaitingCriticalAction(PixDebitVO pixDebitVO, AuthorizationRequestType authorizationRequestType) {
        if (pixDebitVO instanceof PixExternalDebitVO) return false
        if (pixDebitVO.authorizeSynchronous) return false
        if (pixDebitVO instanceof PixAddressKeyDebitVO && pixDebitVO.isRecurringTransfer) return false
        if (pixDebitVO instanceof PixManualDebitVO && pixDebitVO.isRecurringTransfer) return false

        return authorizationRequestType.isCriticalAction()
    }

    private Boolean shouldSetAsAwaitingExternalAuthorization(PixDebitVO pixDebitVO, AuthorizationRequestType authorizationRequestType) {
        if (pixDebitVO instanceof PixAddressKeyDebitVO && pixDebitVO.isRecurringTransfer) return false
        if (pixDebitVO instanceof PixManualDebitVO && pixDebitVO.isRecurringTransfer) return false

        return authorizationRequestType.isExternalAuthorization()
    }

    private PixTransaction buildRefund(PixTransaction refundedTransaction, BaseRefundAdapter refundAdapter) {
        PixTransaction refund = new PixTransaction()
        refund.customer = refundedTransaction.customer
        refund.value = refundAdapter.value
        refund.type = refundAdapter.type
        refund.publicId = UUID.randomUUID()
        return refund
    }

    private void refundDebitAndFeeFinancialTransaction(PixTransaction pixTransaction) {
        if (![PixTransactionStatus.CANCELLED, PixTransactionStatus.REFUSED].contains(pixTransaction.status)) throw new RuntimeException("Transação Pix ${pixTransaction.id} não pode ser estornada no extrato")

        financialTransactionService.refundPixTransactionDebit(pixTransaction)
        chargedFeeService.refundPixTransactionDebitFeeIfNecessary(pixTransaction)
    }

    private Boolean isSuspectedOfFraud(PixDebitVO pixDebitVO) {
        if (!pixDebitVO.type.isDebit()) return false

        CheckoutRiskAnalysisInfoAdapter checkoutInfoAdapter = new CheckoutRiskAnalysisInfoAdapter(pixDebitVO)
        Boolean isSuspectedOfFraud = checkoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject.PIX_TRANSACTION, checkoutInfoAdapter)

        return isSuspectedOfFraud
    }

    private PixTransaction executeCredit(PixTransaction transaction) {
        if (transaction.payment) {
            transaction.payment.lock()
            transaction.payment = paymentConfirmService.confirmPayment(transaction.payment, transaction.value, transaction.dateCreated, BillingType.PIX)

           if (transaction.payment.status.isConfirmed()) return transaction
        } else {
            financialTransactionService.savePixTransactionCredit(transaction)

            Boolean shouldChargeCreditFee = pixTransactionFeeService.shouldChargeCreditFee(transaction.customer, transaction.originType, transaction.externalAccount.cpfCnpj)
            if (shouldChargeCreditFee) chargedFeeService.savePixTransactionCreditFee(transaction)

            anticipationDebitOverdueSettlementAsyncActionService.saveIfNecessary(transaction.customer.id, [pixTransactionId: transaction.id])
        }

        transaction = executeRoutinesPostCreditReceived(transaction)

        return transaction
    }

    private PixTransaction executeDebitRefund(PixTransaction transaction, Date effectivateDate) {
        transaction = setAsDone(transaction, effectivateDate, null)

        if (transaction.hasErrors()) return transaction

        financialTransactionService.refundPixTransactionDebit(transaction)

        createReceipt(transaction)
        pixTransactionNotificationService.sendConclusionNotification(transaction)

        return transaction
    }

    private void updateRefundedValue(PixTransaction refundedTransaction) {
        BigDecimal refundedValue = PixTransactionRefund.calculateRefundedValue(refundedTransaction)
        if (refundedValue > refundedTransaction.value.abs()) throw new BusinessException("O valor estornado é superior ao valor do crédito.")

        refundedTransaction.refundedValue = refundedValue
        refundedTransaction.save(failOnError: true)
    }

    private PixTransactionBankAccountInfoCheckoutLimitConsumation saveBankAccountInfoCheckoutLimitConsumation(PixTransaction transaction, PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit) {
        PixTransactionBankAccountInfoCheckoutLimitConsumation pixTransactionBankAccountInfoCheckoutLimitConsumation = new PixTransactionBankAccountInfoCheckoutLimitConsumation()
        pixTransactionBankAccountInfoCheckoutLimitConsumation.customer = transaction.customer
        pixTransactionBankAccountInfoCheckoutLimitConsumation.pixTransaction = transaction
        pixTransactionBankAccountInfoCheckoutLimitConsumation.pixTransactionBankAccountInfoCheckoutLimit = pixTransactionBankAccountInfoCheckoutLimit
        return pixTransactionBankAccountInfoCheckoutLimitConsumation.save(failOnError: true)
    }

    private void saveCheckoutRiskAnalysisRequestIfNecessary(PixTransaction pixTransaction) {
        if (!pixTransaction.status.isAwaitingCheckoutRiskAnalysisRequest()) return

        AsaasApplicationHolder.applicationContext.checkoutRiskAnalysisRequestService.save(pixTransaction.customer, pixTransaction)
    }

    private Boolean needsCheckoutRiskAnalysisRequestOnDebitAuthorization(PixTransaction pixTransaction) {
        if (pixTransaction.status.isAwaitingCheckoutRiskAnalysisRequest()) return false

        CheckoutRiskAnalysisInfoAdapter checkoutRiskAnalysisInfoAdapter = new CheckoutRiskAnalysisInfoAdapter(pixTransaction)
        Boolean isSuspectedOfFraud = checkoutRiskAnalysisService.checkIfCheckoutIsSuspectedOfFraud(CheckoutRiskAnalysisReasonObject.PIX_TRANSACTION, checkoutRiskAnalysisInfoAdapter)

        return isSuspectedOfFraud
    }

    private void saveExternalAuthorization(Transfer transfer, PixTransactionOriginType originType) {
        if (originType.isQrCode()) {
            customerExternalAuthorizationRequestCreateService.saveForPixQrCode(transfer.pixTransaction)
        } else {
            customerExternalAuthorizationRequestCreateService.saveForTransfer(transfer)
        }
    }
}
