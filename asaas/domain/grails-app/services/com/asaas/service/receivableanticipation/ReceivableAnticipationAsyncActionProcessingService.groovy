package com.asaas.service.receivableanticipation

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.paymentinfo.PaymentNonAnticipableReason
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItemType
import com.asaas.utils.AbTestUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationAsyncActionProcessingService {

    private static final Integer MAX_PAYMENTS_TO_PROCESS = 65000
    private static final Integer MAX_PAYMENTS_TO_ENABLE_PROCESS = 2000

    def asyncActionService
    def customerReceivableAnticipationConfigService
    def receivableAnticipationCancellationService
    def receivableAnticipationLimitRecalculationService
    def receivableAnticipationPartnerSettlementService
    def receivableAnticipationService
    def receivableAnticipationValidationService

    public void applyCreditCardPercentageConfigs() {
        final Integer maxItems = 200

        List<Map> applyAnticipationCreditCardPercentageConfigAsyncActionList = asyncActionService.listPendingApplyAnticipationCreditCardPercentageConfig(maxItems)

        Utils.forEachWithFlushSession(applyAnticipationCreditCardPercentageConfigAsyncActionList, 50, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ( {
                Customer customer = Customer.read(asyncActionData.customerId)
                CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
                if (customerReceivableAnticipationConfig.hasCreditCardPercentage()) {
                    receivableAnticipationLimitRecalculationService.addCustomerToRecalculateLimitIfNecessary(customer, true)
                }
                asyncActionService.delete(asyncActionData.asyncActionId)
            },
                [
                    logErrorMessage: "ReceivableAnticipationAsyncActionProcessingService.applyCreditCardPercentageConfigs >> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]",
                    onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
                ]
            )
        })
    }

    public void processDisableBankSlipAnticipation() {
        final Integer maxAsyncActions = 100
        final Integer flushEvery = 20

        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.PROCESS_DISABLE_BANKSLIP_ANTICIPATION, maxAsyncActions)
        if (!asyncActionList) return

        Utils.forEachWithFlushSession(asyncActionList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                if (!customer) throw new RuntimeException("Cliente ${asyncActionData.customerId} não encontrado")

                List<BillingType> billingTypesToDisable = [BillingType.BOLETO]
                if (AbTestUtils.hasPixAnticipation(customer)) billingTypesToDisable.add(BillingType.PIX)

                for (BillingType billingType : billingTypesToDisable) {
                    receivableAnticipationCancellationService.cancelAllPossibleByBillingType(customer.id, billingType, ReceivableAnticipationCancelReason.ANTICIPATION_DISABLED)

                    Boolean settingFinished = setAsNotAnticipableAndSchedulable(customer, billingType)
                    if (!settingFinished) return
                }

                CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.query([customerId: asyncActionData.customerId]).get()
                if (!receivableAnticipationConfig) throw new RuntimeException("Configuração de antecipação não encontrada.")

                String customerInteractionDescription = 'Limite de antecipação alterado para R$ 0,00 pois a antecipação de boleto está desativada para o cliente.'
                customerReceivableAnticipationConfigService.removeBankSlipAnticipationLimitForCustomerWithoutManualConfig(receivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.ANTICIPATION_LIMIT_REMOVED_ON_DISABLE_BANK_SLIP_ANTICIPATION, customerInteractionDescription)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReceivableAnticipationAsyncActionProcessingService.processDisableBankSlipAnticipation >> Erro ao processar AsyncAction [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.customerId}]",
                onError: {
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                }
            ])
        })
    }

    public void processEnableBankSlipAnticipation() {
        final Integer maxItems = 10
        final Integer flushEvery = 2

        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.PROCESS_ENABLE_BANKSLIP_ANTICIPATION, maxItems)
        if (!asyncActionList) return

        Utils.forEachWithFlushSession(asyncActionList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                List<BillingType> billingTypesToElectForAnalysis = [BillingType.BOLETO]

                if (AbTestUtils.hasPixAnticipation(customer)) billingTypesToElectForAnalysis.add(BillingType.PIX)

                for (BillingType billingType : billingTypesToElectForAnalysis) {
                    Boolean settingFinished = updatePaymentsEligibleForAnalysis(asyncActionData.customerId, billingType)
                    if (!settingFinished) return
                }

                customerReceivableAnticipationConfigService.recalculateBankSlipAnticipationLimit(customer)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReceivableAnticipationAsyncActionProcessingService.processEnableBankSlipAnticipation >> Erro ao processar AsyncAction [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.customerId}]",
                onError: {
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                }
            ])
        })
    }

    public void processDisableCreditCardAnticipation() {
        final Integer maxAsyncActions = 100
        final Integer flushEvery = 20

        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.PROCESS_DISABLE_CREDIT_CARD_ANTICIPATION, maxAsyncActions)
        if (!asyncActionList) return

        Utils.forEachWithFlushSession(asyncActionList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                if (!customer) throw new RuntimeException("Cliente ${asyncActionData.customerId} não encontrado")

                receivableAnticipationCancellationService.cancelAllPossibleByBillingType(customer.id, BillingType.MUNDIPAGG_CIELO, ReceivableAnticipationCancelReason.ANTICIPATION_DISABLED)

                Boolean settingFinished = setAsNotAnticipableAndSchedulable(customer, BillingType.MUNDIPAGG_CIELO)

                if (settingFinished) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReceivableAnticipationAsyncActionProcessingService.processDisableCreditCardAnticipation >> Erro ao processar AsyncAction [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.customerId}]",
                onError: {
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                }
            ])
        })
    }

    public void processEnableCreditCardAnticipation() {
        final Integer maxItems = 10
        final Integer flushEvery = 2

        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.PROCESS_ENABLE_CREDIT_CARD_ANTICIPATION, maxItems)
        if (!asyncActionList) return

        Utils.forEachWithFlushSession(asyncActionList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Boolean settingFinished = updatePaymentsEligibleForAnalysis(asyncActionData.customerId, BillingType.MUNDIPAGG_CIELO)
                if (settingFinished) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReceivableAnticipationAsyncActionProcessingService.processEnableCreditCardAnticipation >> Erro ao processar AsyncAction [asyncActionId: ${asyncActionData.asyncActionId}, customerId: ${asyncActionData.customerId}]",
                onError: {
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                }
            ])
        })
    }

    public void processAsyncActionForCreateReceivableAnticipationDebitSettlementItem() {
        final Integer maxNumberOfGroupIdPerExecution = 400
        List<String> groupIdList = AsyncAction.oldestPending([distinct: "groupId", disableSort: true, type: AsyncActionType.CREATE_RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT_ITEM]).list(max: maxNumberOfGroupIdPerExecution)

        final Integer maxAsyncActions = 100
        final Integer numberOfThreads = 4

        Utils.processWithThreads(groupIdList, numberOfThreads, { List<String> subGroupIdList ->
            List<Map> asyncActionList = asyncActionService.listPendingCreateReceivableAnticipationPartnerSettlementItem(subGroupIdList, maxAsyncActions)

            for (Map asyncActionData : asyncActionList) {
                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableAnticipationPartnerSettlement partnerSettlement = ReceivableAnticipationPartnerSettlement.get(asyncActionData.partnerSettlementId)
                    Payment payment = Payment.load(asyncActionData.paymentId)
                    InternalTransfer internalTransfer = InternalTransfer.load(asyncActionData.internalTransferId)
                    PixTransaction pixTransaction = PixTransaction.load(asyncActionData.pixTransactionId)

                    BigDecimal maxSettlementValue = asyncActionData.maxSettlementValue
                    BigDecimal pendingSettlementValue = partnerSettlement.getAwaitingPaymentValueToPartner()

                    BigDecimal itemValue = BigDecimalUtils.min(maxSettlementValue, pendingSettlementValue)
                    if (!pendingSettlementValue || itemValue <= 0) {
                        asyncActionService.delete(asyncActionData.asyncActionId)
                        return
                    }

                    ReceivableAnticipationPartnerSettlementItemType settlementItemType = receivableAnticipationPartnerSettlementService.saveItem(partnerSettlement, itemValue, [payment: payment, internalTransfer: internalTransfer, pixTransaction: pixTransaction])
                    ReceivableAnticipation anticipation = partnerSettlement.partnerAcquisition.receivableAnticipation

                    if (settlementItemType.isFull() && anticipation.status.isOverdue()) {
                        receivableAnticipationService.updateStatus(anticipation, ReceivableAnticipationStatus.DEBITED, null)
                    }

                    asyncActionService.delete(asyncActionData.asyncActionId)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (Utils.isLock(exception)) {
                            AsaasLogger.warn("ReceivableAnticipationAsyncActionProcessingService.processAsyncActionForCreateReceivableAnticipationDebitSettlementItem >> Ocorreu um lock ao processar criação do item de liquidação da antecipação, asyncActionId: [${asyncActionData.asyncActionId}]", exception)
                            return
                        }

                        asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                        AsaasLogger.error("ReceivableAnticipationAsyncActionProcessingService.processAsyncActionForCreateReceivableAnticipationDebitSettlementItem >> Erro ao processar asyncAction. ID: ${asyncActionData.asyncActionId}", exception)
                    }])
            }
        })
    }

    private Boolean setAsNotAnticipableAndSchedulable(Customer customer, BillingType billingType) {
        List<Long> paymentIdList = []
        ReceivableAnticipationNonAnticipableReasonVO reasonVO

        if (billingType.isCreditCard()) {
            paymentIdList = getPaymentIdListToSetAsNonAnticipable(customer, billingType)
            reasonVO = new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.CREDIT_CARD_ANTICIPATION_DISABLED)
        } else {
            paymentIdList = getPaymentIdListToSetAsNonAnticipable(customer, billingType)
            PaymentNonAnticipableReason nonAnticipableReason = AbTestUtils.hasPixAnticipation(customer) ? PaymentNonAnticipableReason.BANK_SLIP_AND_PIX_ANTICIPATION_DISABLED : PaymentNonAnticipableReason.BANK_SLIP_ANTICIPATION_DISABLED
            reasonVO = new ReceivableAnticipationNonAnticipableReasonVO(nonAnticipableReason)
        }

        if (paymentIdList) receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(paymentIdList, reasonVO)

        return paymentIdList.size() < ReceivableAnticipationAsyncActionProcessingService.MAX_PAYMENTS_TO_PROCESS
    }

    private List<Long> getPaymentIdListToSetAsNonAnticipable(Customer customer, BillingType billingType) {
        List<PaymentStatus> paymentStatus
        if (billingType.isCreditCard()) {
            paymentStatus = [PaymentStatus.PENDING, PaymentStatus.CONFIRMED]
        } else {
            paymentStatus = [PaymentStatus.PENDING]
        }

        return PaymentAnticipableInfo.query([
            column: "payment.id",
            customerId: customer.id,
            anticipated: false,
            billingType: billingType,
            "paymentStatus[in]": paymentStatus,
            disableSort: true
        ]).list(max: ReceivableAnticipationAsyncActionProcessingService.MAX_PAYMENTS_TO_PROCESS)
    }

    private List<Long> getEligibleForAnalysisPaymentIdList(Long customerId, BillingType billingType) {
        List<PaymentStatus> paymentStatusList
        if (billingType.isCreditCard()) {
            paymentStatusList = [PaymentStatus.PENDING, PaymentStatus.CONFIRMED]
        } else {
            paymentStatusList = [PaymentStatus.PENDING]
        }

        return Payment.query([
            column: "id",
            customerId: customerId,
            billingType: billingType,
            statusList: paymentStatusList,
            anticipated: false,
            "paymentAnticipableInfo[notExists]": true,
            disableSort: true
        ]).list(max: ReceivableAnticipationAsyncActionProcessingService.MAX_PAYMENTS_TO_ENABLE_PROCESS)
    }

    private Boolean updatePaymentsEligibleForAnalysis(Long customerId, BillingType billingType) {
        List<Long> paymentIdList = getEligibleForAnalysisPaymentIdList(customerId, billingType)
        receivableAnticipationValidationService.updatePaymentsWhenEnableAnticipation(paymentIdList)
        return paymentIdList.size() < ReceivableAnticipationAsyncActionProcessingService.MAX_PAYMENTS_TO_ENABLE_PROCESS
    }
}
