package com.asaas.service.receivableanticipationpartner

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.customeraccountstatistic.CustomerAccountStatisticName
import com.asaas.customerstatistic.CustomerStatisticName
import com.asaas.domain.asyncAction.AnticipationDebitOverdueSettlementAsyncAction
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.customeraccount.CustomerAccountStatistic
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItem
import com.asaas.exception.ResourceNotFoundException
import com.asaas.exception.receivableanticipation.ReceivableAnticipationDuplicatedSettlementException
import com.asaas.log.AsaasLogger
import com.asaas.receivableanticipation.ReceivableAnticipationPartnerSettlementDebitVO
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItemStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItemType
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationPartnerSettlementService {

    def asyncActionService
    def baseAsyncActionService
    def financialTransactionService
    def internalLoanService
    def mandatoryCustomerAccountNotificationService
    def receivableAnticipationValidationCacheService
    def receivableAnticipationVortxSettlementService

    public void savePaymentFeeRefundValue(ReceivableAnticipationPartnerAcquisition partnerAcquisition, Payment payment) {
        ReceivableAnticipationPartnerSettlement partnerSettlement = ReceivableAnticipationPartnerSettlement.query([partnerAcquisition: partnerAcquisition, payment: payment]).get()
        if (!partnerSettlement) return

        partnerSettlement.paymentFeeRefundValue = payment.getAsaasValue()
        partnerSettlement.save(failOnError: true)
    }

    public void processPendingAnticipationDebitOverdueSettlement() {
        final Integer maxNumberOfGroupIdPerExecution = 400
        final Integer maxAsyncActions = 100
        final Integer numberOfThreads = 4
        final Integer delayInSeconds = 2

        List<Long> groupIdList = AnticipationDebitOverdueSettlementAsyncAction.query([distinct: "groupId",
                                                                                      status: AsyncActionStatus.PENDING,
                                                                                      "dateCreated[lt]": CustomDateUtils.sumSeconds(new Date(), delayInSeconds * -1),
                                                                                      disableSort: true,
                                                                                      includeDeleted: true]).list(max: maxNumberOfGroupIdPerExecution)

        Utils.processWithThreads(groupIdList, numberOfThreads, { List<String> subGroupIdList ->
            Map subGroupSearch = ["groupId[in]": subGroupIdList, includeDeleted: true]
            List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(AnticipationDebitOverdueSettlementAsyncAction, subGroupSearch, maxAsyncActions)
            if (!asyncActionDataList) return

            baseAsyncActionService.processListWithNewTransaction(AnticipationDebitOverdueSettlementAsyncAction, asyncActionDataList, { Map asyncActionData ->
                if (asyncActionData.paymentId) return executeAnticipationDebitOverdueSettlementAsyncAction(asyncActionData.paymentId)

                if (asyncActionData.pixTransactionId) {
                    PixTransaction pixTransaction = PixTransaction.read(asyncActionData.pixTransactionId)
                    debitOverdueSettlements(pixTransaction.customer, pixTransaction)
                    return
                }
            })
        })
    }

    public void syncSettlements() {
        receivableAnticipationVortxSettlementService.syncSettlements()
    }

    public void save(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        List<ReceivableAnticipationPartnerAcquisitionItem> partnerAcquisitionItemList = partnerAcquisition.getPartnerAcquisitionItemList()

        for (ReceivableAnticipationPartnerAcquisitionItem partnerAcquisitionItem : partnerAcquisitionItemList) {
            ReceivableAnticipationPartnerSettlement partnerSettlement = new ReceivableAnticipationPartnerSettlement()
            partnerSettlement.partnerAcquisition = partnerAcquisition
            partnerSettlement.payment = partnerAcquisitionItem.payment
            partnerSettlement.status = ReceivableAnticipationPartnerSettlementStatus.AWAITING_RECEIVABLE_ANTICIPATION_DEBIT
            partnerSettlement.value = partnerAcquisitionItem.totalValue
            partnerSettlement.save(failOnError: true)
        }
    }

    public ReceivableAnticipationPartnerSettlement debit(ReceivableAnticipationPartnerAcquisition partnerAcquisition, Payment payment, BigDecimal maxSettlementValue, Map options) {
        ReceivableAnticipationPartnerSettlement partnerSettlement = ReceivableAnticipationPartnerSettlement.query([partnerAcquisition: partnerAcquisition, payment: payment]).get()
        if (partnerSettlement && !partnerSettlement.status.isAwaitingReceivableAnticipationDebit()) throw new ReceivableAnticipationDuplicatedSettlementException("Cobrança já liquidada para essa aquisição. [payment: ${payment.id}, acquisition: ${partnerAcquisition.id}]")

        BigDecimal settledValue = getSettledValueToPartner(partnerAcquisition)
        BigDecimal minimumSettlementExpectedValue = partnerAcquisition.getMinimumSettlementExpectedValue()

        BigDecimal settlementValue = minimumSettlementExpectedValue - settledValue
        if (maxSettlementValue) settlementValue = BigDecimalUtils.min(settlementValue, maxSettlementValue)

        Boolean canFinishPartnerAcquisition = (settledValue + settlementValue) >= minimumSettlementExpectedValue

        if (!partnerSettlement) {
            partnerSettlement = new ReceivableAnticipationPartnerSettlement()
            partnerSettlement.partnerAcquisition = partnerAcquisition
            partnerSettlement.payment = payment
            partnerSettlement.value = settlementValue
        }

        Boolean wasAwaitingReceivableAnticipationDebit = partnerSettlement.status?.isAwaitingReceivableAnticipationDebit() ?: false

        partnerSettlement.status = ReceivableAnticipationPartnerSettlementStatus.AWAITING_CREDIT
        partnerSettlement.debitDate = new Date().clearTime()
        partnerSettlement.valueToPartner = settlementValue

        if (partnerSettlement.partnerAcquisition.partner.isVortx()) {
            partnerSettlement.unpaidValueToPartner = settlementValue
        } else {
            partnerSettlement.unpaidValueToPartner = 0
        }

        partnerSettlement.save(failOnError: true)

        receivableAnticipationValidationCacheService.evictIsCustomerWithPartnerSettlementAwaitingCredit(partnerAcquisition.customer.id)

        BigDecimal customerCurrentBalance = FinancialTransaction.getCustomerBalance(partnerAcquisition.customer.id)

        if (payment && options.consumeFromPayment) {
            asyncActionService.saveCreateReceivableAnticipationPartnerSettlementItem(partnerSettlement, [paymentId: payment.id, maxSettlementValue: settlementValue, partnerSettlementId: partnerSettlement.id])
        } else if (customerCurrentBalance > 0) {
            asyncActionService.saveCreateReceivableAnticipationPartnerSettlementItem(partnerSettlement, [maxSettlementValue: BigDecimalUtils.min(settlementValue, customerCurrentBalance), partnerSettlementId: partnerSettlement.id])
        }

        if (canFinishPartnerAcquisition) {
            partnerAcquisition.status = ReceivableAnticipationPartnerAcquisitionStatus.DEBITED
            partnerAcquisition.save(failOnError: true)
        }

        String description

        if (partnerAcquisition.receivableAnticipation.installment) {
            description = "Baixa da parcela ${payment.installmentNumber} (fatura nr. ${payment.getInvoiceNumber()}) da antecipação do parcelamento - fatura nr. ${partnerAcquisition.receivableAnticipation.installment.getInvoiceNumber()} ${partnerAcquisition.receivableAnticipation.customerAccount.name}"
        } else {
            description = "Baixa da antecipação - fatura nr. ${partnerAcquisition.receivableAnticipation.payment.getInvoiceNumber()} ${partnerAcquisition.receivableAnticipation.payment.customerAccount.name}"
        }

        FinancialTransaction financialTransaction = financialTransactionService.saveReceivableAnticipationPartnerSettlement(partnerSettlement, payment, description, wasAwaitingReceivableAnticipationDebit)
        internalLoanService.saveIfNecessary(financialTransaction)

        return partnerSettlement
    }

    public void debitOverdueSettlements(Customer customer, Object debitMethod) {
        List<ReceivableAnticipationPartnerSettlement> partnerSettlementList = []

        if (debitMethod instanceof Payment) {
            Boolean isAffectedByExternalContractualEffect = CercContractualEffect.getExternalActiveContractBeneficiaryCpfCnpj(debitMethod).asBoolean()
            if (isAffectedByExternalContractualEffect) return

            partnerSettlementList = listAwaitingCredit(customer, debitMethod)
        } else {
            partnerSettlementList = listAwaitingCredit(customer, null)
        }

        if (!partnerSettlementList) return

        createReceivableAnticipationPartnerSettlementItem(partnerSettlementList, new ReceivableAnticipationPartnerSettlementDebitVO(debitMethod))
    }

    public void expireTotalValueDebitedWithPartner() {
        List<ReceivableAnticipationPartnerAcquisitionStatus> partnerAcquisitionStatusList = ReceivableAnticipationPartnerAcquisitionStatus.getNotCompromisedStatusList()

        final Date lastFidcSettlementDate = CustomDateUtils.todayMinusBusinessDays(ReceivableAnticipationPartnerSettlement.PARTNER_BUSINESS_DAYS_TAKEN_TO_SETTLE_ANTICIPATION).getTime()
        List<Long> customerIdList = ReceivableAnticipationPartnerSettlementItem.createCriteria().list(max: 500) {
            projections {
                distinct "partnerAcquisition.customer.id"
            }

            createAlias("partnerSettlement", "partnerSettlement")
            createAlias("partnerSettlement.partnerAcquisition", "partnerAcquisition")

            eq("paid", true)
            eq("deleted", false)
            between("partnerSettlement.paidDate", lastFidcSettlementDate, CustomDateUtils.setTimeToEndOfDay(lastFidcSettlementDate))
            not { 'in'("partnerAcquisition.status", partnerAcquisitionStatusList) }

            exists CustomerStatistic.where {
                setAlias("customerStatistic")
                eqProperty("customerStatistic.customer.id", "partnerAcquisition.customer.id")
                eq("customerStatistic.name", CustomerStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER)
                le("customerStatistic.lastUpdated", new Date().clearTime())
            }.id()
        }

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerStatistic.expire(Customer.get(customerId), CustomerStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER)
            }, [logErrorMessage: "expireTotalValueDebitedWithPartner >> Erro ao salvar expirar customerStatistic: ${CustomerStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER} do customer: [${customerId}]"])
        })
    }

    public void expireCustomerAccountCpfCnpjTotalValueDebitedWithPartner() {
        List<ReceivableAnticipationPartnerAcquisitionStatus> partnerAcquisitionStatusList = ReceivableAnticipationPartnerAcquisitionStatus.getNotCompromisedStatusList()

        final Date lastFidcSettlementDate = CustomDateUtils.todayMinusBusinessDays(ReceivableAnticipationPartnerSettlement.PARTNER_BUSINESS_DAYS_TAKEN_TO_SETTLE_ANTICIPATION).getTime()
        List<String> customerAccountCpfCnpjList = ReceivableAnticipationPartnerSettlementItem.createCriteria().list(max: 500) {
            projections {
                distinct "partnerAcquisition.customerAccountCpfCnpj"
            }

            createAlias("partnerSettlement", "partnerSettlement")
            createAlias("partnerSettlement.partnerAcquisition", "partnerAcquisition")

            eq("paid", true)
            eq("deleted", false)

            between("partnerSettlement.paidDate", lastFidcSettlementDate, CustomDateUtils.setTimeToEndOfDay(lastFidcSettlementDate))

            not { 'in'("partnerAcquisition.status", partnerAcquisitionStatusList) }

            exists CustomerAccountStatistic.where {
                setAlias("customerAccountStatistic")

                eqProperty("customerAccountStatistic.cpfCnpj", "partnerAcquisition.customerAccountCpfCnpj")

                eq("customerAccountStatistic.name", CustomerAccountStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER)
                le("customerAccountStatistic.lastUpdated", new Date().clearTime())
            }.id()
        }

        Utils.forEachWithFlushSession(customerAccountCpfCnpjList, 50, { String customerAccountCpfCnpj ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerAccountStatistic.expire(customerAccountCpfCnpj, CustomerAccountStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER)
            }, [logErrorMessage: "expireCustomerAccountCpfCnpjTotalValueDebitedWithPartner >> Erro ao expirar CustomerAccountStatistic: ${CustomerAccountStatisticName.TOTAL_VALUE_DEBITED_WITH_ANTICIPATION_PARTNER} do cpfCnpj: [${customerAccountCpfCnpj}]"])
        })
    }

    public ReceivableAnticipationPartnerSettlementItemType saveItem(ReceivableAnticipationPartnerSettlement partnerSettlement, BigDecimal value, Map debitInfo) {
        ReceivableAnticipationPartnerSettlementItem partnerSettlementItem = new ReceivableAnticipationPartnerSettlementItem()
        partnerSettlementItem.partnerSettlement = partnerSettlement

        if (partnerSettlement.partnerAcquisition.partner.isVortx()) {
            partnerSettlementItem.paid = false
            partnerSettlementItem.status = ReceivableAnticipationPartnerSettlementItemStatus.READY_FOR_BATCH
        } else {
            partnerSettlementItem.paid = true
            partnerSettlementItem.status = ReceivableAnticipationPartnerSettlementItemStatus.SENT
        }

        partnerSettlementItem.value = value
        partnerSettlementItem.payment = debitInfo.payment
        partnerSettlementItem.internalTransfer = debitInfo.internalTransfer
        partnerSettlementItem.pixTransaction = debitInfo.pixTransaction
        partnerSettlementItem.save(failOnError: true)

        if (partnerSettlement.getAwaitingPaymentValueToPartner()) {
            partnerSettlementItem.type = ReceivableAnticipationPartnerSettlementItemType.PARTIAL
            partnerSettlementItem.save(failOnError: true)
        } else {
            partnerSettlementItem.type = ReceivableAnticipationPartnerSettlementItemType.FULL
            partnerSettlementItem.save(failOnError: true)

            partnerSettlement.status = ReceivableAnticipationPartnerSettlementStatus.PAID
            partnerSettlement.paidDate = new Date().clearTime()
            partnerSettlement.save(failOnError: true)

            mandatoryCustomerAccountNotificationService.enableCustomerAccountNotificationsUpdateIfPossible(partnerSettlement.partnerAcquisition.receivableAnticipation.customerAccount)

            processPartnerSettlementWasAwaitingCreditForTooLongIfNecessary(partnerSettlement)
        }

        return partnerSettlementItem.type
    }

    private List<ReceivableAnticipationPartnerSettlement> listAwaitingCredit(Customer customer, Payment payment) {
        List<ReceivableAnticipationPartnerSettlement> partnerSettlementList = []
        if (payment) partnerSettlementList.addAll(ReceivableAnticipationPartnerSettlement.awaitingCredit([payment: payment, customer: payment.provider]).list())
        partnerSettlementList.addAll(ReceivableAnticipationPartnerSettlement.awaitingCredit([customer: customer, sort: "id", order: "asc", partner: ReceivableAnticipationPartner.VORTX]).list())
        partnerSettlementList.addAll(ReceivableAnticipationPartnerSettlement.awaitingCredit([customer: customer, sort: "id", order: "asc", partner: ReceivableAnticipationPartner.OCEAN]).list())
        partnerSettlementList.addAll(ReceivableAnticipationPartnerSettlement.awaitingCredit([customer: customer, sort: "id", order: "asc", partner: ReceivableAnticipationPartner.ASAAS]).list())

        if (!partnerSettlementList) return

        return partnerSettlementList.unique { it.id }
    }

    private void createReceivableAnticipationPartnerSettlementItem(List<ReceivableAnticipationPartnerSettlement> partnerSettlementList, ReceivableAnticipationPartnerSettlementDebitVO partnerSettlementDebitVO) {
        for (ReceivableAnticipationPartnerSettlement partnerSettlement : partnerSettlementList) {
            if (!partnerSettlementDebitVO.remainingValue) break

            BigDecimal pendingSettlementValue = partnerSettlement.getAwaitingPaymentValueToPartner()
            if (pendingSettlementValue <= 0) continue

            BigDecimal settlementItemValue = BigDecimalUtils.min(pendingSettlementValue, partnerSettlementDebitVO.remainingValue)
            Map actionData = partnerSettlementDebitVO.buildDebitMethodInfo() + [partnerSettlementId: partnerSettlement.id, maxSettlementValue: settlementItemValue]
            asyncActionService.saveCreateReceivableAnticipationPartnerSettlementItem(partnerSettlement, actionData)

            partnerSettlementDebitVO.remainingValue = partnerSettlementDebitVO.remainingValue - settlementItemValue
        }
    }

    private void processPartnerSettlementWasAwaitingCreditForTooLongIfNecessary(ReceivableAnticipationPartnerSettlement partnerSettlement) {
        if (!partnerSettlement.status.isPaid()) return
        if (partnerSettlement.debitDate > ReceivableAnticipationValidationClosures.getLimitDateCustomerWithPartnerSettlementAwaitingCredit()) return

        receivableAnticipationValidationCacheService.evictIsCustomerWithPartnerSettlementAwaitingCreditForTooLong(partnerSettlement.partnerAcquisition.customer.id)
        asyncActionService.saveSetPaymentAnticipableWhenCustomerDebitOverdueSettlements(partnerSettlement.partnerAcquisition.customer.id)
    }

    private BigDecimal getSettledValueToPartner(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        return ReceivableAnticipationPartnerSettlement.sumValueToPartnerAbs([partnerAcquisition: partnerAcquisition,
                                                                             "status[in]": ReceivableAnticipationPartnerSettlementStatus.getEligibleStatusToSettlementProcess()]).get()
    }

    private void executeAnticipationDebitOverdueSettlementAsyncAction(Long paymentId) {
        Payment payment = Payment.read(paymentId)
        if (!payment) throw new ResourceNotFoundException("Cobrança inexistente.")

        Boolean hasAnticipation = ReceivableAnticipation.query([exists: true, payment: payment, statusList: ReceivableAnticipationStatus.listDebitable()]).get().asBoolean()
        if (hasAnticipation) throw new RuntimeException("Existe uma antecipação válida para essa cobrança.")

        debitOverdueSettlements(payment.provider, payment)
    }
}
