package com.asaas.service.receivableanticipation

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerParameterName
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.installment.Installment
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceItem
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationAgreement
import com.asaas.domain.receivableanticipation.ReceivableAnticipationCompromisedItem
import com.asaas.domain.receivableanticipation.ReceivableAnticipationDocument
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfoMethod
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.payment.PaymentStatus
import com.asaas.paymentinfo.PaymentAnticipableInfoStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.PaymentFeeChargedByAnticipationVO
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationDenialReason
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentVO
import com.asaas.receivableanticipation.ReceivableAnticipationFinancialInfoVO
import com.asaas.receivableanticipation.ReceivableAnticipationFloatCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationSchedulingValidator
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.UserKnownDeviceUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationService {

    def anticipationDebitOverdueSettlementAsyncActionService
    def asaasSegmentioService
    def asyncActionService
    def beamerService
    def cercContractualEffectService
    def cercFidcContractualEffectService
    def customerAlertNotificationService
    def customerInteractionService
    def customerReceivableAnticipationConfigService
    def financialTransactionService
    def hubspotEventService
    def internalLoanService
    def mandatoryCustomerAccountNotificationService
    def mobilePushNotificationService
    def oceanManagerService
    def originRequesterInfoService
    def paymentAnticipableInfoAsyncService
    def paymentAnticipableInfoService
    def paymentDiscountConfigService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def paymentSplitService
    def promotionalCodeService
    def pushNotificationRequestReceivableAnticipationService
    def receivableAnticipationAnalysisService
    def receivableAnticipationCompromisedItemService
    def receivableAnticipationCustomerMessageService
    def receivableAnticipationDocumentAdminService
    def receivableAnticipationDocumentService
    def receivableAnticipationFinancialInfoService
    def receivableAnticipationHistoryService
    def receivableAnticipationItemService
    def receivableAnticipationPartnerAcquisitionService
    def receivableAnticipationPartnerConfigService
    def receivableAnticipationValidationCacheService
    def receivableAnticipationValidationService
    def treasureDataService

    public void denyAnticipationsWhereCustomerNotInWhitelist() {
        final Integer maxItemsPerCycle = 100
        final Integer businessDaysToDeny = 5

        Map search = [
            "column": "id",
            "billingType": BillingType.MUNDIPAGG_CIELO,
            "readyForAnalysisDate[le]": CustomDateUtils.subtractBusinessDays(new Date(), businessDaysToDeny).clearTime(),
            "statusList": [ReceivableAnticipationStatus.AWAITING_AUTOMATIC_APPROVAL, ReceivableAnticipationStatus.APPROVED],
            "zAnticipationWhitelist[notExists]": true
        ]

        List<Long> anticipationIdList = ReceivableAnticipation.query(search).list(max: maxItemsPerCycle)
        if (!anticipationIdList) return

        ReceivableAnticipationDenialReason denialReason = ReceivableAnticipationDenialReason.ANALYSIS_NOT_APPROVED
        String observation = denialReason.getLabel()

        Utils.forEachWithFlushSession(anticipationIdList, 10, { Long anticipationId ->
            Utils.withNewTransactionAndRollbackOnError({
                deny(anticipationId, observation, denialReason)
            }, [logErrorMessage: "ReceivableAnticipationService.denyAnticipationsWhereCustomerNotInWhitelist >> Erro ao negar antecipação de payment ou installment gerado pelo link de pagamento [${anticipationId}]"])
        })
    }

    public Map simulateAnticipation(Installment installment, Payment payment, Map options) {
        CreateReceivableAnticipationAdapter adapter
        if (payment) {
            Boolean schedule = ReceivableAnticipationSchedulingValidator.paymentIsScheduled(payment, options.scheduleDaysAfterConfirmation)
            adapter = CreateReceivableAnticipationAdapter.buildBySimulatePayment(payment, options.scheduleDaysAfterConfirmation, schedule)
        } else {
            List<Payment> paymentList = getToAnticipatePaymentsFromInstallment(installment, true)
            Boolean schedule = ReceivableAnticipationSchedulingValidator.installmentIsScheduled(paymentList, options.scheduleDaysAfterConfirmation, false)

            adapter = CreateReceivableAnticipationAdapter.buildBySimulateInstallment(installment, options.scheduleDaysAfterConfirmation, schedule)
        }

        BigDecimal valueToBeAnticipated = getValueToBeAnticipated(adapter.payment, adapter.installment, adapter.schedule)
        adapter.partner = receivableAnticipationPartnerConfigService.getReceivableAnticipationPartner(adapter.customer, adapter.chargeType, adapter.customerAccount.cpfCnpj, adapter.billingType, valueToBeAnticipated)

        return buildAnticipationPropertiesMap(adapter)
    }

    public BigDecimal calculateReceivableAnticipationValue(List<Payment> paymentList) {
        return paymentList.sum { calculateReceivableAnticipationValue(it) }
    }

    public BigDecimal calculateReceivableAnticipationValue(Payment payment) {
        Map paymentValueWithDiscount = paymentDiscountConfigService.calculatePaymentDiscountInfo(payment)
        BigDecimal discountValue = paymentValueWithDiscount?.discountValue ?: 0
        BigDecimal anticipationValue = payment.netValue - discountValue - payment.getAlreadyRefundedValue()

        if (anticipationValue < 0) return 0

        return anticipationValue
    }

    public ReceivableAnticipation save(CreateReceivableAnticipationAdapter adapter) {
        String receivableAnticipationDisabledReason = ReceivableAnticipationValidationClosures.anticipationIsEnabledWithoutCache(adapter.customer, adapter.payment, adapter.installment)?.message
        if (receivableAnticipationDisabledReason) throw new BusinessException(getMessageDisabledReason(adapter.payment, adapter.installment, receivableAnticipationDisabledReason))

        ReceivableAnticipation validatedAnticipation = validateDocumentList(adapter)
        if (validatedAnticipation.hasErrors()) return validatedAnticipation

        adapter.schedule = ReceivableAnticipationSchedulingValidator.isSchedule(adapter.payment, adapter.installment, adapter.scheduleDaysAfterConfirmation)

        BigDecimal valueToBeAnticipated = getValueToBeAnticipated(adapter.payment, adapter.installment, adapter.schedule)
        adapter.partner = receivableAnticipationPartnerConfigService.getReceivableAnticipationPartner(adapter.customer, adapter.chargeType, adapter.customerAccount.cpfCnpj, adapter.billingType, valueToBeAnticipated)

        BigDecimal customerInitialAvailableAnticipationLimit

        if (adapter.billingType.isCreditCard()) {
            customerInitialAvailableAnticipationLimit = customerReceivableAnticipationConfigService.calculateCreditCardAvailableLimit(adapter.customer)
        } else if (adapter.billingType.isBoletoOrPix()) {
            customerInitialAvailableAnticipationLimit = customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(adapter.customer)
        } else {
            throw new RuntimeException("Não existe limite de antecipação para a forma de pagamento informada")
        }

        ReceivableAnticipation anticipation = build(adapter)
        anticipation.save(flush: true, failOnError: true)

        List<Payment> paymentList = (adapter.installment) ? getToAnticipatePaymentsFromInstallment(adapter.installment, adapter.schedule) : [adapter.payment]
        receivableAnticipationItemService.saveAllItems(anticipation, paymentList, adapter.partner)

        anticipation.partnerAcquisition = receivableAnticipationPartnerAcquisitionService.save(anticipation, adapter.partner)
        anticipation.save(flush: true, failOnError: true)

        if (anticipation.status.isPending() && adapter.needsToAttachAsaasIssuedInvoice) {
            updateStatus(anticipation, ReceivableAnticipationStatus.AWAITING_AUTOMATIC_ATTACHMENT_ASAAS_ISSUED_INVOICE, null)
        } else if (anticipation.status.isPending()) {
            updateStatusToPending(anticipation, null)
        } else {
            receivableAnticipationHistoryService.save(anticipation)
        }

        List<ReceivableAnticipationCompromisedItem> receivableAnticipationCompromisedItemList = receivableAnticipationCompromisedItemService.save(anticipation)

        saveOriginRequesterInfo(anticipation, adapter.method, adapter.eventOrigin)

        asaasSegmentioService.track(anticipation.customer.id, "Logged :: Adiantamento :: Solicitação confirmada", buildTrackingMap(anticipation))

        autoApproveCreditCardAnticipationIfEnabled(anticipation)

        BigDecimal customerRefreshedAvailableAnticipationLimit = customerInitialAvailableAnticipationLimit - receivableAnticipationCompromisedItemList*.value.sum()
        receivableAnticipationValidationService.updateAsNotAnticipableOnLimitDecrease(customerRefreshedAvailableAnticipationLimit, adapter.customer, anticipation.billingType, adapter.customerAccount)

        sendPushNotificationOnCreate(anticipation)

        receivableAnticipationDocumentService.saveDocuments(adapter.customer, anticipation, adapter.documentVOList)

        receivableAnticipationValidationCacheService.evictHasActiveReceivableAnticipation(adapter.customerAccount.id)

        return anticipation
    }

    public ReceivableAnticipation updateToAwaitingPartnerCredit(ReceivableAnticipation anticipation) {
        recalculateFee(anticipation, [execute: true])

        if (!anticipation.hasPromotionalCodeUse()) consumePromotionalCode(anticipation)

        ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisition.query([receivableAnticipation: anticipation]).get()
        receivableAnticipationPartnerAcquisitionService.approve(partnerAcquisition)

        updateStatus(anticipation, ReceivableAnticipationStatus.AWAITING_PARTNER_CREDIT, null)

        return anticipation
    }

    public ReceivableAnticipation approve(Long anticipationId, String observation) {
        ReceivableAnticipation anticipation = ReceivableAnticipation.get(anticipationId)
        if (!anticipation.canBeApproved()) throw new RuntimeException("Não é possível aprovar esta antecipação.")

        if (anticipation.billingType.isBoletoOrPix()) {
            receivableAnticipationDocumentAdminService.validateDocumentListInfo(anticipation)
        }

        updateStatusToApproved(anticipation, observation)

        treasureDataService.track(anticipation.customer, TreasureDataEventType.ANTICIPATION_ANALYZED, [anticipationId: anticipation.id, billingType: anticipation.billingType])
        hubspotEventService.trackAnticipationAnalyzed(anticipation.customer, anticipation.billingType, true)

        receivableAnticipationAnalysisService.finishAnalysisIfExists(anticipation.id)

        return anticipation
    }

    public void processApprovedAnticipations(BillingType billingType) {
        final Integer maxItemsPerCycle = 100

        Map search = [column: "id", billingType: billingType, status: ReceivableAnticipationStatus.APPROVED, "releaseCreditDate[le]": new Date()]

        if (billingType.isCreditCard() && AsaasEnvironment.isProduction()) {
            search."zAnticipationWhitelist[exists]" = true
        }

        List<Long> approvedAnticipationIdList =  ReceivableAnticipation.query(search).list(max: maxItemsPerCycle)
        Utils.forEachWithFlushSession(approvedAnticipationIdList, 10, { Long anticipationId ->
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation anticipation = ReceivableAnticipation.get(anticipationId)

                for (Payment payment : anticipation.getPayments()) {
                    payment = AsaasApplicationHolder.applicationContext.paymentConfirmService.applyNetValue(payment)
                    payment.save(flush: true, failOnError: true)
                }

                if (anticipation.billingType.isCreditCard()) {
                    processApprovedCreditCardAnticipation(anticipation)
                } else {
                    processApprovedBankSlipAndPixAnticipation(anticipation)
                }
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) return

                    AsaasLogger.error("ReceivableAnticipationService.processApprovedAnticipations >> Não foi possível processar a antecipação aprovada [${anticipationId}]", exception)
            }])
        })
    }

    public ReceivableAnticipation credit(ReceivableAnticipation anticipation) {
        if (!anticipation.canBeCredited()) throw new BusinessException("A antecipação [${anticipation.id}] não pode ser creditada.")

        if (anticipation.status.isApproved()) {
            recalculateFee(anticipation, [execute: true])
            if (!anticipation.hasPromotionalCodeUse()) consumePromotionalCode(anticipation)
        }

        updateStatusToCredited(anticipation)

        receivableAnticipationPartnerAcquisitionService.credit(anticipation.partnerAcquisition)

        FinancialTransaction financialTransaction = financialTransactionService.saveReceivableAnticipationCredit(anticipation)
        anticipation.creditedWithGrossValue = financialTransaction.transactionType.isReceivableAnticipationGrossCredit()
        anticipation.save(failOnError: true)

        for (ReceivableAnticipationItem anticipationItem in anticipation.items) {
            paymentSplitService.executeSplit(anticipationItem.payment, anticipationItem.value - anticipationItem.fee)
            paymentPushNotificationRequestAsyncPreProcessingService.save(anticipationItem.payment, PushNotificationRequestEvent.PAYMENT_ANTICIPATED)
        }

        if (!anticipation.isVortxAcquisition() && anticipation.isCreditCard()) asyncActionService.save(AsyncActionType.CREATE_ANTICIPATED_RECEIVABLE_UNIT, [anticipationId: anticipation.id])

        mandatoryCustomerAccountNotificationService.createNotificationsForCustomerAccountIfNecessary(anticipation)

        receivableAnticipationCustomerMessageService.notifyCustomerAboutReceivableAnticipationCredit(anticipation)
        mobilePushNotificationService.notifyAnticipationRequestResult(anticipation)

        pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_CREDITED, anticipation)

        for (ReceivableAnticipationItem anticipationItem : anticipation.items) {
            if (shouldExecuteDebitOnCredit(anticipationItem.payment)) {
                debit(anticipationItem.payment, false)
            }
        }

        return anticipation
    }

    public void debit(Payment payment, Boolean forceToConsumeBalance) {
        if (payment.hasRefundInProgress) return

        List<ReceivableAnticipation> anticipationList = ReceivableAnticipationItem.query([column: "anticipation",
                                                                                          disableSort: true,
                                                                                          customer: payment.provider,
                                                                                          "status[in]": ReceivableAnticipationStatus.listDebitable(),
                                                                                          paymentId: payment.id]).list()

        if (!anticipationList && payment.status.isReceived()) {
            anticipationDebitOverdueSettlementAsyncActionService.saveIfNecessary(payment.providerId, [paymentId: payment.id])
            return
        }

        Boolean hasAnticipationAwaitingCredit = anticipationList?.any { it.status.isAwaitingPartnerCredit() }
        if (hasAnticipationAwaitingCredit) return

        for (ReceivableAnticipation anticipation : anticipationList) {
            if (anticipation.installment) {
                debitInstallment(anticipation, payment)
                break
            }

            if (anticipation.status.isOverdue() && !payment.isReceived()) continue

            Boolean alreadyDebited = anticipation.status.isOverdue() || anticipation.status.isDebited()

            if (payment.isReceived()) {
                updateStatus(anticipation, ReceivableAnticipationStatus.DEBITED, null)
                if (anticipation.isCreditCard() && !anticipation.isAsaasAcquisition()) asyncActionService.saveFinishFidcContractualEffectGuarantee(anticipation.id, null)
                pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_DEBITED, anticipation)
            } else {
                updateStatus(anticipation, ReceivableAnticipationStatus.OVERDUE, null)
                if (payment.isOverdue()) asyncActionService.saveCustomerAccountBlockListAnticipationOverdue(anticipation.payment.customerAccount.cpfCnpj)
                pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_OVERDUE, anticipation)
            }

            paymentAnticipableInfoAsyncService.saveCustomerAnticipationsAsAwaitingAnalysisIfNecessary(anticipation.customer, anticipation.billingType)

            receivableAnticipationCompromisedItemService.delete(anticipation, null)

            if (!alreadyDebited && !anticipation.partnerAcquisition) {
                String description = "Baixa da antecipação - fatura nr. ${anticipation.payment.getInvoiceNumber()} ${anticipation.payment.customerAccount.name}"
                FinancialTransaction financialTransaction = financialTransactionService.saveReceivableAnticipationDebit(anticipation, null, description)
                internalLoanService.saveIfNecessary(financialTransaction)
            }

            receivableAnticipationValidationCacheService.evictHasActiveReceivableAnticipation(anticipation.customerAccount.id)
        }

        receivableAnticipationPartnerAcquisitionService.debit(payment, forceToConsumeBalance)
    }

    public ReceivableAnticipation deny(Long anticipationId, String denialReasonObservation, ReceivableAnticipationDenialReason denialReason) {
        ReceivableAnticipation anticipation = ReceivableAnticipation.get(anticipationId)

        if (!anticipation.status.isDeniable()) throw new RuntimeException("Não é possível negar esta antecipação.")

        String formattedObservation = "Motivo da reprovação: ${denialReason.getLabel()} \nObservação: ${denialReasonObservation}"
        updateStatus(anticipation, ReceivableAnticipationStatus.DENIED, formattedObservation)

        anticipation.denialReason = denialReason

        anticipation.save(flush: true, failOnError: true)

        setPaymentsAsNotAnticipated(anticipation)

        receivableAnticipationPartnerAcquisitionService.cancel(anticipation)

        receivableAnticipationCompromisedItemService.delete(anticipation, null)

        promotionalCodeService.cancelPromotionalCodeUse(anticipation)

        String customerDenialMessage = denialReason.getCustomerMessage()
        receivableAnticipationCustomerMessageService.sendReceivableAnticipationDenied(anticipation, customerDenialMessage)

        treasureDataService.track(anticipation.customer, TreasureDataEventType.ANTICIPATION_ANALYZED, [anticipationId: anticipation.id, billingType: anticipation.billingType])
        hubspotEventService.trackAnticipationAnalyzed(anticipation.customer, anticipation.billingType, false)

        customerAlertNotificationService.notifyAnticipationRequestResult(anticipation)

        receivableAnticipationAnalysisService.finishAnalysisIfExists(anticipation.id)

        mobilePushNotificationService.notifyAnticipationRequestResult(anticipation)
        pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_DENIED, anticipation)

        return anticipation
    }

    public void setPaymentsAsNotAnticipated(ReceivableAnticipation anticipation) {
        if (anticipation.payment) {
            if (ReceivableAnticipation.countValid(anticipation.payment.provider.id, anticipation.payment.id) == 0) {
                updatePaymentAsNotAnticipated(anticipation.payment.id)
            }
        }

        if (anticipation.installment) {
            for (ReceivableAnticipationItem item : anticipation.items) {
                if (ReceivableAnticipation.countValid(item.payment.provider.id, item.payment.id) == 0) {
                    updatePaymentAsNotAnticipated(item.payment.id)
                }
            }
        }

        paymentAnticipableInfoAsyncService.saveCustomerAnticipationsAsAwaitingAnalysisIfNecessary(anticipation.customer, anticipation.billingType)
    }

    public List<ReceivableAnticipation> list(Customer customer, Integer max, Integer offset, Map search) {
        return ReceivableAnticipation.query([sort: "id", order: "desc"] + search + [customer: customer]).list(max: max, offset: offset)
    }

    public void processNewBankSlipAnticipationLimit() {
        final Integer lastPaymentReceivedMonthLimit = 3

        Date threeMonthsAgo = CustomDateUtils.addMonths(new Date(), lastPaymentReceivedMonthLimit * -1)
        Date startDate = CustomDateUtils.getFirstDayOfMonth(threeMonthsAgo).clearTime()
        Date finishDate = CustomDateUtils.setTimeToEndOfDay(CustomDateUtils.getLastDayOfLastMonth())

        Map search = [:]
        search.distinct = "provider.id"
        search.status = PaymentStatus.RECEIVED
        search."paymentDate[ge]" = startDate
        search."paymentDate[le]" = finishDate
        search.disableSort = true
        List<Long> customerIdList = Payment.query(search).list()

        final Integer flushEvery = 100
        Utils.forEachWithFlushSession(customerIdList, flushEvery, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)
                if (customer.deleted) return

                customerReceivableAnticipationConfigService.recalculateBankSlipAnticipationLimit(customer)
            }, [logErrorMessage: "ReceivableAnticipationService.processNewBankSlipAnticipationLimit >> Erro ao processar atualização automática do limite antecipação de boleto. Id do cliente: ${customerId}."])
        })

        removeAnticipationLimitForCustomerWithoutManualConfig(customerIdList)
    }

    public void autoApproveCreditCardAnticipationIfAgreementHasBeenSigned(ReceivableAnticipation receivableAnticipation) {
        if (!receivableAnticipation.isCreditCard()) return
        if (!receivableAnticipation.canBeApproved()) return

        Boolean synchronousAutoApproveEnabled = CustomerParameter.getValue(receivableAnticipation.customer, CustomerParameterName.AUTOMATIC_APPROVAL_CREDIT_CARD_ANTICIPATION)
        if (synchronousAutoApproveEnabled) {
            approve(receivableAnticipation.id, "Aprovação automática. Parâmetro para auto aprovação habilitado.")
            return
        }

        updateStatus(receivableAnticipation, ReceivableAnticipationStatus.AWAITING_AUTOMATIC_APPROVAL, "Enviado para fila de aprovação automática.")
    }

    public Boolean paymentFeeChargedByAnticipationWhenPaymentReceiving(Payment payment) {
        PaymentFeeChargedByAnticipationVO paymentFeeChargedByAnticipation = getInfoPaymentFeeChargedByAnticipation(payment)
        if (!paymentFeeChargedByAnticipation) return false
        if (paymentFeeChargedByAnticipation.hasAnticipationGrossCredit) return true
        return paymentFeeChargedByAnticipation.partner.isVortx()
    }

    public PaymentFeeChargedByAnticipationVO getInfoPaymentFeeChargedByAnticipation(Payment payment) {
        PaymentFeeChargedByAnticipationVO anticipationVO = new PaymentFeeChargedByAnticipationVO()

        if (!payment.anticipated) return null

        ReceivableAnticipationPartnerAcquisition partnerAcquisition = ReceivableAnticipationPartnerAcquisition.active([payment: payment]).get()
        if (!partnerAcquisition) return null

        anticipationVO.partner = partnerAcquisition.partner
        anticipationVO.hasAnticipationGrossCredit = partnerAcquisition.receivableAnticipation.creditedWithGrossValue
        anticipationVO.partnerAcquisition = partnerAcquisition

        return anticipationVO
    }

    public Boolean canReversePaymentFee(Payment payment, PaymentFeeChargedByAnticipationVO paymentFeeChargedByAnticipation) {
        if (!paymentFeeChargedByAnticipation) return false
        if (payment.status.isRefundInProgress()) return false

        Boolean reverseBankSlipFeeAnticipatedByAsaas = payment.billingType.isBoleto() && paymentFeeChargedByAnticipation.partner.isAsaas()
        Boolean reverseCreditCardFeeAnticipated = payment.billingType.isCreditCard() && paymentFeeChargedByAnticipation.hasAnticipationGrossCredit

        return (reverseBankSlipFeeAnticipatedByAsaas || reverseCreditCardFeeAnticipated)
    }

    public Boolean isDocumentsRequiredToRequestAnticipation(Payment payment, Installment installment) {
        if (installment) return false
        if (payment?.billingType?.isCreditCard()) return false

        Invoice invoice = Invoice.findByPaymentOrInstallment(payment)
        if (!invoice) return true
        if (invoice.value < payment.value) return true

        if (!invoice.status.isAllowedToBeUsedAsAnticipationDocument()) return true
        if (invoice.status.isAuthorized()) return false

        if (!invoice.fiscalConfig) return true
        if (invoice.fiscalConfig.invoiceCreationPeriod.invoiceOnPaymentCreation()) return false

        return true
    }

    public Map summaryAnticipationsByStatus(Customer customer, Map search) {
        List<Map> valuesByStatusList = ReceivableAnticipation.sumValueAndCountByStatus([search + [customer: customer]]).list()

        Map summary = [:]

        summary.scheduled = [:]
        summary.scheduled.count = 0
        summary.scheduled.sumValue = 0

        summary.underAnalysis = [:]
        summary.underAnalysis.count = 0
        summary.underAnalysis.sumValue = 0

        summary.credited = [:]
        summary.credited.count = 0
        summary.credited.sumValue = 0

        summary.debited = [:]
        summary.debited.count = 0
        summary.debited.sumValue = 0

        for (Map valuesByStatus : valuesByStatusList) {
            if (ReceivableAnticipationStatus.getUnderAnalysis().contains(valuesByStatus.status) ) {
                summary.underAnalysis.count += valuesByStatus.count
                summary.underAnalysis.sumValue += valuesByStatus.sumValue
            } else if (ReceivableAnticipationStatus.SCHEDULED == valuesByStatus.status) {
                summary.scheduled.count += valuesByStatus.count
                summary.scheduled.sumValue += valuesByStatus.sumValue
            } else if (ReceivableAnticipationStatus.CREDITED == valuesByStatus.status) {
                summary.credited.count += valuesByStatus.count
                summary.credited.sumValue += valuesByStatus.sumValue
            } else if (ReceivableAnticipationStatus.DEBITED == valuesByStatus.status) {
                summary.debited.count += valuesByStatus.count
                summary.debited.sumValue += valuesByStatus.sumValue
            }
        }

        return summary
    }

    public List<Payment> getToAnticipatePaymentsFromInstallment(Installment installment, Boolean schedule) {
        List<Payment> validatedPaymentList = []
        List<Map> paymentAnticipableInfoList = PaymentAnticipableInfo.query([columnList: ["anticipable", "schedulable", "payment"], installmentId: installment.id, includeDeleted: true, disableSort: true]).list()

        for (Map paymentAnticipableInfo : paymentAnticipableInfoList) {
            if (paymentAnticipableInfo.anticipable || (schedule && paymentAnticipableInfo.schedulable)) {
                validatedPaymentList.add(paymentAnticipableInfo.payment)
            }
        }

        return validatedPaymentList
    }

    public void recalculateFee(ReceivableAnticipation anticipation, Map options) {
        Map properties = [:]

        ReceivableAnticipationPartner receivableAnticipationPartner = anticipation.partnerAcquisition.partner

        if (anticipation.payment) {
            ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
            receivableAnticipationFinancialInfoVO.build(anticipation.payment, options.schedule)
            receivableAnticipationFinancialInfoVO.scheduleDaysAfterConfirmation = options.scheduleDaysAfterConfirmation

            properties = receivableAnticipationFinancialInfoService.buildFinancialInfo(anticipation.customer, receivableAnticipationFinancialInfoVO, receivableAnticipationPartner)

            ReceivableAnticipationItem anticipationItem = (anticipation.items) ? anticipation.items.first() : null
            if (anticipationItem) {
                Date estimatedCreditDate = anticipationItem.payment.dueDate
                if (anticipationItem.payment.billingType.isCreditCard()) {
                    Date confirmationDate = receivableAnticipationFinancialInfoService.calculateEstimatedConfirmationDate(receivableAnticipationFinancialInfoVO, anticipation.customer)
                    estimatedCreditDate = anticipationItem.payment.creditDate ?: Payment.calculateEstimatedCreditDate(anticipationItem.customerId, anticipationItem.payment.installmentNumber, confirmationDate)
                }

                anticipationItem.value = properties.value
                anticipationItem.fee = properties.fee
                anticipationItem.estimatedCreditDate = estimatedCreditDate
                anticipationItem.save(failOnError: true)
            }
        } else if (anticipation.installment) {
            List<Payment> payments = []
            anticipation.items.each { payments.add(it.payment) }

            ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
            receivableAnticipationFinancialInfoVO.build(anticipation.installment, options.schedule, payments)
            receivableAnticipationFinancialInfoVO.scheduleDaysAfterConfirmation = options.scheduleDaysAfterConfirmation

            properties = receivableAnticipationFinancialInfoService.buildFinancialInfo(anticipation.customer, receivableAnticipationFinancialInfoVO, receivableAnticipationPartner)

            for (ReceivableAnticipationItem item : anticipation.items) {
                Date confirmationDate = receivableAnticipationFinancialInfoService.calculateEstimatedConfirmationDate(receivableAnticipationFinancialInfoVO, anticipation.customer)
                Date estimatedCreditDate = item.payment.dueDate
                if (item.payment.billingType.isCreditCard()) {
                    estimatedCreditDate = item.payment.creditDate ?: Payment.calculateEstimatedCreditDate(item.customerId, item.payment.installmentNumber, confirmationDate)
                }

                item.value = calculateReceivableAnticipationValue(item.payment)
                item.fee = receivableAnticipationFinancialInfoService.calculateInstallmentItemFee(item.value, properties.anticipationDate, estimatedCreditDate, item.payment.getProvider())
                item.estimatedCreditDate = estimatedCreditDate

                item.save(failOnError: true)
            }
        }

        anticipation.properties = properties
        anticipation.save(failOnError: true)
    }

    public void setAsPendingAndUpdateValueAndFee(ReceivableAnticipation anticipation) {
        try {
            recalculateFee(anticipation, [execute: true])

            if (shouldAwaitAutomaticAttachmentAsaasIssuedInvoice(anticipation)) {
                updateStatus(anticipation, ReceivableAnticipationStatus.AWAITING_AUTOMATIC_ATTACHMENT_ASAAS_ISSUED_INVOICE, "Atualizado de Agendado para Pendente")
            } else {
                updateStatusToPending(anticipation, "Atualizado de Agendado para Pendente")
            }

            pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_PENDING, anticipation)
        } catch (Exception exception) {
            AsaasLogger.error("ReceivableAnticipationService.setAsPendingAndUpdateValueAndFee >> Erro ao atualizar antecipação [${anticipation.id}]", exception)
        }
    }

    public Boolean willAnyPaymentBeAffectedByContractualEffect(ReceivableAnticipation anticipation) {
        for (Payment payment : anticipation.getPayments()) {
            if (CercContractualEffect.getExternalActiveContractBeneficiaryCpfCnpj(payment).asBoolean()) {
                return true
            }
        }
        return false
    }

    public CustomerReceivableAnticipationConfig removeManualLimitAndRecalculateAnticipationLimit(Long customerId) {
        customerReceivableAnticipationConfigService.removeManualAnticipationLimit(customerId)

        Customer customer = Customer.read(customerId)
        customerReceivableAnticipationConfigService.recalculateBankSlipAnticipationLimit(customer)

        return CustomerReceivableAnticipationConfig.findFromCustomer(customerId)
    }

    public void autoApproveCreditCardAnticipationIfEnabled(ReceivableAnticipation receivableAnticipation) {
        if (!ReceivableAnticipationAgreement.anyAgreementVersionHasBeenSigned(receivableAnticipation.customer.id)) return
        autoApproveCreditCardAnticipationIfAgreementHasBeenSigned(receivableAnticipation)
    }

    public void processApprovedBankSlipAndPixAnticipation(ReceivableAnticipation anticipation) {
        if (anticipation.isAsaasAcquisition()) {
            credit(anticipation)
        } else {
            updateToAwaitingPartnerCredit(anticipation)
        }
    }

    public void processApprovedCreditCardAnticipation(ReceivableAnticipation anticipation) {
        if (anticipation.isVortxAcquisition()) {
            updateToAwaitingPartnerCredit(anticipation)
            cercFidcContractualEffectService.saveIfNecessary(anticipation)
        } else {
            if (willAnyPaymentBeAffectedByContractualEffect(anticipation)) {
                ReceivableAnticipationDenialReason denialReason = ReceivableAnticipationDenialReason.HAS_PAYMENT_AFFECTED_BY_CONTRACTUAL_EFFECT
                deny(anticipation.id, denialReason.getLabel(), denialReason)
                return
            }

            credit(anticipation)
            obtainCreditedValueWithPartnerIfNecessary(anticipation)
        }
    }

    public void obtainCreditedValueWithPartnerIfNecessary(ReceivableAnticipation anticipation) {
        if (!anticipation.partner.isOcean()) return

        oceanManagerService.obtainAnticipationCreditedValue(anticipation)
        receivableAnticipationPartnerAcquisitionService.confirm(anticipation.partnerAcquisition)
    }

    public void updateStatusToPending(ReceivableAnticipation anticipation, String observation) {
        anticipation.readyForAnalysisDate = new Date()
        updateStatus(anticipation, ReceivableAnticipationStatus.PENDING, observation)
    }

    public Boolean hasReceivableAnticipationCredited(Customer customer) {
        return ReceivableAnticipation.query([
            exists: true,
            customer: customer,
            disableSort: true,
            status: ReceivableAnticipationStatus.CREDITED]).get().asBoolean()
    }

    public void updateStatus(ReceivableAnticipation anticipation, ReceivableAnticipationStatus status, String observation) {
        anticipation.status = status
        anticipation.save(failOnError: true)

        receivableAnticipationItemService.setAllItemsStatus(anticipation, status)

        receivableAnticipationHistoryService.save(anticipation, observation)
    }

    private ReceivableAnticipation validateDocumentList(CreateReceivableAnticipationAdapter adapter) {
        ReceivableAnticipation validatedAnticipation = new ReceivableAnticipation()

        if (isDocumentsRequiredToRequestAnticipation(adapter.payment, adapter.installment)) {
            if (adapter.isDocumentListEmptyOrAnyDocumentIsNull()) {
                DomainUtils.addError(validatedAnticipation, "Envie o contrato comercial ou a nota fiscal eletrônica.")
                return validatedAnticipation
            }
        } else {
            adapter.needsToAttachAsaasIssuedInvoice = needsToAttachAsaasIssuedInvoice(adapter)
        }

        for (ReceivableAnticipationDocumentVO documentVO : adapter.documentVOList) {
            ReceivableAnticipationDocument validatedDocument = receivableAnticipationDocumentService.validateDocument(adapter.customer, documentVO)
            if (validatedDocument.hasErrors()) {
                validatedAnticipation = DomainUtils.copyAllErrorsFromObject(validatedDocument, validatedAnticipation)
                return validatedAnticipation
            }
        }

        return validatedAnticipation
    }

    private Boolean needsToAttachAsaasIssuedInvoice(CreateReceivableAnticipationAdapter createReceivableAnticipationAdapter) {
        if (!createReceivableAnticipationAdapter.billingType?.isBoletoOrPix()) return false

        return createReceivableAnticipationAdapter.isDocumentListEmptyOrAnyDocumentIsNull()
    }

    private void updatePaymentAsNotAnticipated(Long paymentId) {
        Payment payment = Payment.get(paymentId)
        payment.anticipated = false
        payment.save(failOnError: true)

        paymentAnticipableInfoService.updateIfNecessary(payment)
        paymentAnticipableInfoService.sendToAnalysisQueue(paymentId)
    }

    private Boolean shouldAwaitAutomaticAttachmentAsaasIssuedInvoice(ReceivableAnticipation anticipation) {
        if (anticipation.installment) return false
        if (anticipation.billingType.isCreditCard()) return false

        Boolean alreadyHasDocument = ReceivableAnticipationDocument.query([exists: true, anticipationId: anticipation.id, disableSort: true]).get().asBoolean()
        if (alreadyHasDocument) return false

        Boolean hasInvoice = InvoiceItem.query([column: "invoice.id", "payment[or]": anticipation.payment]).get().asBoolean()
        return hasInvoice
    }

    private void consumePromotionalCode(ReceivableAnticipation receivableAnticipation) {
        BigDecimal fee = receivableAnticipation.fee
        if (!fee) return

        BigDecimal feeWithDiscountApplied = promotionalCodeService.consumeFeeDiscountBalance(receivableAnticipation.customer, fee, receivableAnticipation)
        if (fee == feeWithDiscountApplied) return

        updateFeeWithDiscountApplied(receivableAnticipation, feeWithDiscountApplied)
    }

    private void updateFeeWithDiscountApplied(ReceivableAnticipation receivableAnticipation, BigDecimal feeWithDiscountApplied) {
        receivableAnticipationItemService.updateFeeWithDiscountApplied(receivableAnticipation, feeWithDiscountApplied)

        receivableAnticipation.netValue = receivableAnticipation.value - feeWithDiscountApplied
        receivableAnticipation.originalFee = receivableAnticipation.fee
        receivableAnticipation.fee = feeWithDiscountApplied

        receivableAnticipation.save(failOnError: true)
    }

    private void updateStatusToApproved(ReceivableAnticipation anticipation, String observation) {
        anticipation.releaseCreditDate = ReceivableAnticipationFloatCalculator.calculateReleaseCreditDate(anticipation)
        updateStatus(anticipation, ReceivableAnticipationStatus.APPROVED, observation)
    }

    private void updateStatusToCredited(ReceivableAnticipation anticipation) {
        anticipation.creditDate = new Date()
        updateStatus(anticipation, ReceivableAnticipationStatus.CREDITED, null)
    }

    private ReceivableAnticipation build(CreateReceivableAnticipationAdapter adapter) {
        Map properties = buildAnticipationPropertiesMap(adapter)

        ReceivableAnticipation anticipation = new ReceivableAnticipation(properties)
        anticipation.billingType = adapter.billingType
        anticipation.publicId = ReceivableAnticipation.buildPublicId()

        return anticipation
    }

    private Map buildAnticipationPropertiesMap(CreateReceivableAnticipationAdapter adapter) {
        Map properties = [installment: adapter.installment, payment: adapter.payment, customer: adapter.customer, scheduleDaysAfterConfirmation: adapter.scheduleDaysAfterConfirmation]

        ReceivableAnticipationValidator validator = new ReceivableAnticipationValidator(false)

        String receivableAnticipationDisabledReason
        if (adapter.schedule) {
            receivableAnticipationDisabledReason = validator.canSchedule(adapter.payment, adapter.installment, adapter.customer, adapter.customerAccount)?.first()?.message
            if (!receivableAnticipationDisabledReason) ReceivableAnticipationSchedulingValidator.validateScheduleDaysAfterConfirmation(adapter.scheduleDaysAfterConfirmation, adapter.payment, adapter.installment)
        } else {
            receivableAnticipationDisabledReason = validator.canAnticipate(adapter.payment, adapter.installment, adapter.customer, adapter.customerAccount)?.first()?.message
        }

        if (receivableAnticipationDisabledReason) {
            throw new BusinessException(getMessageDisabledReason(adapter.payment, adapter.installment, receivableAnticipationDisabledReason))
        }

        ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()

        if (adapter.installment) {
            List<Payment> anticipationItems = getToAnticipatePaymentsFromInstallment(adapter.installment, adapter.schedule)

            properties.customerAccount = adapter.customerAccount
            properties.billingType = adapter.billingType
            properties.anticipationItemsCount = anticipationItems.size()
            properties.installmentHasAnyPaymentWithCercContractualEffect = cercContractualEffectService.installmentHasAnyPaymentWithEffect(adapter.installment)
            receivableAnticipationFinancialInfoVO.build(adapter.installment, adapter.schedule, anticipationItems)
        } else if (adapter.payment) {
            properties.customerAccount = adapter.customerAccount
            properties.billingType = adapter.billingType
            receivableAnticipationFinancialInfoVO.build(adapter.payment, adapter.schedule)
        }

        receivableAnticipationFinancialInfoVO.scheduleDaysAfterConfirmation = adapter.scheduleDaysAfterConfirmation

        properties << receivableAnticipationFinancialInfoService.buildFinancialInfo(adapter.customer, receivableAnticipationFinancialInfoVO, adapter.partner)

        properties.status = adapter.schedule ? ReceivableAnticipationStatus.SCHEDULED : ReceivableAnticipationStatus.PENDING

        properties.hasInstallmentRestrictions = properties.installment && (properties.installment.anyPaymentHasBeenAnticipated() || properties.installmentHasAnyPaymentWithCercContractualEffect || properties.installment.anyPaymentHasBeenReceived())

        return properties
    }

    private void sendPushNotificationOnCreate(ReceivableAnticipation anticipation) {
        if (anticipation.status.isPendingToApiResponse()) {
            pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_PENDING, anticipation)
        } else if (anticipation.status.isScheduled()) {
            pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_SCHEDULED, anticipation)
        }
    }

    private String getMessageDisabledReason(Payment payment, Installment installment, String receivableAnticipationDisabledReason) {
        return "Não é possível antecipar ${installment ? ('o parcelamento ' + installment.id) : ('a cobrança ' + payment.id)}. Motivo(s): ${receivableAnticipationDisabledReason}"
    }

    private Map buildTrackingMap(ReceivableAnticipation anticipation) {
        Map trackingMap = [:]
        trackingMap.customer = anticipation.customer.id
        trackingMap.valorCobranca = anticipation.netValue
        trackingMap.vencimento = anticipation.dueDate
        trackingMap.dias = ReceivableAnticipationCalculator.calculateAnticipationDays(anticipation.anticipationDate, anticipation.dueDate)
        trackingMap.taxa = anticipation.fee
        trackingMap.valorLiquido = anticipation.netValue

        return trackingMap
    }

    private void debitInstallment(ReceivableAnticipation anticipation, Payment payment) {
        ReceivableAnticipationItem anticipationItem = ReceivableAnticipationItem.query([anticipation: anticipation, paymentId: payment.id]).get()

        Boolean alreadyDebited = anticipationItem.status.isOverdue() || anticipationItem.status.isDebited()
        if (alreadyDebited) return

        if (!anticipation.partnerAcquisition) {
            String description = "Baixa da parcela ${payment.installmentNumber} (fatura nr. ${payment.getInvoiceNumber()}) da antecipação do parcelamento - fatura nr. ${anticipation.installment.getInvoiceNumber()} ${anticipation.customerAccount.name}"
            FinancialTransaction financialTransaction = financialTransactionService.saveReceivableAnticipationDebit(anticipation, anticipationItem, description)
            internalLoanService.saveIfNecessary(financialTransaction)
        }

        if (payment.isRefunded()) {
            anticipation.status = ReceivableAnticipationStatus.OVERDUE
            anticipationItem.status = anticipation.status
            pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_OVERDUE, anticipation)
        } else {
            anticipationItem.status = ReceivableAnticipationStatus.DEBITED
            if (anticipation.isCreditCard() && anticipation.isVortxAcquisition()) asyncActionService.saveFinishFidcContractualEffectGuarantee(anticipation.id, anticipationItem.id)
        }

        if (anticipation.allPaymentsAreReceived()) {
            anticipation.status = ReceivableAnticipationStatus.DEBITED
            anticipationItem.status = anticipation.status

            pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_DEBITED, anticipation)
        }

        anticipation.save(failOnError: true, deepValidate: false)
        anticipationItem.save(failOnError: true)

        receivableAnticipationValidationCacheService.evictHasActiveReceivableAnticipation(anticipation.customerAccount.id)

        receivableAnticipationCompromisedItemService.delete(anticipation, anticipationItem)
    }

    private BigDecimal getValueToBeAnticipated(Payment payment, Installment installment, Boolean isScheduling) {
        if (installment) {
            List<Payment> anticipationItems = getToAnticipatePaymentsFromInstallment(installment, isScheduling)

            if (!anticipationItems) {
                Boolean hasPaymentAnticipableInfoAwaitingAnalysis = PaymentAnticipableInfo.query([exists: true, installmentId: installment.id, status: PaymentAnticipableInfoStatus.AWAITING_ANALYSIS, includeDeleted: true, disableSort: true]).get().asBoolean()

                if (hasPaymentAnticipableInfoAwaitingAnalysis) {
                    throw new BusinessException("Cobranças aguardando análise")
                }
                throw new BusinessException("Não foi possível antecipar as cobranças selecionadas")
            }

            return calculateReceivableAnticipationValue(anticipationItems)
        } else {
            return calculateReceivableAnticipationValue(payment)
        }
    }

    private void removeAnticipationLimitForCustomerWithoutManualConfig(List<Long> customerIdList) {
        Map search = [:]
        search.column = "id"
        search."customerId[notIn]" = customerIdList
        search."bankSlipAnticipationLimit[gt]" = 0.0
        search."bankSlipManualAnticipationLimit[isNull]" = true
        List<Long> anticipationConfigFromCustomerThatDidntReceivePaymentsInLastThreeMonthsIdList = CustomerReceivableAnticipationConfig.query(search).list()

        Utils.forEachWithFlushSession(anticipationConfigFromCustomerThatDidntReceivePaymentsInLastThreeMonthsIdList, 100, { Long receivableAnticipationConfigId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.get(receivableAnticipationConfigId)
                receivableAnticipationConfig.bankSlipAnticipationLimit = 0
                receivableAnticipationConfig.save()

                customerReceivableAnticipationConfigService.saveHistory(receivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.ANTICIPATION_LIMIT_REMOVED_AFTER_INACTIVITY)
                customerInteractionService.save(receivableAnticipationConfig.customer, 'Limite de antecipação alterado para R$ 0,00 pois o cliente está há mais de 3 meses sem receber cobranças.')
            }, [logErrorMessage: "ReceivableAnticipationService.removeAnticipationLimitForCustomerWithoutManualConfig >> [${receivableAnticipationConfigId}]"])
        })
    }

    private saveOriginRequesterInfo(ReceivableAnticipation anticipation, ReceivableAnticipationOriginRequesterInfoMethod method, EventOriginType eventOrigin) {
        ReceivableAnticipationOriginRequesterInfo receivableAnticipationOriginRequesterInfo = new ReceivableAnticipationOriginRequesterInfo()
        receivableAnticipationOriginRequesterInfo.receivableAnticipation = anticipation
        receivableAnticipationOriginRequesterInfo.method = method
        receivableAnticipationOriginRequesterInfo.user = UserUtils.getCurrentUser()
        receivableAnticipationOriginRequesterInfo.remoteIp = RequestUtils.getRemoteIp()
        receivableAnticipationOriginRequesterInfo.eventOrigin = eventOrigin ?: originRequesterInfoService.getEventOrigin()
        if (receivableAnticipationOriginRequesterInfo.user) receivableAnticipationOriginRequesterInfo.device = UserKnownDeviceUtils.getCurrentDevice(receivableAnticipationOriginRequesterInfo.user.id)
        receivableAnticipationOriginRequesterInfo.save(failOnError: true)
    }

    private Boolean shouldExecuteDebitOnCredit(Payment payment) {
        if (!payment.billingType.isBoletoOrPix()) return false

        return payment.status.isDebitableForBankSlipOrPixAnticipation()
    }
}
