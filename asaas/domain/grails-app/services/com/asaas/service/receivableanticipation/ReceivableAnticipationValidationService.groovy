package com.asaas.service.receivableanticipation

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationCustomerAccountConfig
import com.asaas.payment.PaymentStatus
import com.asaas.paymentinfo.PaymentAnticipableInfoStatus
import com.asaas.paymentinfo.PaymentNonAnticipableReason
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import com.asaas.receivableanticipationcompromisedvalue.ReceivableAnticipationCompromisedValueCache
import com.asaas.utils.AbTestUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationValidationService {

    def asyncActionService
    def hubspotEventService
    def mobilePushNotificationService
    def paymentAnticipableInfoAsyncService
    def paymentAnticipableInfoService
    def receivableAnticipationValidationCacheService

    public void onPaymentChange(Payment payment) {
        validatePaymentAndUpdateAnticipableProperty(payment)
        paymentAnticipableInfoService.updateIfNecessary(payment)
    }

    public void onPaymentChange(Installment installment) {
        processValidationsForInstallment(installment)
        for (Payment payment : installment.payments) {
            paymentAnticipableInfoService.updateIfNecessary(payment)
        }
    }

    public void onPaymentRestore(Payment payment) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(payment.id)
        if (!paymentAnticipableInfo) {
            paymentAnticipableInfoService.save(payment)
        }

        validatePaymentAndUpdateAnticipableProperty(payment)
        paymentAnticipableInfoService.updateIfNecessary(payment)
    }

    public void notifyOnFirstTransferReachesMinimumPeriodToAnticipate(Customer customer, BillingType billingType) {
        if (canSendAnticipationNotification(customer, billingType)) {
            hubspotEventService.trackCanAnticipate(customer, billingType)

            if (billingType.isBoleto() || (billingType.isCreditCard() && customer.isNaturalPerson())) {
                mobilePushNotificationService.notifyCustomerWhenAnticipationIsReleased(customer, billingType)
            }

            return
        }

        hubspotEventService.trackFirstTransferReachedMinimumPeriodToAnticipate(customer, billingType)
    }

    public void onCustomerChange(Customer customer) {
        if (customer.canAnticipate()) {
            if (receivableAnticipationValidationCacheService.isCreditCardEnabled(customer.id)) paymentAnticipableInfoAsyncService.saveCreditCardAnticipableAsAwaitingAnalysisIfNecessary(customer, [:])
            if (receivableAnticipationValidationCacheService.isBankSlipEnabled(customer.id)) paymentAnticipableInfoAsyncService.saveBankSlipAndPixAnticipableAsAwaitingAnalysisIfNecessary(customer, [:])
        } else {
            List<Long> paymentIdList = PaymentAnticipableInfo.query([customerId: customer.id, "paymentStatus[in]": PaymentStatus.listAnticipable()] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()
            setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.CUSTOMER_REGISTER_STATUS_NOT_APPROVED))
        }
    }

    public void onDeleteCustomerAccount(CustomerAccount customerAccount) {
        List<Long> paymentIdList = PaymentAnticipableInfo.query([customerId: customerAccount.provider.id,
                                                                 paymentCustomerAccountId: customerAccount.id,
                                                                 "paymentStatus[in]": PaymentStatus.listAnticipable()] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()
        setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.CUSTOMER_ACCOUNT_DELETED))
    }

    public void onRestoreCustomerAccount(CustomerAccount customerAccount) {
        paymentAnticipableInfoAsyncService.saveAnticipableAsAwaitingAnalysisIfNecessary(customerAccount.provider, [paymentCustomerAccountId: customerAccount.id])
    }

    public void onBankSlipAnticipationLimitChange(Customer customer, BigDecimal previousLimit) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        if (receivableAnticipationConfig.bankSlipAnticipationLimit == previousLimit) return

        if (receivableAnticipationConfig.bankSlipAnticipationLimit > previousLimit) {
            onBankSlipAnticipationLimitIncrease(customer, previousLimit)
            return
        }

        onBankSlipAnticipationLimitDecrease(customer)
    }

    public void onCreditCardAnticipationLimitChange(Customer customer, BigDecimal previousLimit, BigDecimal newLimit) {
        if (previousLimit == newLimit) return

        if (newLimit < previousLimit) {
            setCreditCardPaymentsAsNotAnticipableIfAboveLimit(customer)
            return
        }

        paymentAnticipableInfoAsyncService.saveCreditCardAnticipableAsAwaitingAnalysisIfNecessary(customer, ["value[le]": AsaasApplicationHolder.applicationContext.customerReceivableAnticipationConfigService.calculateCreditCardAvailableLimit(customer)])
    }

    public void onCustomerAccountInfoUpdated(CustomerAccount customerAccount, List<String> updatedFields) {
        if (!updatedFields) return

        if (updatedFields.contains("phone") || updatedFields.contains("mobilePhone") || updatedFields.contains("name") || updatedFields.contains("cpfCnpj")) {
            paymentAnticipableInfoAsyncService.saveBankSlipAndPixAnticipableAsAwaitingAnalysisIfNecessary(customerAccount.provider, [paymentCustomerAccountId: customerAccount.id])
        }

        if (updatedFields.contains("name") || updatedFields.contains("cpfCnpj")) {
            paymentAnticipableInfoAsyncService.saveCreditCardAnticipableAsAwaitingAnalysisIfNecessary(customerAccount.provider, [paymentCustomerAccountId: customerAccount.id])
        }
    }

    public void onAddCustomerAccountInBlockList(CustomerAccount customerAccount) {
        Map search = [:]

        List<BillingType> billingTypeList = [BillingType.BOLETO]
        if (AbTestUtils.hasPixAnticipation(customerAccount.provider)) billingTypeList.add(BillingType.PIX)

        search."billingType[in]" = billingTypeList
        search.paymentStatus = PaymentStatus.PENDING
        search.paymentCustomerAccountId = customerAccount.id

        List<Long> paymentIdList = PaymentAnticipableInfo.query(search + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()

        setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.CUSTOMER_ACCOUNT_IN_BLOCK_LIST))
    }

    public void onAddCustomerAccountCpfCnpjInBlockList(String cpfRootCnpj) {
        Map search = [:]
        search."paymentStatus[in]" = PaymentStatus.listAnticipable()
        search += buildDefaultFiltersToUpdateAsAnticipableFalse()
        List<Long> paymentIdList = []

        if (CpfCnpjUtils.isCpf(cpfRootCnpj)) {
            search.paymentCustomerAccountCpfCnpj = cpfRootCnpj
            paymentIdList = PaymentAnticipableInfo.query(search).list()
        } else {
            List<Long> customerAccountEconomicGroupIdList = CustomerAccount.economicGroup(cpfRootCnpj, [column: "id", disableSort: true]).list()
            final Integer maxCustomerAccountsPerSearch = 3000
            for (List<Long> customerAccountIdList : customerAccountEconomicGroupIdList.collate(maxCustomerAccountsPerSearch)) {
                search."paymentCustomerAccountId[in]" = customerAccountIdList
                paymentIdList += PaymentAnticipableInfo.query(search).list()
            }
        }

        setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.CUSTOMER_ACCOUNT_IN_BLOCK_LIST))
    }

    public void onRemoveCustomerAccountFromBlockListReasonBankSlipOverdue(CustomerAccount customerAccount) {
        Map search = [paymentCustomerAccountId: customerAccount.id]

        paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysis(BillingType.BOLETO, search)
        paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysis(BillingType.PIX, search)
    }

    public void onRemoveCustomerAccountFromBlockListReasonAnticipationOverdue(String cpfRootCnpj) {
        if (CpfCnpjUtils.isCpf(cpfRootCnpj)) {
            paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysisForAllAnticipableBillingTypes(["paymentCustomerAccountCpfCnpj": cpfRootCnpj])
        } else {
            List<Long> customerAccountEconomicGroupIdList = CustomerAccount.economicGroup(cpfRootCnpj, [column: "id", disableSort: true]).list()
            final Integer maxCustomerAccountsPerUpdate = 3000
            for (List<Long> customerAccountIdList : customerAccountEconomicGroupIdList.collate(maxCustomerAccountsPerUpdate)) {
                paymentAnticipableInfoService.updateAnticipableAsAwaitingAnalysisForAllAnticipableBillingTypes(["paymentCustomerAccountId[in]": customerAccountIdList])
            }
        }
    }

    public void processAnticipableWhenCustomerDebitOverduePartnerSettlement() {
        final Integer maxItemsPerCycle = 100
        final Integer flushEvery = 10

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SET_PAYMENTS_ANTICIPABLE_WHEN_CUSTOMER_DEBIT_OVERDUE_SETTLEMENTS, maxItemsPerCycle)
        if (!asyncActionDataList) return

        Utils.forEachWithFlushSession(asyncActionDataList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(Utils.toLong(asyncActionData.customerId))
                Boolean isCustomerWithPartnerSettlementAwaitingCreditForTooLong = ReceivableAnticipationValidationClosures.isCustomerWithPartnerSettlementAwaitingCreditForTooLong(customer)

                if (!isCustomerWithPartnerSettlementAwaitingCreditForTooLong) {
                    paymentAnticipableInfoAsyncService.saveAnticipableAsAwaitingAnalysisIfNecessary(customer, [:])
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ReceivableAnticipationValidationService.processAnticipableWhenCustomerDebitOverduePartnerSettlement >> Falha ao processar anticipable para clientes que pagaram antecipação vencida [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) } ])
        })
    }

    public void onLegalPersonHasFirstTransferConfirmed(Customer customer) {
        if (!customer.isLegalPerson()) return

        notifyOnFirstTransferReachesMinimumPeriodToAnticipate(customer, BillingType.MUNDIPAGG_CIELO)
        onCustomerChange(customer)
        receivableAnticipationValidationCacheService.evictIsFirstUse(customer.id)
    }

    public Boolean processPaymentsFromCustomersWithOverdueSettlementsAsyncAction() {
        final Integer maxItemsPerCycle = 100

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SET_PAYMENTS_FROM_CUSTOMER_WITH_OVERDUE_SETTLEMENTS_AS_NOT_ANTICIPABLE, maxItemsPerCycle)
        if (!asyncActionDataList) return false

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)

                if (!ReceivableAnticipationValidationClosures.isCustomerWithPartnerSettlementAwaitingCreditForTooLong(customer)) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                List<Long> paymentIdList = listPaymentsInAllowedTimeNotAnticipatedFromCustomer(customer)
                if (paymentIdList) {
                    setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.CUSTOMER_WITH_AWAITING_CREDIT))
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ReceivableAnticipationValidationService.processPaymentsFromCustomersWithOverdueSettlementsAsyncAction >> Falha ao processar asyncAction para atualizar cobranças de customer com antecipação vencida [${asyncActionData.asyncActionId}]", onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }

        return asyncActionDataList.size() == maxItemsPerCycle
    }

    public void updateAsNotAnticipableOnLimitDecrease(BigDecimal customerAvailableAnticipationLimit, Customer customer, BillingType billingType, CustomerAccount customerAccount) {
        if (billingType.isCreditCard()) {
            List<Long> paymentIdList = PaymentAnticipableInfo.query([customerId: customer.id,
                                                                     billingType: BillingType.MUNDIPAGG_CIELO,
                                                                     paymentStatus: PaymentStatus.CONFIRMED,
                                                                     "value[gt]": customerAvailableAnticipationLimit] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()

            setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.VALUE_ABOVE_CREDIT_CARD_AVAILABLE_LIMIT))
            return
        }

        PaymentNonAnticipableReason nonAnticipableReason = PaymentNonAnticipableReason.CUSTOMER_WITHOUT_BANK_SLIP_LIMIT
        PaymentNonAnticipableReason customerAccountReason = PaymentNonAnticipableReason.CUSTOMER_ACCOUNT_WITHOUT_LIMIT

        if (AbTestUtils.hasPixAnticipation(customer)) {
            customerAccountReason = PaymentNonAnticipableReason.CUSTOMER_ACCOUNT_WITHOUT_BANK_SLIP_AND_PIX_LIMIT
            nonAnticipableReason = PaymentNonAnticipableReason.CUSTOMER_WITHOUT_BANK_SLIP_AND_PIX_LIMIT
        }

        List<Long> paymentIdList = PaymentAnticipableInfo.query([customerId: customer.id,
                                                                 "billingType[in]": [BillingType.BOLETO, BillingType.PIX],
                                                                 paymentStatus: PaymentStatus.PENDING,
                                                                 "value[gt]": customerAvailableAnticipationLimit] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()
        setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(nonAnticipableReason))

        ReceivableAnticipationCustomerAccountConfig customerAccountConfig = ReceivableAnticipationCustomerAccountConfig.query([cpfCnpj: customerAccount.cpfCnpj]).get()
        if (!customerAccountConfig) return

        BigDecimal customerAccountAvailableLimit = customerAccountConfig.getAvailableLimit()
        List<Long> paymentIdsFromCustomerAccountCpfCnpjList = PaymentAnticipableInfo.query(["paymentCustomerAccountCpfCnpj": customerAccountConfig.cpfCnpj,
                                                                                            "billingType[in]": [BillingType.BOLETO, BillingType.PIX],
                                                                                            paymentStatus: PaymentStatus.PENDING,
                                                                                            "value[gt]": customerAccountAvailableLimit] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()
        setAsNotAnticipableAndSchedulable(paymentIdsFromCustomerAccountCpfCnpjList, new ReceivableAnticipationNonAnticipableReasonVO(customerAccountReason))
    }

    public void setAsNotAnticipableAndSchedulable(List<Long> paymentIdList, ReceivableAnticipationNonAnticipableReasonVO nonAnticipableReason) {
        if (!paymentIdList) return

        final Integer maxPaymentsPerUpdate = 800
        for (List<Long> idList : paymentIdList.collate(maxPaymentsPerUpdate)) {
            paymentAnticipableInfoService.bulkProcessPaymentsByReason(idList, nonAnticipableReason)
        }

        paymentAnticipableInfoService.expireCustomerTotalValueAvailableForAnticipation(paymentIdList)
    }

    public void setAnticipableAndSchedulable(List<Long> paymentIdList, ReceivableAnticipationNonAnticipableReasonVO nonAnticipableReason) {
        if (!paymentIdList) return

        final Integer maxPaymentsPerUpdate = 800
        for (List<Long> idList : paymentIdList.collate(maxPaymentsPerUpdate)) {
            paymentAnticipableInfoService.bulkSetAnticipableAndSchedulable(idList, nonAnticipableReason)
        }

        paymentAnticipableInfoService.expireCustomerTotalValueAvailableForAnticipation(paymentIdList)
    }

    public void setSchedulable(Payment payment, ReceivableAnticipationNonAnticipableReasonVO cannotScheduleAnticipationReason) {
        if (!cannotScheduleAnticipationReason) {
            paymentAnticipableInfoService.setAsSchedulable(payment.id)
        } else {
            paymentAnticipableInfoService.setAsNotSchedulable(payment.id, cannotScheduleAnticipationReason)
        }
    }

    public ReceivableAnticipationNonAnticipableReasonVO getCannotScheduleAndAnticipateReasonForPaymentIfExists(Payment payment) {
        ReceivableAnticipationNonAnticipableReasonVO reasonVO = validatePaymentAnticipable(payment)
        if (reasonVO) return reasonVO

        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(payment.id)
        if (paymentAnticipableInfo) {
            if (paymentAnticipableInfo.status.isAwaitingAnalysis()) return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_AWAITING_ANALYSIS)
            if (paymentAnticipableInfo.anticipable || paymentAnticipableInfo.schedulable) return null
            if (paymentAnticipableInfo.nonAnticipableReason) return new ReceivableAnticipationNonAnticipableReasonVO(paymentAnticipableInfo.nonAnticipableReason, paymentAnticipableInfo.nonAnticipableDescription)
        }

        return processValidationsForPayment(payment)
    }

    public ReceivableAnticipationNonAnticipableReasonVO getCannotScheduleAndAnticipateReasonForInstallmentIfExists(Installment installment) {
        if (installment.billingType.isBoleto()) {
            List<Payment> paymentList = installment.getNotDeletedPayments()

            if (paymentList.every { it.anticipated }) return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.INSTALLMENT_ALL_ANTICIPATED)
            if (!paymentList.any { it.canScheduleAnticipation() }) return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.INSTALLMENT_HAS_NO_ANTICIPABLE_PAYMENT)
        } else {
            if (!installment.canScheduleAnticipation()) return new ReceivableAnticipationValidator(false).canSchedule(installment)?.first()
        }

        List<PaymentAnticipableInfo> paymentAnticipableInfoList = PaymentAnticipableInfo.findByInstallmentId(installment.id)
        if (paymentAnticipableInfoList && paymentAnticipableInfoList.every { it.status.isAwaitingAnalysis() }) return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.INSTALLMENT_AWAITING_ANALYSIS)

        return null
    }

    public ReceivableAnticipationNonAnticipableReasonVO validatePaymentAnticipable(Payment payment) {
        ReceivableAnticipationNonAnticipableReasonVO anticipationIsEnabledReasonVO = ReceivableAnticipationValidationClosures.anticipationIsEnabled(payment)
        if (anticipationIsEnabledReasonVO) return anticipationIsEnabledReasonVO

        if (payment.deleted) {
            return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_DELETED)
        }

        if (payment.anticipated) {
            if (payment.getCurrentAnticipation().status.isScheduled()) return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_HAS_SCHEDULED_ANTICIPATION)

            return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_HAS_ALREADY_ANTICIPATED)
        }

        if (payment.billingType.isBoletoOrPix() && AbTestUtils.hasPixAnticipation(payment.provider)) {
            if (payment.isReceivingProcessInitiated()) {
                return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.BANK_SLIP_AND_PIX_PAYMENT_IN_RECEIVING_PROCESS)
            }
        } else {
            if (payment.billingType.isBoleto() && payment.isReceivingProcessInitiated()) {
                return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.BANK_SLIP_PAYMENT_IN_RECEIVING_PROCESS)
            }

            if (payment.billingType.isPix()) {
                return new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_BILLING_TYPE_PIX)
            }
        }

        ReceivableAnticipationNonAnticipableReasonVO reasonVO = ReceivableAnticipationValidationClosures.validatePaymentStatus(payment)
        if (reasonVO) return reasonVO

        return null
    }

    public void updatePaymentsWhenEnableAnticipation(List<Long> paymentIdList) {
        if (!paymentIdList) return

        final Integer flushEvery = 200
        Utils.forEachWithFlushSession(paymentIdList, flushEvery, { Long paymentId ->
            PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
            if (paymentAnticipableInfo) return

            Payment payment = Payment.get(paymentId)
            paymentAnticipableInfoService.save(payment)
            paymentAnticipableInfoService.sendToAnalysisQueue(paymentId)
        })
    }

    public void processValidationsForInstallment(Installment installment) {
        if (installment.deleted) {
            List<Long> paymentIdList = installment.getNotDeletedPayments().collect { it.id }
            setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_DELETED, null))
            return
        }

        List<Payment> paymentList = installment.getNotDeletedPayments()
        for (Payment payment : paymentList) {
            processValidationsForPayment(payment)
        }
    }

    public ReceivableAnticipationNonAnticipableReasonVO processValidationsForPayment(Payment payment) {
        ReceivableAnticipationNonAnticipableReasonVO cannotAnticipateReason = payment.cannotScheduleAnticipationReason()
        if (!cannotAnticipateReason) {
            cannotAnticipateReason = payment.cannotAnticipateIfSchedulableReason()
            setAnticipable(payment, cannotAnticipateReason)
            if (cannotAnticipateReason) setSchedulable(payment, null)
        } else {
            setSchedulable(payment, cannotAnticipateReason)
        }

        return cannotAnticipateReason
    }

    private void validatePaymentAndUpdateAnticipableProperty(Payment payment) {
        ReceivableAnticipationNonAnticipableReasonVO reasonVO = validatePaymentAnticipable(payment)
        if (reasonVO) {
            setSchedulable(payment, reasonVO)
            return
        }

        processValidationsForPayment(payment)
    }

    private void setAnticipable(Payment payment, ReceivableAnticipationNonAnticipableReasonVO cannotAnticipateReason) {
        if (!cannotAnticipateReason) {
            paymentAnticipableInfoService.setAsAnticipable(payment.id)
        } else {
            paymentAnticipableInfoService.setAsNotAnticipable(payment.id, cannotAnticipateReason)
        }

        CustomerStatistic.expireTotalValueAvailableForAnticipation(payment.provider)
    }

    private void setCreditCardPaymentsAsNotAnticipableIfAboveLimit(Customer customer) {
        BigDecimal limitValueToAnticipate = AsaasApplicationHolder.applicationContext.customerReceivableAnticipationConfigService.calculateCreditCardAvailableLimit(customer)

        List<Long> notAnticipableAnymorePaymentIdList = PaymentAnticipableInfo.query([customerId: customer.id,
                                                                                      billingType: BillingType.MUNDIPAGG_CIELO,
                                                                                      "value[gt]": limitValueToAnticipate,
                                                                                      paymentStatus: PaymentStatus.CONFIRMED] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()

        setAsNotAnticipableAndSchedulable(notAnticipableAnymorePaymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.VALUE_ABOVE_CREDIT_CARD_AVAILABLE_LIMIT))
    }

    private List<Long> listPaymentsInMinimumDateAllowedNotAnticipatedFromCustomerByBillingType(Long customerId, BillingType billingType) {
        Map searchParams = [
            column: "payment.id",
            anticipable: true,
            anticipated: false,
            billingType: billingType,
            customerId: customerId,
            disableSort: true
        ]

        if (billingType.isCreditCard()) {
            searchParams."paymentCreditDate[ge]" = ReceivableAnticipation.getMinimumDateAllowed()
            searchParams.paymentStatus = PaymentStatus.CONFIRMED
        } else {
            searchParams."dueDate[ge]" = ReceivableAnticipation.getMinimumDateAllowed()
            searchParams.paymentStatus = PaymentStatus.PENDING
        }

        return PaymentAnticipableInfo.query(searchParams).list()
    }

    private Map buildDefaultFiltersToUpdateAsAnticipableFalse() {
        return [
            column: "payment.id",
            status: PaymentAnticipableInfoStatus.ANALYZED,
            anticipable: true,
            anticipated: false,
            disableSort: true
        ]
    }

    private List<Long> listPaymentsInAllowedTimeNotAnticipatedFromCustomer(Customer customer) {
        List<Long> paymentIdList = []
        List<BillingType> billingTypesToSearch = [BillingType.BOLETO, BillingType.MUNDIPAGG_CIELO]
        if (AbTestUtils.hasPixAnticipation(customer)) billingTypesToSearch.add(BillingType.PIX)

        for (BillingType billingType : billingTypesToSearch) {
            List<Long> paymentIdListByBillingType = listPaymentsInMinimumDateAllowedNotAnticipatedFromCustomerByBillingType(customer.id, billingType)
            paymentIdList.addAll(paymentIdListByBillingType)
        }

        return paymentIdList
    }

    private Boolean canSendAnticipationNotification(Customer customer, BillingType billingType) {
        if (!customer.hasCreatedPayments()) return false
        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return false
        if (!customer.canAnticipate()) return false
        if (billingType.isBoleto() && !customer.canAnticipateBoleto()) return false
        if (billingType.isCreditCard() && !customer.canAnticipateCreditCard()) return false

        return true
    }

    private void onBankSlipAnticipationLimitIncrease(Customer customer, BigDecimal previousLimit) {
        BigDecimal oldAvailableAnticipationLimit = previousLimit - ReceivableAnticipationCompromisedValueCache.getCompromisedValueForBankSlipAndPix(customer)
        oldAvailableAnticipationLimit = BigDecimalUtils.max(oldAvailableAnticipationLimit, 0.0)

        paymentAnticipableInfoAsyncService.saveBankSlipAndPixAnticipableAsAwaitingAnalysisIfNecessary(customer, ["value[gt]": oldAvailableAnticipationLimit])
    }

    private void onBankSlipAnticipationLimitDecrease(Customer customer) {
        BigDecimal availableAnticipationLimit = AsaasApplicationHolder.applicationContext.customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(customer)
        List<Long> paymentIdList = PaymentAnticipableInfo.query([customerId: customer.id,
                                                                 "billingType[in]": [BillingType.BOLETO, BillingType.PIX],
                                                                 "value[gt]": availableAnticipationLimit,
                                                                 paymentStatus: PaymentStatus.PENDING] + buildDefaultFiltersToUpdateAsAnticipableFalse()).list()

        PaymentNonAnticipableReason nonAnticipableReason = AbTestUtils.hasPixAnticipation(customer)
            ? PaymentNonAnticipableReason.CUSTOMER_WITHOUT_BANK_SLIP_AND_PIX_LIMIT
            : PaymentNonAnticipableReason.CUSTOMER_WITHOUT_BANK_SLIP_LIMIT

        setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(nonAnticipableReason))
    }
}
