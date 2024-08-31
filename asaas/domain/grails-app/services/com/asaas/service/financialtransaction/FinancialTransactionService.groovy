package com.asaas.service.financialtransaction

import com.asaas.asaascard.AsaasCardRechargeStatus
import com.asaas.asaascard.AsaasCardType
import com.asaas.asaascardbillpayment.AsaasCardBillPaymentMethod
import com.asaas.asaascardtransaction.AsaasCardTransactionType
import com.asaas.asaasmoney.AsaasMoneyCashbackStatus
import com.asaas.asaasmoney.AsaasMoneyChargedFeeStatus
import com.asaas.asaasmoney.AsaasMoneyPaymentCompromisedBalanceStatus
import com.asaas.asaasmoney.AsaasMoneyTransactionChargebackStatus
import com.asaas.asaasmoney.AsaasMoneyTransactionStatus
import com.asaas.asaasmoney.AsaasMoneyTransactionType
import com.asaas.bill.BillStatus
import com.asaas.billinginfo.BillingType
import com.asaas.chargedfee.ChargedFeeStatus
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.customer.CustomerParameterName
import com.asaas.customercommission.CustomerCommissionCheckoutStatus
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customerstatistic.CustomerStatisticName
import com.asaas.debit.DebitType
import com.asaas.debtrecovery.DebtRecoveryNegotiationPaymentStatus
import com.asaas.domain.asaascard.AsaasCardBalanceRefund
import com.asaas.domain.asaascard.AsaasCardCashback
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPaymentRefund
import com.asaas.domain.asaascardtransaction.AsaasCardTransaction
import com.asaas.domain.asaasmoney.AsaasMoneyCashback
import com.asaas.domain.asaasmoney.AsaasMoneyChargedFee
import com.asaas.domain.asaasmoney.AsaasMoneyPaymentCompromisedBalance
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionChargeback
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.bill.Bill
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.credit.Credit
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionCheckout
import com.asaas.domain.debit.Debit
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiationPayment
import com.asaas.domain.externalsettlement.ExternalSettlement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.financialtransaction.FinancialTransactionBatch
import com.asaas.domain.financialtransaction.FinancialTransactionPaymentRefund
import com.asaas.domain.financialtransactionasaascardbalancerefund.FinancialTransactionAsaasCardBalanceRefund
import com.asaas.domain.financialtransactionasaascardbillpayment.FinancialTransactionAsaasCardBillPayment
import com.asaas.domain.financialtransactionasaascardbillpayment.FinancialTransactionAsaasCardBillPaymentRefund
import com.asaas.domain.financialtransactionasaascardcashback.FinancialTransactionAsaasCardCashback
import com.asaas.domain.financialtransactionasaascardtransaction.FinancialTransactionAsaasCardTransaction
import com.asaas.domain.financialtransactionasaasmoneycashback.FinancialTransactionAsaasMoneyCashback
import com.asaas.domain.financialtransactionasaasmoneychargedfee.FinancialTransactionAsaasMoneyChargedFee
import com.asaas.domain.financialtransactionasaasmoneypaymentcompromisedbalance.FinancialTransactionAsaasMoneyPaymentCompromisedBalance
import com.asaas.domain.financialtransactionasaasmoneytransaction.FinancialTransactionAsaasMoneyTransaction
import com.asaas.domain.financialtransactionmobilephonerecharge.FinancialTransactionMobilePhoneRecharge
import com.asaas.domain.financialtransactionpaymentcustodyitem.FinancialTransactionPaymentCustodyItem
import com.asaas.domain.financialtransactionpixtransaction.FinancialTransactionPixTransaction
import com.asaas.domain.integration.bacen.bacenjud.JudicialLockItem
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.domain.contractualeffectsettlement.ContractualEffectSettlementItem
import com.asaas.domain.integration.cerc.contractualeffect.FinancialTransactionContractualEffectSettlement
import com.asaas.domain.financialtransactioncontractualeffectsettlementitem.FinancialTransactionContractualEffectSettlementItem
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.domain.payment.CustomerPaymentReceivedValueConsolidatedCache
import com.asaas.domain.payment.PartialPayment
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.paymentcustody.PaymentCustodyItem
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitConsumation
import com.asaas.domain.postalservice.PaymentPostalServiceBatch
import com.asaas.domain.promotionalcode.PromotionalCodeUse
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.exception.BusinessException
import com.asaas.externalsettlement.ExternalSettlementStatus
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.financialtransaction.querybuilder.FinancialTransactionQueryBuilder
import com.asaas.integration.bacen.bacenjud.JudicialLockItemType
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementStatus
import com.asaas.contractualeffectsettlement.enums.ContractualEffectSettlementItemStatus
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.internaltransfer.InternalTransferStatus
import com.asaas.log.AsaasLogger
import com.asaas.mobilephonerecharge.MobilePhoneRechargeRepository
import com.asaas.mobilephonerecharge.MobilePhoneRechargeStatus
import com.asaas.payment.CustomerPaymentReceivedValueConsolidatedCacheRepository
import com.asaas.payment.PaymentDunningStatus
import com.asaas.payment.PaymentStatus
import com.asaas.paymentcustody.PaymentCustodyItemType
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.postalservice.PostalServiceBatchStatus
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FinancialTransactionUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import datadog.trace.api.Trace
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional
import groovy.json.JsonOutput
import org.apache.commons.lang.time.DateUtils

@Transactional
class FinancialTransactionService {

    def asaasErpAccountingStatementService
    def asyncActionService
    def customerCheckoutLimitService
    def customerDailyBalanceConsolidationRecalculateService
    def customerDailyPartialBalanceRecalculateService
    def financialTransactionAfterSaveAsyncActionService
    def financialTransactionCustomerCommissionService
    def freePaymentUseService
    def invoiceItemService
    def promotionalCodeService
    def grailsLinkGenerator

    public BigDecimal getCurrentBalanceWithoutPartnerSettlement(Customer customer) {
        BigDecimal partnerSettlementValue = ReceivableAnticipationPartnerSettlement.getUnpaidValueByCustomer(customer, ReceivableAnticipationPartner.VORTX)
        return FinancialTransaction.getCustomerBalance(customer) + partnerSettlementValue
    }

    public void saveDebtRecoveryNegotiationPaymentFee(DebtRecoveryNegotiationPayment negotiationPayment) {
        save([provider: negotiationPayment.payment.provider,
            value: FinancialTransactionUtils.toDebitValue(negotiationPayment.chargeValue),
            payment: negotiationPayment.payment,
            transactionType: FinancialTransactionType.DEBT_RECOVERY_NEGOTIATION_FINANCIAL_CHARGES,
            description: "Encargos sobre renegociação - fatura nr. ${negotiationPayment.payment.getInvoiceNumber()} ${negotiationPayment.payment.customerAccount.name}",
            financialTransactionBatch: new FinancialTransactionBatch().save()])
    }

    public FinancialTransaction saveContractualEffectSettlement(CercContractualEffectSettlement settlement) {
        String description = "Valor em recebíveis reservado pela instituição ${CpfCnpjUtils.formatCpfCnpj(settlement.contractualEffect.beneficiaryCpfCnpj)} da cobrança ${settlement.receivableUnitItem.payment.getInvoiceNumber()}"

        return save([
            provider: settlement.customer,
            transactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT,
            value: FinancialTransactionUtils.toDebitValue(settlement.value),
            financialTransactionBatch: new FinancialTransactionBatch().save(),
            description: description
        ])
    }

    public FinancialTransaction saveContractualEffectSettlementItem(ContractualEffectSettlementItem settlementItem, String beneficiaryCpfCnpj) {
        String description = "Valor em recebíveis reservado pela instituição ${CpfCnpjUtils.formatCpfCnpj(beneficiaryCpfCnpj)} da cobrança ${settlementItem.payment.getInvoiceNumber()}"

        return save([
            provider: settlementItem.customer,
            transactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT,
            value: FinancialTransactionUtils.toDebitValue(settlementItem.value),
            financialTransactionBatch: new FinancialTransactionBatch().save(),
            description: description
        ])
    }

    public FinancialTransaction saveContractualEffectSettlementItemReversal(FinancialTransactionContractualEffectSettlementItem financialTransactionContractualEffectSettlementItem, String beneficiaryCpfCnpj) {
        Payment payment = financialTransactionContractualEffectSettlementItem.settlementItem.payment
        FinancialTransaction transactionToBeReversed = financialTransactionContractualEffectSettlementItem.financialTransaction
        String description = "Estorno do valor em recebíveis reservado pela instituição ${CpfCnpjUtils.formatCpfCnpj(beneficiaryCpfCnpj)} da cobrança ${payment.getInvoiceNumber()}"

        return save([
            provider: transactionToBeReversed.provider,
            transactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT_REVERSAL,
            value: FinancialTransactionUtils.toCreditValue(transactionToBeReversed.value),
            financialTransactionBatch: transactionToBeReversed.financialTransactionBatch,
            description: description
        ])
    }

    public FinancialTransaction saveContractualEffectSettlementReversal(FinancialTransaction transactionToBeReversed) {
        CercContractualEffectSettlement settlement = FinancialTransactionContractualEffectSettlement.query([
            column: "contractualEffectSettlement",
            financialTransactionId: transactionToBeReversed.id,
            financialTransactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT
        ]).get()
        String description = "Estorno do valor em recebíveis reservado pela instituição ${CpfCnpjUtils.formatCpfCnpj(settlement.contractualEffect.beneficiaryCpfCnpj)} da cobrança ${settlement.receivableUnitItem.payment.getInvoiceNumber()}"

        return save([
            provider: transactionToBeReversed.provider,
            transactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT_REVERSAL,
            value: FinancialTransactionUtils.toCreditValue(transactionToBeReversed.value),
            financialTransactionBatch: transactionToBeReversed.financialTransactionBatch,
            description: description
        ])
    }

    public FinancialTransaction saveExternalSettlementCredit(ExternalSettlement externalSettlement, String description) {
        return save([
            provider: externalSettlement.asaasCustomer,
            transactionType: externalSettlement.origin.convertToFinancialTransactionType(),
            value: externalSettlement.totalValue,
            financialTransactionBatch: new FinancialTransactionBatch().save(),
            description: description
        ])
    }

    public FinancialTransaction saveExternalSettlementCreditReversal(FinancialTransaction transactionToBeReversed, ExternalSettlement externalSettlement, String description) {
        return save([
            provider: externalSettlement.asaasCustomer,
            transactionType: externalSettlement.origin.convertToFinancialTransactionReversalType(),
            value: FinancialTransactionUtils.toDebitValue(transactionToBeReversed.value),
            financialTransactionBatch: new FinancialTransactionBatch().save(),
            reversedTransaction: transactionToBeReversed,
            description: description
        ])
    }

    public void delete(List<FinancialTransaction> financialTransactionList, Boolean flush) {
        for (FinancialTransaction financialTransaction : financialTransactionList) {
            if (financialTransaction.deleted) continue

            financialTransaction.deleted = true
            financialTransaction.save(flush: flush, failOnError: true)
        }

        Map<Customer, List<FinancialTransaction>> financialTransactionListGroupedByCustomer = financialTransactionList.groupBy { FinancialTransaction transaction -> transaction.provider }

        financialTransactionListGroupedByCustomer.each { Customer customer, List<FinancialTransaction> transactionList ->
            customerDailyPartialBalanceRecalculateService.recalculateWhenDeleteFinancialTransaction(customer)

            customerDailyBalanceConsolidationRecalculateService.saveAsyncActionWhenDeleteFinancialTransaction(customer, transactionList.transactionDate.min())

            recalculateBalance(customer)
        }
    }

    @Trace(resourceName = "FinancialTransactionService.recalculateBalance")
    public void recalculateBalance(Customer customer) {
        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customer)
        Map totalCreditValueMap = getTotalCreditValue(customer, [logged: false])
        Map totalDebitValueMap = getTotalDebitValue(customer, [logged: false])

        BigDecimal recalculatedBalance = totalCreditValueMap.totalCreditValueWithoutPaymentCache - totalDebitValueMap.totalDebitValueWithoutPaymentCache
        BigDecimal recalculatedBalanceUsingPaymentValueConsolidatedCache = totalCreditValueMap.totalCreditValueWithPaymentCache - totalDebitValueMap.totalDebitValueWithPaymentCache

        if (recalculatedBalance != recalculatedBalanceUsingPaymentValueConsolidatedCache) {
            AsaasLogger.warn("FinancialTransactionService.recalculateBalance >> O recálculo de saldo do cliente [${customer.id}] utilizando o cache das cobranças não bate com o recálculo padrão. Saldo atual [${currentBalance}] / recálculo padrão [${recalculatedBalance}] / recálculo com cache [${recalculatedBalanceUsingPaymentValueConsolidatedCache}]")
        }

        BigDecimal recalculateBalanceTolerance = CustomerParameter.getNumericValue(customer, CustomerParameterName.RECALCULATE_BALANCE_TOLERANCE)
        if (!recalculateBalanceTolerance) recalculateBalanceTolerance = FinancialTransaction.RECALCULATE_BALANCE_TOLERANCE

        BigDecimal recalculateBalanceDifference = BigDecimalUtils.abs(currentBalance - recalculatedBalance)
        if (recalculateBalanceDifference > recalculateBalanceTolerance) {
            RuntimeException balanceRuntimeException = new RuntimeException("O cliente não possui saldo suficiente para realizar esta transação.")
            AsaasLogger.error("Recalculate balance >> Customer [${customer.id}] balance [${currentBalance}] does not match with recalculated balance [${getTotalCreditValue(customer, [logged: true]).totalCreditValueWithoutPaymentCache - getTotalDebitValue(customer, [logged: true]).totalDebitValueWithoutPaymentCache}]", balanceRuntimeException)
            throw balanceRuntimeException
        } else if (recalculateBalanceDifference != 0) {
            AsaasLogger.warn("Recalculate balance >> O recálculo de saldo do cliente [${customer.id}] esta dentro da tolerância permitida.")
        }
    }

    public FinancialTransaction saveCredit(Credit credit, FinancialTransactionBatch financialTransactionBatch) {
        if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: credit.customer,
                                                 value: credit.value,
                                                 transactionType: FinancialTransactionType.CREDIT,
                                                 credit: credit,
                                                 description: credit.description,
                                                 financialTransactionBatch: financialTransactionBatch], true)

        return transaction
    }

    public FinancialTransaction saveCustomerCommission(Customer customer, CustomerCommissionType type, List<CustomerCommission> customerCommissionList) {
        FinancialTransaction transaction = save([
            provider: customer,
            transactionType: type.isDebitType() ? FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_DEBIT : FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            value: customerCommissionList.sum { it.value },
            financialTransactionBatch: new FinancialTransactionBatch().save(),
            description: Utils.getMessageProperty("customer.commission.type.transaction.${type.toString()}.description")
        ])

        financialTransactionCustomerCommissionService.save(customerCommissionList, transaction)

        return transaction
    }

	public Map getTotalCreditValue(Customer customer, Map options) {
        Map<String, BigDecimal> creditInfoMap = [:]
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "creditBureauValue", Payment.executeQuery("select coalesce(sum(pd.payment.netValue), 0) from PaymentDunning pd where pd.customer = :customer and pd.type = :dunningType and pd.deleted = false and pd.status = :dunningStatus and pd.payment.deleted = false and pd.payment.status = :paymentStatus", [customer: customer, dunningType: PaymentDunningType.CREDIT_BUREAU, dunningStatus: PaymentDunningStatus.PAID, paymentStatus: PaymentStatus.DUNNING_RECEIVED])[0])
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "internalTransferCredit", InternalTransfer.sumValue([destinationCustomer: customer, status: InternalTransferStatus.DONE]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "creditValue", Credit.sumNotDerivative([customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "partialPaymentValue", PartialPayment.sumValue([customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "anticipationValue", ReceivableAnticipation.executeQuery("select coalesce(sum(a.netValue), 0) from ReceivableAnticipation a join a.partnerAcquisition pa where a.customer = :provider and a.status in (:status) and a.deleted = false and pa.id is not null", [provider: customer, status: ReceivableAnticipationStatus.listAlreadyCredited()])[0])
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "customerCommissionValue", CustomerCommission.sumValue([customer: customer, "type[in]": CustomerCommissionType.listCreditTypes(), settled: true, "customerCommissionCheckout[isNull]": true, disableSort: true]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "customerCommissionCheckoutValue", CustomerCommissionCheckout.sumValue([customer: customer, status: CustomerCommissionCheckoutStatus.APPROVED]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "asaasCardTransactionCreditValue", AsaasCardTransaction.sumValueAbs([customer: customer, "type[in]": AsaasCardTransactionType.getCreditTypes()]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "pixTransactionCreditValue", PixTransaction.sumValueAbs([customer: customer, "payment[isNull]": true, "status[in]": PixTransactionStatus.getCompromisedList(), "type[in]": [PixTransactionType.DEBIT_REFUND, PixTransactionType.CREDIT]]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "asaasCardBalanceRefund", AsaasCardBalanceRefund.sumAmount([customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "bacenJudicialUnlock", JudicialLockItem.sumValueAbs([customer: customer, "type[in]": [JudicialLockItemType.UNLOCK, JudicialLockItemType.CANCEL_LOCK, JudicialLockItemType.UNLOCK_FROM_TRANSFER]]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "externalSettlementTotalValue", ExternalSettlement.sumTotalValue([asaasCustomerId: customer.id, "status[in]": [ExternalSettlementStatus.IN_PROGRESS, ExternalSettlementStatus.PRE_PROCESSED, ExternalSettlementStatus.PROCESSED]]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "asaasMoneyCashbackValue", AsaasMoneyCashback.sumValueAbs([payerCustomer: customer, status: AsaasMoneyCashbackStatus.CREDITED]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "asaasCardCashbackValue", AsaasCardCashback.sumValueAbs([customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "custodyReversalValue", PaymentCustodyItem.sumValueAbs([customer: customer, type: PaymentCustodyItemType.REVERSAL]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "anticipationPaymentFeeRefundValue", ReceivableAnticipationPartnerSettlement.sumPaymentFeeRefundValue([customer: customer, "status[in]": ReceivableAnticipationPartnerSettlementStatus.getEligibleStatusToSettlementProcess()]).get())
        addValueInRecalculateInfoMapWithRound(creditInfoMap, "asaasCardBillPaymentRefundValue", AsaasCardBillPaymentRefund.sumValueAbs([customer: customer]).get())

        BigDecimal totalCreditWithoutPaymentValues = creditInfoMap.values().sum()

        Map<String, BigDecimal> totalCreditValueMap = [:]
        BigDecimal paymentCreditValueWithoutCache = Payment.executeQuery("select coalesce(sum(netValue), 0) from Payment p where p.provider = :provider and p.status = :status and deleted = false", [provider: customer, status: PaymentStatus.RECEIVED])[0]
        totalCreditValueMap.totalCreditValueWithoutPaymentCache = totalCreditWithoutPaymentValues + BigDecimalUtils.roundHalfUp(paymentCreditValueWithoutCache).abs()

        BigDecimal paymentCreditValueWithCache = getPaymentCreditValue(customer)
        BigDecimal paymentRefundReversalFee = Payment.executeQuery("select coalesce(sum(abs(value - netValue)), 0) from Payment p where provider = :customer and deleted = false and status in (:statusList) and exists (select r.id from PaymentRefund r where r.payment = p and r.valueDebited = true and (r.shouldRefundFee = true or coalesce(p.refundFee, 0) = 0))",
            [customer: customer, statusList: [PaymentStatus.REFUNDED, PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUND_IN_PROGRESS]])[0]
        totalCreditValueMap.totalCreditValueWithPaymentCache = totalCreditWithoutPaymentValues + BigDecimalUtils.roundHalfUp(paymentCreditValueWithCache).abs() + BigDecimalUtils.roundHalfUp(paymentRefundReversalFee).abs()

        if (options.logged) {
            Map creditInfoMapToLog = creditInfoMap + ["paymentCreditValueWithoutCache": paymentCreditValueWithoutCache, "paymentCreditValueWithCache": paymentCreditValueWithCache, "paymentRefundReversalFee": paymentRefundReversalFee]
            AsaasLogger.info("FinancialTransactionService.getTotalCreditValue >> [Customer: ${customer.id}, Valor Total Recalculo Padrão: ${totalCreditValueMap.totalCreditValueWithoutPaymentCache}, Valor Total recálculo com cache da cobrança: ${totalCreditValueMap.totalCreditValueWithPaymentCache}]. Detalhes: \n${JsonOutput.prettyPrint(JsonOutput.toJson(creditInfoMapToLog))}")
        }

        return totalCreditValueMap
    }

    public Map getTotalDebitValue(Customer customer, Map options) {
        Map<String, BigDecimal> debitInfoMap = [:]
        BigDecimal transferValue = CreditTransferRequest.sumNetValueAndFeeAbs([provider: customer, "statusList": CreditTransferRequestStatus.getCompromisedList()]).get()
        transferValue += CreditTransferRequest.sumNetValueAndFeeAbs([provider: customer, "scheduledDate[isNull]": true, "statusList": CreditTransferRequestStatus.getCompromisedListForNotScheduledTransfer()]).get()
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "transferValue", transferValue)
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "debitValue", Debit.sumValue([customer: customer, done: true]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "billValue", Bill.executeQuery("select coalesce(sum(abs(b.value)) + coalesce(sum(abs(b.fee)), 0), 0) from Bill b where b.customer = :customer and b.status not in (:status) and b.deleted = false and b.valueDebited = true ", [customer: customer, status: [BillStatus.CANCELLED, BillStatus.FAILED, BillStatus.REFUNDED]])[0])
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "postalServiceFee", PaymentPostalServiceBatch.executeQuery("select coalesce(sum(abs(fee)), 0) from PaymentPostalServiceBatch where customer = :customer and status != :cancelled and deleted = false", [customer: customer, cancelled: PostalServiceBatchStatus.CANCELLED])[0])
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "debitedAnticipationInstallmentValue", ReceivableAnticipation.executeQuery("select coalesce(sum(abs(rai.value)), 0) from ReceivableAnticipationItem rai left join rai.anticipation.partnerAcquisition pa where rai.anticipation.customer = :customer and rai.anticipation.status = :anticipationStatus and rai.anticipation.installment is not null and rai.payment.status in (:paymentStatus) and rai.deleted = false and rai.anticipation.deleted = false and pa.id is null", [customer: customer, anticipationStatus: ReceivableAnticipationStatus.CREDITED, paymentStatus: [PaymentStatus.RECEIVED, PaymentStatus.REFUNDED]])[0])
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "internalTransferDebit",   InternalTransfer.sumValueAbs([customer: customer, "status[in]": [InternalTransferStatus.AWAITING_EXTERNAL_AUTHORIZATION, InternalTransferStatus.PENDING, InternalTransferStatus.DONE]]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "refundRequestFee",   RefundRequest.sumFeeAbs([customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "invoiceFee",   Invoice.sumFeeAbs([customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "asaasCardRecharge",   AsaasCardRecharge.sumValueAbs(['status[ne]': AsaasCardRechargeStatus.CANCELLED, customer: customer]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "anticipationChargebackRetained",   Payment.sumNetValue(['paymentDate[isNull]': true, customer: customer, anticipated: true, statusList: [PaymentStatus.CHARGEBACK_REQUESTED, PaymentStatus.CHARGEBACK_DISPUTE, PaymentStatus.AWAITING_CHARGEBACK_REVERSAL]]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "chargebackDebitedAnticipationInstallmentRetained",   ReceivableAnticipation.executeQuery("select coalesce(sum(abs(rai.value)), 0) from ReceivableAnticipationItem rai left join rai.anticipation.partnerAcquisition pa where rai.anticipation.customer = :customer and rai.anticipation.status = :anticipationStatus and rai.anticipation.installment is not null and rai.payment.status in (:paymentStatus) and rai.deleted = false and rai.anticipation.deleted = false and rai.payment.paymentDate is not null and pa.id is null", [customer: customer, anticipationStatus: ReceivableAnticipationStatus.CREDITED, paymentStatus: [PaymentStatus.CHARGEBACK_REQUESTED, PaymentStatus.CHARGEBACK_DISPUTE, PaymentStatus.AWAITING_CHARGEBACK_REVERSAL]])[0])
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "asaasMoneyChargebackRetained",   AsaasMoneyTransactionChargeback.sumValueAbs([payerCustomer: customer, status: AsaasMoneyTransactionChargebackStatus.DEBITED]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "chargedFee",   ChargedFee.sumValueAbs([customer: customer, status: ChargedFeeStatus.DEBITED]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "anticipationFeeWithoutPartnerValue",   ReceivableAnticipation.executeQuery("select coalesce(sum(a.fee), 0) from ReceivableAnticipation a left join a.partnerAcquisition pa where a.customer = :customer and a.status in (:status) and a.deleted = false and pa.id is null", [customer: customer, status: [ReceivableAnticipationStatus.DEBITED, ReceivableAnticipationStatus.OVERDUE]])[0])
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "anticipationPartnerSettlement",   ReceivableAnticipationPartnerSettlement.sumValueAbs([customer: customer, "status[in]": ReceivableAnticipationPartnerSettlementStatus.getEligibleStatusToSettlementProcess()]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "asaasCardTransactionDebitValue", AsaasCardTransaction.sumValueAbs([customer: customer, "type[in]": AsaasCardTransactionType.getDebitTypes()]).get())
        BigDecimal pixTransactionDebitValue = PixTransaction.sumValueAbs([customer: customer, "payment[isNull]": true, "status[in]": PixTransactionStatus.getCompromisedList(), "type[in]": [PixTransactionType.DEBIT, PixTransactionType.CREDIT_REFUND, PixTransactionType.DEBIT_REFUND_CANCELLATION]]).get()
        pixTransactionDebitValue += PixTransaction.sumValueAbs([customer: customer, "payment[isNull]": true, "status[in]": PixTransactionStatus.getCompromisedListForNotScheduledTransaction(), "scheduledDate[isNull]": true, "type[in]": [PixTransactionType.DEBIT, PixTransactionType.CREDIT_REFUND]]).get()
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "pixTransactionDebitValue", pixTransactionDebitValue)
        BigDecimal settledContractualEffectValue = CercContractualEffectSettlement.sumValue([customerId: customer.id, status: CercContractualEffectSettlementStatus.DEBITED]).get()
        settledContractualEffectValue += ContractualEffectSettlementItem.sumValue([customerId: customer.id, status: ContractualEffectSettlementItemStatus.DEBITED]).get()
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "settledContractualEffectValue", settledContractualEffectValue)
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "debtRecoveryNegotiationFinancialCharges",   customer.isAsaasDebtRecoveryProvider() ? DebtRecoveryNegotiationPayment.sumChargeValue([status: DebtRecoveryNegotiationPaymentStatus.USED_TO_SETTLE_DEBT]).get() : 0)
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "debitAsaasMoneyPayment",  AsaasMoneyTransactionInfo.sumValueAbs([payerCustomer: customer, type: AsaasMoneyTransactionType.PAYMENT, status: AsaasMoneyTransactionStatus.PAID]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "debitAsaasMoneyDonation",  AsaasMoneyTransactionInfo.sumDonationValueAbs([payerCustomer: customer, type: AsaasMoneyTransactionType.PAYMENT, status: AsaasMoneyTransactionStatus.PAID]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "debitMobilePhoneRecharge",  MobilePhoneRechargeRepository.query([customerId: customer.id, "status[in]": [MobilePhoneRechargeStatus.AWAITING_EXTERNAL_AUTHORIZATION, MobilePhoneRechargeStatus.WAITING_CRITICAL_ACTION, MobilePhoneRechargeStatus.PENDING, MobilePhoneRechargeStatus.CONFIRMED]]).sumAbsolute("value"))
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "asaasMoneyFee", AsaasMoneyChargedFee.sumValueAbs([payerCustomer: customer, status: AsaasMoneyChargedFeeStatus.DEBITED]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "asaasMoneyPaymentCompromisedBalance",  AsaasMoneyPaymentCompromisedBalance.sumValueAbs([payerCustomer: customer, status: AsaasMoneyPaymentCompromisedBalanceStatus.DEBITED]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "bacenJudicialLock",  JudicialLockItem.sumValueAbs([customer: customer, "type[in]": [JudicialLockItemType.LOCK, JudicialLockItemType.LOCK_FROM_TRANSFER]]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "asaasCardBillPaymentDebitValue",  AsaasCardBillPayment.sumValueAbs([customer: customer, "method[in]": AsaasCardBillPaymentMethod.listBalanceDebit()]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "custodyBlockValue",  PaymentCustodyItem.sumValueAbs([customer: customer, type: PaymentCustodyItemType.BLOCK]).get())
        addValueInRecalculateInfoMapWithRound(debitInfoMap, "customerCommissionValue",  CustomerCommission.sumValue([customer: customer, "type[in]": CustomerCommissionType.listDebitTypes(), settled: true, "customerCommissionCheckout[isNull]": true, disableSort: true]).get())

        BigDecimal totalDebitWithoutPaymentRefundValues = debitInfoMap.values().sum().abs()

        Map<String, BigDecimal> totalDebitValueMap = [:]
        BigDecimal refundFeeValue = Debit.executeQuery("select coalesce(sum(abs(refundFee)), 0) from Payment where provider = :customer and deleted = false and status in (:status)", [customer: customer, status: [PaymentStatus.REFUNDED, PaymentStatus.REFUND_REQUESTED]])[0]
        BigDecimal refundedPayment = PaymentRefund.sumValueAbs([customer: customer, valueDebited: true, "paymentStatus[in]": [PaymentStatus.RECEIVED, PaymentStatus.CHARGEBACK_DISPUTE, PaymentStatus.CHARGEBACK_REQUESTED]]).get()
        totalDebitValueMap.totalDebitValueWithoutPaymentCache = totalDebitWithoutPaymentRefundValues + BigDecimalUtils.roundHalfUp(refundFeeValue).abs() + BigDecimalUtils.roundHalfUp(refundedPayment).abs()

        BigDecimal allRefundedPaymentDebited = PaymentRefund.sumValueAbs([customer: customer, valueDebited: true]).get()
        BigDecimal chargebackRetainedValue = Payment.sumNetValue([customer: customer, withChargebackRetained: true, billingType: BillingType.MUNDIPAGG_CIELO, statusList: [PaymentStatus.CHARGEBACK_REQUESTED, PaymentStatus.CHARGEBACK_DISPUTE, PaymentStatus.AWAITING_CHARGEBACK_REVERSAL], "paymentDate[isNotNull]": true]).get()
        totalDebitValueMap.totalDebitValueWithPaymentCache = totalDebitWithoutPaymentRefundValues + BigDecimalUtils.roundHalfUp(allRefundedPaymentDebited).abs() + BigDecimalUtils.roundHalfUp(chargebackRetainedValue).abs()

        if (options.logged) {
            Map debitInfoMapToLog = debitInfoMap + ["refundFeeValue": refundFeeValue, "refundedPayment": refundedPayment, "allRefundedPaymentDebited": allRefundedPaymentDebited, "chargebackRetainedValue": chargebackRetainedValue]
            AsaasLogger.info("FinancialTransactionService.getTotalDebitValue >> [Customer: ${customer.id}, Valor Total Recalculo Padrão: ${totalDebitValueMap.totalDebitValueWithoutPaymentCache}, Valor Total recálculo com cache da cobrança: ${totalDebitValueMap.totalDebitValueWithPaymentCache}]. Detalhes: \n${JsonOutput.prettyPrint(JsonOutput.toJson(debitInfoMapToLog))}")
        }

        return totalDebitValueMap
    }

    private Map addValueInRecalculateInfoMapWithRound(Map recalculateInfoMap, String key, BigDecimal value) {
        if (recalculateInfoMap.containsKey(key)) {
            throw new RuntimeException("Chave já existente no Map de informações de recálculo.")
        }

        return recalculateInfoMap.put(key, BigDecimalUtils.roundHalfUp(value).abs())
    }

    public FinancialTransaction cancelPaymentRefundIfNecessary(PaymentRefund paymentRefund) {
        FinancialTransactionPaymentRefund reversedTransaction = FinancialTransactionPaymentRefund.query([paymentRefund: paymentRefund, readOnly: true]).get()
        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem. ${paymentRefund.id}")

        FinancialTransaction financialTransactionReversal = FinancialTransaction.query([payment: paymentRefund.payment, reversedTransactionId: reversedTransaction.financialTransaction.id, readOnly: true]).get()
        if (financialTransactionReversal) return financialTransactionReversal

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: reversedTransaction.financialTransaction.provider,
                                                 value: FinancialTransactionUtils.toCreditValue(reversedTransaction.financialTransaction.value),
                                                 payment: paymentRefund.payment,
                                                 transactionType: FinancialTransactionType.PAYMENT_REFUND_CANCELLED,
                                                 reversedTransaction: reversedTransaction.financialTransaction,
                                                 description: "Cancelamento do estorno - fatura nr. ${paymentRefund.payment.getInvoiceNumber()} ${paymentRefund.payment.customerAccount.name}",
                                                 financialTransactionBatch: financialTransactionBatch], true)

        FinancialTransactionPaymentRefund financialTransactionPaymentRefund = new FinancialTransactionPaymentRefund()
        financialTransactionPaymentRefund.paymentRefund = paymentRefund
        financialTransactionPaymentRefund.financialTransaction = transaction
        financialTransactionPaymentRefund.save(failOnError: true)

        if (CustomDateUtils.isSameDayOfYear(reversedTransaction.financialTransaction.transactionDate, new Date())) {
            customerCheckoutLimitService.refundDailyLimit(reversedTransaction.financialTransaction.provider, transaction.value)
        }

        asaasErpAccountingStatementService.onFinancialTransactionCreate(transaction)

        return transaction
    }

    public FinancialTransaction reversePayment(PaymentRefund paymentRefund, String financialDescription, RefundRequest refundRequest) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction newTransaction = save([provider: paymentRefund.customer,
                                                    value: new BigDecimal(paymentRefund.value * getDebitOrCreditInt(FinancialTransactionType.PAYMENT_REVERSAL)),
                                                    payment: paymentRefund.payment,
                                                    refundRequest: refundRequest,
                                                    transactionType: FinancialTransactionType.PAYMENT_REVERSAL,
                                                    description: financialDescription,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        FinancialTransactionPaymentRefund financialTransactionPaymentRefund = new FinancialTransactionPaymentRefund()
        financialTransactionPaymentRefund.paymentRefund = paymentRefund
        financialTransactionPaymentRefund.financialTransaction = newTransaction
        financialTransactionPaymentRefund.save(failOnError: true)

        asaasErpAccountingStatementService.onFinancialTransactionCreate(newTransaction)

        return newTransaction
    }

    public FinancialTransaction reverseRefundRequest(RefundRequest refundRequest, FinancialTransactionBatch financialTransactionBatch) {
        if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction originFinancialTransaction = FinancialTransaction.query([refundRequest: refundRequest, payment: refundRequest.payment, transactionType: FinancialTransactionType.PAYMENT_REVERSAL, readOnly: true]).get()
        if (!originFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        FinancialTransaction newTransaction = save([provider: refundRequest.payment.provider,
                                                    value: new BigDecimal(refundRequest.payment.value),
                                                    transactionType: FinancialTransactionType.REFUND_REQUEST_CANCELLED,
                                                    payment: refundRequest.payment,
                                                    refundRequest: refundRequest,
                                                    reversedTransaction: originFinancialTransaction,
                                                    description: "Cancelamento do estorno - fatura nr. ${refundRequest.payment.getInvoiceNumber()} ${refundRequest.payment.customerAccount.name}",
                                                    financialTransactionBatch: financialTransactionBatch])

        if (refundRequest.fee > 0) {
            save([provider: refundRequest.payment.provider,
                  value: new BigDecimal(refundRequest.fee),
                  transactionType: FinancialTransactionType.REFUND_REQUEST_FEE_REVERSAL,
                  refundRequest: refundRequest,
                  description: "Cancelamento da taxa de realização de estorno - fatura nr. ${refundRequest.payment.getInvoiceNumber()} ${refundRequest.payment.customerAccount.name}",
                  financialTransactionBatch: financialTransactionBatch])
        }

        return newTransaction
    }

	private int getDebitOrCreditInt(FinancialTransactionType type) {
		if (type in [FinancialTransactionType.PAYMENT_RECEIVED, FinancialTransactionType.REFUND_REQUEST_CANCELLED]) {
			return 1
		} else if (type in [FinancialTransactionType.TRANSFER, FinancialTransactionType.TRANSFER_FEE, FinancialTransactionType.DEBIT_REVERSAL, FinancialTransactionType.REVERSAL, FinancialTransactionType.TRANSFER_REVERSAL, FinancialTransactionType.PAYMENT_REVERSAL, FinancialTransactionType.PAYMENT_FEE, FinancialTransactionType.POSTAL_SERVICE_FEE, FinancialTransactionType.BILL_PAYMENT]) {
			return -1
		} else {
			throw new RuntimeException("Tipo de transação sem definição de débito ou crédito")
		}
	}

	private FinancialTransaction findTransactionToReverse(CreditTransferRequest creditTransferRequest) {
		FinancialTransaction reversedTransaction = FinancialTransaction.executeQuery(FinancialTransactionQueryBuilder.buildTransactionToReverseByCreditTransferRequest(), [transfer: creditTransferRequest, type: FinancialTransactionType.TRANSFER])[0]
		if (!reversedTransaction) {
            AsaasLogger.error("Unable to find FinancialTransaction to >> [creditTransferRequest: ${creditTransferRequest.id}, transactionType: ${FinancialTransactionType.TRANSFER}]")
			throw new RuntimeException("Não foi possível localizar a movimentação financeira da transferência.")
		}

		return reversedTransaction
	}


	private FinancialTransaction findTransferFeeTransaction(FinancialTransaction transferTransaction) {
		return FinancialTransaction.executeQuery(FinancialTransactionQueryBuilder.buildTransferFeeToReverse(), [transfer: transferTransaction.creditTransferRequest, type: FinancialTransactionType.TRANSFER_FEE, batch: transferTransaction.financialTransactionBatch])[0]
	}


	public FinancialTransaction saveTransfer(CreditTransferRequest creditTransferRequest, Boolean bypassCheckoutLimit) {
		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        String transferDescription = "Transferência para conta bancária"
        FinancialTransaction transaction = save([provider: creditTransferRequest.provider,
                                                    value: new BigDecimal(creditTransferRequest.netValue * getDebitOrCreditInt(FinancialTransactionType.TRANSFER)),
                                                    transactionType: FinancialTransactionType.TRANSFER,
                                                    description: transferDescription,
                                                    creditTransferRequest: creditTransferRequest,
                                                    financialTransactionBatch: financialTransactionBatch], true)

		BigDecimal promotionalCodeCreditValue = PromotionalCodeUse.sumValue([consumerObject: creditTransferRequest]).get()
		BigDecimal transferFee = creditTransferRequest.transferFee + promotionalCodeCreditValue
		if (transferFee > 0) saveTransferFee(creditTransferRequest, transferFee, transaction.financialTransactionBatch)

		if (promotionalCodeCreditValue > 0) {
            String promotionalTransferFeeDescription = "Desconto na taxa de transferência para conta bancária"
            save([provider: creditTransferRequest.provider,
                    value: promotionalCodeCreditValue,
                    transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                    description: promotionalTransferFeeDescription,
                    creditTransferRequest: creditTransferRequest,
                    financialTransactionBatch: financialTransactionBatch])
		}

        validateBalanceWithdrawedByCustomer(transaction, (!bypassCheckoutLimit))

		return transaction
	}


	public FinancialTransaction saveTransferFee(CreditTransferRequest creditTransferRequest, BigDecimal transferFee, FinancialTransactionBatch financialTransactionBatch) {
        String description = "Taxa de transferência para conta bancária"
        FinancialTransaction transaction = save([provider: creditTransferRequest.provider,
                                                    value: new BigDecimal(transferFee * getDebitOrCreditInt(FinancialTransactionType.TRANSFER_FEE)),
                                                    transactionType: FinancialTransactionType.TRANSFER_FEE,
                                                    description: description,
                                                    creditTransferRequest: creditTransferRequest,
                                                    financialTransactionBatch: financialTransactionBatch])
		return transaction
	}


	public FinancialTransaction reverseTransfer(CreditTransferRequest creditTransferRequest, Boolean reverseFee, Boolean bypassCheckoutLimit) {
		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

		FinancialTransaction reversedTransaction = findTransactionToReverse(creditTransferRequest)

        String reversalTransferDescription = "Estorno de transferência"
        FinancialTransaction transaction = save([provider: creditTransferRequest.provider,
                                                    value: new BigDecimal(reversedTransaction.value * getDebitOrCreditInt(FinancialTransactionType.TRANSFER_REVERSAL)),
                                                    transactionType: FinancialTransactionType.REVERSAL,
                                                    description: reversalTransferDescription,
                                                    creditTransferRequest: creditTransferRequest,
                                                    reversedTransaction: reversedTransaction,
                                                    financialTransactionBatch: financialTransactionBatch])

		if (!bypassCheckoutLimit && reversedTransaction.transactionDate == new Date().clearTime()) asyncActionService.saveRefundCustomerCheckoutDailyLimit(reversedTransaction.provider.id, reversedTransaction.value.abs(), reversedTransaction.transactionDate, reversedTransaction.id)

		if (reverseFee && creditTransferRequest.transferFee > 0) {
			FinancialTransaction reversedTransferFee = findTransferFeeTransaction(reversedTransaction)

			if (reversedTransferFee) {
                String reversalTransferFeeDescription = "Estorno de taxa de transferência"
                save([provider: creditTransferRequest.provider,
                        value: new BigDecimal(reversedTransferFee.value * getDebitOrCreditInt(FinancialTransactionType.TRANSFER_REVERSAL)),
                        transactionType: FinancialTransactionType.REVERSAL,
                        description: reversalTransferFeeDescription,
                        creditTransferRequest: creditTransferRequest,
                        reversedTransaction: reversedTransferFee,
                        financialTransactionBatch: financialTransactionBatch])
			}
		}

		return transaction
	}

	public FinancialTransaction savePaymentReceived(Payment payment, FinancialTransactionBatch financialTransactionBatch) {
		if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

        String description = "Cobrança recebida"
        if (payment.billingType.isTransfer()) {
            description = "TED recebida"
        }

        description += " - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"
        Invoice relatedInvoice = payment.getInvoice() ?: payment.installment?.getInvoice()
        if (relatedInvoice?.number) description += ". Nota fiscal nr. ${relatedInvoice.number}"

        FinancialTransaction transaction = save([provider: payment.provider,
                                                    value: new BigDecimal(payment.value * getDebitOrCreditInt(FinancialTransactionType.PAYMENT_RECEIVED)),
                                                    transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
                                                    payment: payment,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    description: description])

		savePaymentFee(payment, financialTransactionBatch)

		return transaction
	}


	private void savePaymentFee(Payment payment, FinancialTransactionBatch financialTransactionBatch) {
        BigDecimal freePaymentCreditValue = freePaymentUseService.calculateValueToCredit(payment)

		BigDecimal promotionalCodeCreditValue = payment.calculatePromotionalCodeValueToCredit()

		BigDecimal paymentFee = payment.getAsaasValue() + promotionalCodeCreditValue + freePaymentCreditValue

		if (paymentFee <= 0) return

        String description = "Taxa de boleto"
        if (payment.billingType.isCreditCardOrDebitCard()) description = "Taxa de cartão"
        if (payment.billingType.isPix()) description = "Taxa do Pix"

        description += " - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"

        save([provider: payment.provider,
                value: (paymentFee * getDebitOrCreditInt(FinancialTransactionType.PAYMENT_FEE)),
                description: description,
                transactionType: FinancialTransactionType.PAYMENT_FEE,
                payment: payment,
                financialTransactionBatch: financialTransactionBatch])

        if (promotionalCodeCreditValue) {
            save([provider: payment.provider,
                    value: promotionalCodeCreditValue,
                    transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                    payment: payment,
                    financialTransactionBatch: financialTransactionBatch,
                    description: "Desconto na tarifa - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"])
        }

        if (freePaymentCreditValue) {
            save([provider: payment.provider,
                    value: freePaymentCreditValue,
                    transactionType: FinancialTransactionType.FREE_PAYMENT_USE,
                    payment: payment,
                    description: "Estorno por campanha promocional na tarifa - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}",
                    financialTransactionBatch: financialTransactionBatch])
        }
	}

    public void reversePaymentFee(Payment payment, FinancialTransactionBatch financialTransactionBatch) {
        Boolean hasPaymentFeeBeenAlreadyReversed = FinancialTransaction.query([exists: true, payment: payment, transactionType: FinancialTransactionType.PAYMENT_FEE_REVERSAL]).get()
        if (hasPaymentFeeBeenAlreadyReversed) throw new RuntimeException("A taxa da cobrança ${payment.id} já foi estornada.")

        if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

        BigDecimal feeRefundValue = payment.getAsaasValue()

        List<PromotionalCodeUse> promotionalCodesUsedList = promotionalCodeService.reverseDiscountValueUsed(payment)
        BigDecimal feeRefundPromotionalCodeValue = promotionalCodesUsedList*.value?.sum() ?: 0
        if (feeRefundPromotionalCodeValue > 0) {
            save([provider: payment.provider,
                    value: feeRefundPromotionalCodeValue *-1,
                    transactionType: FinancialTransactionType.PROMOTIONAL_CODE_DEBIT,
                    payment: payment,
                    description: "Estorno do desconto na tarifa - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}",
                    financialTransactionBatch: financialTransactionBatch])

            feeRefundValue += feeRefundPromotionalCodeValue
        }

        if (feeRefundValue <= 0) return

        String description = "Estorno da taxa de boleto"
        if (payment.billingType.isCreditCardOrDebitCard()) description = "Estorno da taxa de cartão"
        description += " - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"

        save([provider: payment.provider,
                value: feeRefundValue,
                transactionType: FinancialTransactionType.PAYMENT_FEE_REVERSAL,
                payment: payment,
                description: description,
                financialTransactionBatch: financialTransactionBatch])
    }

    public FinancialTransaction cancelPaymentRefund(Payment payment) {
        FinancialTransaction reversedTransaction = FinancialTransaction.query([payment: payment, transactionType: FinancialTransactionType.PAYMENT_REVERSAL]).get()
        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: reversedTransaction.provider,
                                                    value: FinancialTransactionUtils.toCreditValue(reversedTransaction.value),
                                                    payment: reversedTransaction.payment,
                                                    transactionType: FinancialTransactionType.PAYMENT_REFUND_CANCELLED,
                                                    reversedTransaction: reversedTransaction,
                                                    description: "Cancelamento do estorno - fatura nr. ${reversedTransaction.payment.getInvoiceNumber()} ${reversedTransaction.payment.customerAccount.name}",
                                                    financialTransactionBatch: financialTransactionBatch], true)

        if (reversedTransaction.transactionDate == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(reversedTransaction.provider, transaction.value)

        return transaction
    }


	public FinancialTransaction deletePaymentFee(Payment payment) {
		FinancialTransaction financialTransaction = FinancialTransaction.findWhere(payment: payment, transactionType: FinancialTransactionType.PAYMENT_FEE, deleted: false)

		if (!financialTransaction) return

		invoiceItemService.delete(financialTransaction)

        delete([financialTransaction], true)

        List<PromotionalCodeUse> promotionalCodesUsedList = promotionalCodeService.reverseDiscountValueUsed(payment)

        for (PromotionalCodeUse promotionalCodeUse in promotionalCodesUsedList) {
            FinancialTransaction promotionalCodeFinancialTransaction = FinancialTransaction.query([payment: promotionalCodeUse.payment, transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT]).get()

            delete([promotionalCodeFinancialTransaction], true)
        }

		return financialTransaction
	}

    public FinancialTransaction saveCustody(PaymentCustodyItem paymentCustodyItem, description) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: paymentCustodyItem.customer,
                                                 value: paymentCustodyItem.value * -1,
                                                 transactionType: FinancialTransactionType.PAYMENT_CUSTODY_BLOCK,
                                                 description: description,
                                                 financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionPaymentCustodyItem(transaction, paymentCustodyItem)

        return transaction
    }

    public FinancialTransaction saveCustodyReversal(PaymentCustodyItem paymentCustodyItem, description) {
        FinancialTransaction originalFinancialTransaction = FinancialTransactionPaymentCustodyItem.query([column: "financialTransaction", paymentCustody: paymentCustodyItem.paymentCustody, financialTransactionType: FinancialTransactionType.PAYMENT_CUSTODY_BLOCK]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de bloqueio de saldo por custódia.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: paymentCustodyItem.customer,
                                                 value: paymentCustodyItem.value,
                                                 transactionType: FinancialTransactionType.PAYMENT_CUSTODY_BLOCK_REVERSAL,
                                                 description: description,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 reversedTransaction: originalFinancialTransaction], true)

        saveFinancialTransactionPaymentCustodyItem(transaction, paymentCustodyItem)

        return transaction
    }

    public FinancialTransaction saveJudicialLock(Long customerId, BigDecimal lockedValue, String description) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()
        Customer customer = Customer.get(customerId)
        FinancialTransaction transaction = save([provider: customer,
                                                 value: lockedValue * -1,
                                                 transactionType: FinancialTransactionType.BACEN_JUDICIAL_LOCK,
                                                 description: description,
                                                 financialTransactionBatch: financialTransactionBatch], true)

        return transaction
    }

    public FinancialTransaction saveJudicialUnlock(Long customerId, BigDecimal unlockValue, String description, Long reversedTransactionId) {
        FinancialTransaction reversedFinancialTransaction = FinancialTransaction.get(reversedTransactionId)
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()
        Customer customer = Customer.get(customerId)
        FinancialTransaction transaction = save([provider: customer,
                                                 value: unlockValue,
                                                 transactionType: FinancialTransactionType.BACEN_JUDICIAL_UNLOCK,
                                                 description: description,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 reversedTransaction: reversedFinancialTransaction], true)

        return transaction
    }

    public FinancialTransaction saveDebit(Debit debit, FinancialTransactionBatch financialTransactionBatch, FinancialTransactionType financialTransactionType) {
        if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

        String description = debit.type.isBalanceBlock() ? "Bloqueio de Saldo" : debit.description

        FinancialTransaction transaction = save([provider: debit.customer,
                                                 value: debit.value * -1,
                                                 transactionType: financialTransactionType,
                                                 debit: debit,
                                                 description: description,
                                                 financialTransactionBatch: financialTransactionBatch], true)

		return transaction
	}

	public FinancialTransaction reverseDebit(Debit debit) {
		FinancialTransaction debitFinancialTransaction = FinancialTransaction.findWhere(debit: debit, transactionType: FinancialTransactionType.DEBIT, deleted: false)

		if (!debitFinancialTransaction) {
            AsaasLogger.error("Unable to find FinancialTransaction to >> [debit: ${debit.id}, transactionType: ${FinancialTransactionType.DEBIT}, deleted: false]")
			throw new RuntimeException("Não foi possível localizar a movimentação financeira para reversão.")
		}

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        String description
        if (debit.type.isBalanceBlock()) {
            description = "Desbloqueio de saldo"
        } else {
            description = "Estorno da transação ${debitFinancialTransaction.id}"
        }

        FinancialTransaction creditFinancialTransaction = save([provider: debitFinancialTransaction.provider,
                                                                value: debitFinancialTransaction.value * -1,
                                                                transactionType: FinancialTransactionType.DEBIT_REVERSAL,
                                                                debit: debit,
                                                                reversedTransaction: debitFinancialTransaction,
                                                                description: description,
                                                                financialTransactionBatch: financialTransactionBatch])

		return creditFinancialTransaction
	}

    public FinancialTransaction savePartialPayment(PartialPayment partialPayment) {
		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: partialPayment.customer,
                                                    value: partialPayment.value,
                                                    transactionType: FinancialTransactionType.PARTIAL_PAYMENT,
                                                    partialPayment: partialPayment,
                                                    financialTransactionBatch: financialTransactionBatch])

		return transaction
	}

    public FinancialTransaction saveReceivableAnticipationCredit(ReceivableAnticipation anticipation) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        BigDecimal valueToBeCredited = ReceivableAnticipationCalculator.calculateFinancialTransactionCreditValue(anticipation)

        String description
        if (anticipation.installment) {
            description = "Antecipação do parcelamento - fatura nr. ${anticipation.installment.getInvoiceNumber()} ${anticipation.customerAccount.name}"
        } else {
            description = "Antecipação - fatura nr. ${anticipation.payment.getInvoiceNumber()} ${anticipation.payment.customerAccount.name}"
        }

        FinancialTransaction transaction = save([provider: anticipation.customer,
                                                 value: valueToBeCredited,
                                                 transactionType: FinancialTransactionType.RECEIVABLE_ANTICIPATION_GROSS_CREDIT,
                                                 receivableAnticipation: anticipation,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 description: description], true)

        List<ReceivableAnticipationPartnerAcquisitionItem> partnerAcquisitionItemList = anticipation.partnerAcquisition.getPartnerAcquisitionItemList()
        for (ReceivableAnticipationPartnerAcquisitionItem item : partnerAcquisitionItemList) {
            applyPaymentFeeToReceivableAnticipation(financialTransactionBatch, anticipation.customer, item.payment, item.paymentFee)
        }

        applyReceivableAnticipationFee(anticipation, financialTransactionBatch)

        return transaction
    }

    private void applyPaymentFeeToReceivableAnticipation(FinancialTransactionBatch financialTransactionBatch, Customer customer, Payment payment, BigDecimal paymentFee) {
        BigDecimal freePaymentCreditValue = freePaymentUseService.calculateValueToCredit(payment)
        BigDecimal promotionalCodeCreditValue = payment.calculatePromotionalCodeValueToCredit()
        BigDecimal totalPaymentFee = paymentFee + promotionalCodeCreditValue + freePaymentCreditValue

        if (totalPaymentFee <= 0) return

        String paymentFeeDescription = "Taxa de boleto"
        if (payment.billingType.isCreditCardOrDebitCard()) paymentFeeDescription = "Taxa de cartão"
        paymentFeeDescription += " - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"

        save([provider: customer,
              value: FinancialTransactionUtils.toDebitValue(totalPaymentFee),
              transactionType: FinancialTransactionType.PAYMENT_FEE,
              payment: payment,
              description: paymentFeeDescription,
              financialTransactionBatch: financialTransactionBatch])

        if (promotionalCodeCreditValue) {
            save([provider: customer,
                  value: promotionalCodeCreditValue,
                  transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                  payment: payment,
                  financialTransactionBatch: financialTransactionBatch,
                  description: "Desconto na tarifa - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}"])
        }

        if (freePaymentCreditValue) {
            save([provider: customer,
                  value: freePaymentCreditValue,
                  transactionType: FinancialTransactionType.FREE_PAYMENT_USE,
                  payment: payment,
                  description: "Estorno por campanha promocional na tarifa - fatura nr. ${payment.getInvoiceNumber()} ${payment.customerAccount.name}",
                  financialTransactionBatch: financialTransactionBatch])
        }
    }

    private void applyReceivableAnticipationFee(ReceivableAnticipation anticipation, FinancialTransactionBatch financialTransactionBatch) {
        BigDecimal anticipationFee = anticipation.fee

        BigDecimal promotionalCodeCreditValue = anticipation.calculatePromotionalCodeCreditValue()
        anticipationFee += promotionalCodeCreditValue

        if (!anticipationFee) return

        String description
        if (anticipation.installment) {
            description = "Taxa de antecipação do parcelamento - fatura nr. ${anticipation.installment.getInvoiceNumber()} ${anticipation.customerAccount.name}"
        } else {
            description = "Taxa de antecipação - fatura nr. ${anticipation.payment.getInvoiceNumber()} ${anticipation.payment.customerAccount.name}"
        }

        save([provider: anticipation.customer,
              value: FinancialTransactionUtils.toDebitValue(anticipationFee),
              transactionType: FinancialTransactionType.RECEIVABLE_ANTICIPATION_FEE,
              receivableAnticipation: anticipation,
              financialTransactionBatch: financialTransactionBatch,
              description: description], true)

        if (promotionalCodeCreditValue > 0) {
            String invoiceNumberPromotionalCodeCredit = anticipation.installment ? anticipation.installment.getInvoiceNumber() : anticipation.payment.getInvoiceNumber()
            String descriptionPromotionalCodeCredit = "Desconto por campanha promocional na tarifa - fatura nr. ${invoiceNumberPromotionalCodeCredit} ${anticipation.customerAccount.name}"
            save([provider: anticipation.customer,
                  value: promotionalCodeCreditValue,
                  transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                  receivableAnticipation: anticipation,
                  financialTransactionBatch: financialTransactionBatch,
                  description: descriptionPromotionalCodeCredit])
        }
    }

    public FinancialTransaction saveReceivableAnticipationPartnerSettlement(ReceivableAnticipationPartnerSettlement partnerSettlement, Payment payment, String description, Boolean wasAwaitingReceivableAnticipationDebit) {
        ReceivableAnticipation receivableAnticipation = partnerSettlement.partnerAcquisition.receivableAnticipation

        FinancialTransactionType transactionType = FinancialTransactionType.RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT
        if (wasAwaitingReceivableAnticipationDebit) transactionType = FinancialTransactionType.RECEIVABLE_ANTICIPATION_DEBIT


        FinancialTransaction transaction = save([provider: receivableAnticipation.customer,
                                                    value: partnerSettlement.value * -1,
                                                    transactionType: transactionType,
                                                    receivableAnticipation: receivableAnticipation,
                                                    payment: payment,
                                                    description: description,
                                                    financialTransactionBatch: new FinancialTransactionBatch().save()])

		return transaction
	}

    public FinancialTransaction saveReceivableAnticipationDebit(ReceivableAnticipation anticipation, ReceivableAnticipationItem anticipationItem, String description) {
        Payment payment
        BigDecimal valueToDebit = 0

        if (anticipation.installment && anticipationItem) {
            payment = anticipationItem.payment
            valueToDebit = anticipationItem.value
        } else {
            payment = anticipation.payment
            valueToDebit = anticipation.value
        }

        FinancialTransaction transaction = save([provider: anticipation.customer,
                                                    value: valueToDebit * -1,
                                                    transactionType: FinancialTransactionType.RECEIVABLE_ANTICIPATION_DEBIT,
                                                    receivableAnticipation: anticipation,
                                                    payment: payment,
                                                    description: description,
                                                    financialTransactionBatch: new FinancialTransactionBatch().save()], true)

        return transaction
    }

	public FinancialTransaction saveBillPayment(Bill bill, FinancialTransactionBatch financialTransactionBatch) {
		if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

		FinancialTransaction transaction = save([provider: bill.customer,
                                                    value: bill.value * -1,
                                                    transactionType: FinancialTransactionType.BILL_PAYMENT,
                                                    bill: bill,
                                                    description: "Pagamento de conta ${bill.description ? '- ' + bill.description : ''}",
                                                    financialTransactionBatch: financialTransactionBatch], true)

		BigDecimal promotionalCodeCreditValue = PromotionalCodeUse.sumValue([consumerObject: bill]).get()
		BigDecimal billFee = bill.fee + promotionalCodeCreditValue

		if (billFee > 0) saveBillPaymentFee(bill, billFee, transaction.financialTransactionBatch)

		if (promotionalCodeCreditValue > 0) {
            save([provider: bill.customer,
                    value: promotionalCodeCreditValue,
                    transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                    bill: bill,
                    financialTransactionBatch: financialTransactionBatch,
                    description: "Desconto na taxa de pagamento de conta"])
		}

        validateBalanceWithdrawedByCustomer(transaction)

		return transaction
	}

	public FinancialTransaction saveBillPaymentFee(Bill bill, BigDecimal billFee, FinancialTransactionBatch batch) {
        FinancialTransaction transaction = save([provider: bill.customer,
                                                    value: billFee * -1,
                                                    transactionType: FinancialTransactionType.BILL_PAYMENT_FEE,
                                                    bill: bill,
                                                    description: "Taxa de pagamento de conta",
                                                    financialTransactionBatch: batch], true)
		return transaction
	}

	public FinancialTransaction cancelBillPayment(Bill bill, FinancialTransactionBatch financialTransactionBatch) {
		if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: bill.customer,
                                                                    value: bill.value * 1,
                                                                    transactionType: FinancialTransactionType.BILL_PAYMENT_CANCELLED,
                                                                    bill: bill,
                                                                    description: "Cancelamento do pagamento de conta ${bill.description ? '- ' + bill.description : ''}",
                                                                    financialTransactionBatch: financialTransactionBatch])

		if (bill.dateCreated.clearTime() == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(transaction.provider, transaction.value.abs())

		if (bill.fee) cancelBillFeePayment(bill, financialTransactionBatch)

		return transaction
	}

    public FinancialTransaction refundBillPayment(Bill bill) {
        FinancialTransaction financialTransaction = FinancialTransaction.query([bill: bill, transactionType: FinancialTransactionType.BILL_PAYMENT]).get()
        if (!financialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: bill.customer,
                                                 value: FinancialTransactionUtils.toCreditValue(financialTransaction.value),
                                                 transactionType: FinancialTransactionType.BILL_PAYMENT_REFUNDED,
                                                 bill: bill,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 description: "Estorno do pagamento de conta",
                                                 reversedTransaction: financialTransaction])

        if (bill.fee) cancelBillFeePayment(bill, financialTransactionBatch)

        return transaction
    }

	private FinancialTransaction cancelBillFeePayment(Bill bill, FinancialTransactionBatch financialTransactionBatch) {
        FinancialTransaction transaction = save([provider: bill.customer,
                                                    value: bill.fee * 1,
                                                    transactionType: FinancialTransactionType.BILL_PAYMENT_FEE_CANCELLED,
                                                    bill: bill,
                                                    description: "Cancelamento da taxa de pagamento de conta",
                                                    financialTransactionBatch: financialTransactionBatch])

		return transaction
	}

	public FinancialTransaction savePaymentPostalServiceBatchCreated(PaymentPostalServiceBatch paymentPostalServiceBatch) {
        BigDecimal promotionalCodeCreditValue = PromotionalCodeUse.sumValue([consumerObject: paymentPostalServiceBatch]).get()
        BigDecimal paymentPostalServiceBatchOriginalValue = paymentPostalServiceBatch.fee + promotionalCodeCreditValue

        if (!paymentPostalServiceBatchOriginalValue) return

		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction newTransaction = save([provider: paymentPostalServiceBatch.customer,
                                                    value: FinancialTransactionUtils.toDebitValue(paymentPostalServiceBatchOriginalValue),
                                                    transactionType: FinancialTransactionType.POSTAL_SERVICE_FEE,
                                                    paymentPostalServiceBatch: paymentPostalServiceBatch,
                                                    description: "Taxa de envio de ${paymentPostalServiceBatch.items.size().toString()} boletos via Correios",
                                                    financialTransactionBatch: financialTransactionBatch], true)

        if (promotionalCodeCreditValue) savePaymentPostalServiceBatchPromotionalCodeCredit(paymentPostalServiceBatch, promotionalCodeCreditValue, financialTransactionBatch)

		return newTransaction
	}

    public FinancialTransaction saveInternalTransferDebit(InternalTransfer internalTransfer, String description = null) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: internalTransfer.customer,
                                                    value: internalTransfer.value * -1,
                                                    transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT,
                                                    internalTransfer: internalTransfer,
                                                    description: description,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        if (internalTransfer.type.isAsaasAccount()) validateBalanceWithdrawedByCustomer(transaction)

        return transaction
    }

    public FinancialTransaction saveDebtRecoveryInternalTransferDebit(InternalTransfer internalTransfer) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: internalTransfer.customer,
                                                 value: internalTransfer.value * -1,
                                                 transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT,
                                                 internalTransfer: internalTransfer,
                                                 description: "Transferência para a conta Asaas ${internalTransfer.destinationCustomer.getProviderName()}",
                                                 financialTransactionBatch: financialTransactionBatch], true)

        customerCheckoutLimitService.consumeDailyLimit(transaction.provider, BigDecimalUtils.abs(transaction.value), false)
        recalculateBalance(transaction.provider)

        return transaction
    }

    public FinancialTransaction saveInternalTransferCredit(InternalTransfer internalTransfer, FinancialTransactionBatch financialTransactionBatch, String description = null) {
        if (!financialTransactionBatch) financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: internalTransfer.destinationCustomer,
                                                    value: internalTransfer.value,
                                                    transactionType: FinancialTransactionType.INTERNAL_TRANSFER_CREDIT,
                                                    internalTransfer: internalTransfer,
                                                    description: description,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        return transaction
    }

    public FinancialTransaction reverseInternalTransferDebit(InternalTransfer internalTransfer, FinancialTransactionBatch financialTransactionBatch) {
        FinancialTransaction reversedTransaction = FinancialTransaction.query([internalTransfer: internalTransfer, transactionType: FinancialTransactionType.INTERNAL_TRANSFER_DEBIT]).get()

        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira da transferência.")

        if (!financialTransactionBatch) financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: reversedTransaction.provider,
                                                    value: reversedTransaction.value * -1,
                                                    transactionType: FinancialTransactionType.INTERNAL_TRANSFER_REVERSAL,
                                                    internalTransfer: internalTransfer,
                                                    reversedTransaction: reversedTransaction,
                                                    description: "Cancelamento da transferência para a conta Asaas ${internalTransfer.destinationCustomer.getProviderName()}",
                                                    financialTransactionBatch: financialTransactionBatch], true)

        if (reversedTransaction.transactionDate == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(reversedTransaction.provider, reversedTransaction.value.abs())

        return transaction
    }

	public FinancialTransaction saveChargeback(Chargeback chargeback, Payment payment) {
		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: chargeback.customer,
                                                    value: payment.netValue * -1,
                                                    transactionType: FinancialTransactionType.CHARGEBACK,
                                                    description: "Bloqueio de saldo devido ao chargeback - fatura nr. ${payment.getInvoiceNumber()}",
                                                    chargeback: chargeback,
                                                    payment: payment,
                                                    financialTransactionBatch: financialTransactionBatch], true)

		return transaction
	}

	public FinancialTransaction reverseChargebackIfNecessary(Chargeback chargeback, Payment payment) {
		FinancialTransaction reversedTransaction = FinancialTransaction.query([chargeback: chargeback, payment: payment, transactionType: FinancialTransactionType.CHARGEBACK]).get()
		if (!reversedTransaction) return null

		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

		FinancialTransaction transaction = save([provider: chargeback.customer,
                                                    value: reversedTransaction.value * -1,
                                                    transactionType: FinancialTransactionType.CHARGEBACK_REVERSAL,
                                                    description: "Cancelamento do bloqueio de saldo devido ao chargeback - fatura nr. ${payment.getInvoiceNumber()}",
                                                    chargeback: chargeback,
                                                    payment: payment,
                                                    reversedTransaction: reversedTransaction,
                                                    financialTransactionBatch: financialTransactionBatch], true)

		return transaction
	}

    public FinancialTransaction saveAsaasMoneyTransactionChargeback(AsaasMoneyTransactionChargeback asaasMoneyTransactionChargeback) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasMoneyTransactionChargeback.payerCustomer,
                                                    description: "Bloqueio de saldo devido ao chargeback - fatura nr. ${asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo.getBackingInvoiceNumber()}",
                                                    value:  FinancialTransactionUtils.toDebitValue(asaasMoneyTransactionChargeback.value),
                                                    transactionType: FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CHARGEBACK,
                                                    chargeback: asaasMoneyTransactionChargeback.chargeback,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionAsaasMoneyTransaction(transaction, asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo)

        return transaction
    }

    public FinancialTransaction reverseAsaasMoneyTransactionChargeback(AsaasMoneyTransactionChargeback asaasMoneyTransactionChargeback) {
        FinancialTransaction originalFinancialTransaction = FinancialTransaction.query([asaasMoneyTransactionInfo: asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo, transactionType: FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CHARGEBACK]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de bloqueio de saldo de chargeback da transação Asaas Money [${asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo.id}].")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasMoneyTransactionChargeback.payerCustomer,
                                                    description: "Cancelamento do bloqueio de saldo devido ao chargeback - fatura nr. ${asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo.getBackingInvoiceNumber()}",
                                                    value: FinancialTransactionUtils.toCreditValue(originalFinancialTransaction.value),
                                                    transactionType: FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CHARGEBACK_REVERSAL,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction], true)

        saveFinancialTransactionAsaasMoneyTransaction(transaction, asaasMoneyTransactionChargeback.asaasMoneyTransactionInfo)

        return transaction
    }

    public FinancialTransaction saveAsaasMoneyPaymentCompromisedBalance(AsaasMoneyPaymentCompromisedBalance asaasMoneyPaymentCompromisedBalance) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasMoneyPaymentCompromisedBalance.payerCustomer,
                                                    description: "Reserva de saldo comprometido com pagamento Asaas Money",
                                                    value: FinancialTransactionUtils.toDebitValue(asaasMoneyPaymentCompromisedBalance.value),
                                                    transactionType: FinancialTransactionType.ASAAS_MONEY_PAYMENT_COMPROMISED_BALANCE,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionAsaasMoneyPaymentCompromisedBalance(transaction, asaasMoneyPaymentCompromisedBalance)

        return transaction
    }

    public FinancialTransaction reverseAsaasMoneyPaymentCompromisedBalance(AsaasMoneyPaymentCompromisedBalance asaasMoneyPaymentCompromisedBalance) {
        FinancialTransaction originalFinancialTransaction = FinancialTransactionAsaasMoneyPaymentCompromisedBalance.query([column: "financialTransaction", asaasMoneyPaymentCompromisedBalance: asaasMoneyPaymentCompromisedBalance, financialTransactionType: FinancialTransactionType.ASAAS_MONEY_PAYMENT_COMPROMISED_BALANCE]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de reserva de saldo comprometido com pagamento Asaas Money.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: originalFinancialTransaction.provider,
                                                    description: "Cancelamento de reserva de saldo comprometido com pagamento Asaas Money",
                                                    value: FinancialTransactionUtils.toCreditValue(originalFinancialTransaction.value),
                                                    transactionType: FinancialTransactionType.ASAAS_MONEY_PAYMENT_COMPROMISED_BALANCE_REFUND,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction], true)

        saveFinancialTransactionAsaasMoneyPaymentCompromisedBalance(transaction, asaasMoneyPaymentCompromisedBalance)

        return transaction
    }

    private FinancialTransactionAsaasMoneyPaymentCompromisedBalance saveFinancialTransactionAsaasMoneyPaymentCompromisedBalance(FinancialTransaction financialTransaction, AsaasMoneyPaymentCompromisedBalance asaasMoneyPaymentCompromisedBalance) {
        FinancialTransactionAsaasMoneyPaymentCompromisedBalance financialTransactionAsaasMoneyPaymentCompromisedBalance = new FinancialTransactionAsaasMoneyPaymentCompromisedBalance()
        financialTransactionAsaasMoneyPaymentCompromisedBalance.asaasMoneyPaymentCompromisedBalance = asaasMoneyPaymentCompromisedBalance
        financialTransactionAsaasMoneyPaymentCompromisedBalance.financialTransaction = financialTransaction
        financialTransactionAsaasMoneyPaymentCompromisedBalance.save(failOnError: true)

        return financialTransactionAsaasMoneyPaymentCompromisedBalance
    }

	public FinancialTransaction saveInvoiceFee(Invoice invoice, FinancialTransactionBatch financialTransactionBatch) {
		if (!financialTransactionBatch)	financialTransactionBatch = new FinancialTransactionBatch().save()

		BigDecimal promotionalCodeCreditValue = PromotionalCodeUse.sumValue([consumerObject: invoice]).get()
		BigDecimal invoiceOriginalFee = invoice.fee + promotionalCodeCreditValue

        String description = "Taxa de emissão da nota fiscal de serviço nr. ${invoice.number}"

        if (invoice.originType.isPayment()) {
            description += " - fatura nr. ${invoice.getPayment().getInvoiceNumber()}"
        } else if (invoice.originType.isInstallment()) {
            description += " - fatura do parcelamento nr. ${invoice.getInstallment().getInvoiceNumber()}"
        }
        description += " - ${invoice.customerAccount.name}"

        FinancialTransaction transaction = save([provider: invoice.customer,
                                                    value: invoiceOriginalFee * -1,
                                                    transactionType: FinancialTransactionType.INVOICE_FEE,
                                                    invoice: invoice,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    description: description])

		if (promotionalCodeCreditValue > 0) {
            save([provider: invoice.customer,
                    value: promotionalCodeCreditValue,
                    transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                    invoice: invoice,
                    financialTransactionBatch: financialTransactionBatch,
                    description: "Desconto na taxa de emissão da nota fiscal de serviço nr. ${invoice.number}"])
		}

		return transaction
	}

	public FinancialTransaction saveAsaasCardRecharge(AsaasCardRecharge asaasCardRecharge) {
		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasCardRecharge.getCustomer(),
                                                    value: asaasCardRecharge.value * -1,
                                                    transactionType: FinancialTransactionType.ASAAS_CARD_RECHARGE,
                                                    asaasCardRecharge: asaasCardRecharge,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    description: "Recarga do cartão ${asaasCardRecharge.asaasCard.getFormattedName()}"], true)

        validateBalanceWithdrawedByCustomer(transaction)

		return transaction
	}

	public FinancialTransaction cancelAsaasCardRecharge(AsaasCardRecharge asaasCardRecharge) {
        FinancialTransaction reversedTransaction = FinancialTransaction.query([asaasCardRecharge: asaasCardRecharge, transactionType: FinancialTransactionType.ASAAS_CARD_RECHARGE]).get()

        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira da recarga de cartão.")

		FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasCardRecharge.getCustomer(),
                                                    value: asaasCardRecharge.value,
                                                    transactionType: FinancialTransactionType.ASAAS_CARD_RECHARGE_REVERSAL,
                                                    asaasCardRecharge: asaasCardRecharge,
                                                    reversedTransaction: reversedTransaction,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    description: "Estorno da recarga do cartão ${asaasCardRecharge.asaasCard.getFormattedName()}"], true)

        if (reversedTransaction.transactionDate == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(reversedTransaction.provider, reversedTransaction.value.abs())

		return transaction
	}

	public Date calculateStartDateByPeriod(String period) {
		Calendar startDate = CustomDateUtils.getInstanceOfCalendar(DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH))

		if ("last7Days".equals(period)) {
			startDate.set(Calendar.DAY_OF_MONTH, startDate.get(Calendar.DAY_OF_MONTH) - 7)
		} else if ("last15Days".equals(period)) {
			startDate.set(Calendar.DAY_OF_MONTH, startDate.get(Calendar.DAY_OF_MONTH) - 15)
		}

		return startDate.getTime()
	}


	public Date calculateFinishDateByPeriod(String period) {
		Calendar finishDate = CustomDateUtils.getInstanceOfCalendar(DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH))

		return finishDate.getTime()
	}

    public FinancialTransaction saveRefundRequestFee(RefundRequest refundRequest) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        BigDecimal promotionalCodeCreditValue = PromotionalCodeUse.sumValue([consumerObject: refundRequest]).get()
        BigDecimal refundRequestOriginalFee = refundRequest.fee + promotionalCodeCreditValue

        FinancialTransaction transaction = save([provider: refundRequest.payment.provider,
                                                    value: refundRequestOriginalFee * -1,
                                                    transactionType: FinancialTransactionType.REFUND_REQUEST_FEE,
                                                    refundRequest: refundRequest,
                                                    description: "Taxa de realização de estorno - fatura nr. ${refundRequest.payment.getInvoiceNumber()} ${refundRequest.payment.customerAccount.name}",
                                                    financialTransactionBatch: financialTransactionBatch])

        if (promotionalCodeCreditValue > 0) {
            save([provider: refundRequest.payment.provider,
                    value: promotionalCodeCreditValue,
                    transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                    refundRequest: refundRequest,
                    financialTransactionBatch: financialTransactionBatch,
                    description: "Desconto na taxa de estorno - fatura nr. ${refundRequest.payment.getInvoiceNumber()} ${refundRequest.payment.customerAccount.name}"])
        }

        return transaction
    }

	public BigDecimal calculateMonthlyAsaasIncome(Customer customer) {
		try {
			ArrayList customerFinancialTransactionInfo = FinancialTransaction.sumValueAndMinTransactionDate([customer: customer, transactionTypeList: FinancialTransactionType.getAsaasIncomeTypes()]).get()
			ArrayList customerDebitInfo = Debit.sumValueAndMinDateCreated([customer: customer, typeList: DebitType.getAsaasIncomeTypes()]).get()
			BigDecimal totalAsaasIncome = customerFinancialTransactionInfo[0] + customerDebitInfo[0]
			Date firstTransactionDate = [customerFinancialTransactionInfo[1], customerDebitInfo[1]].min()

			Double numberOfMonthsAfterFirstIncomeReceived = 1.0
			Date currentDate = new Date().clearTime()

			if (firstTransactionDate && firstTransactionDate != currentDate) {
				numberOfMonthsAfterFirstIncomeReceived = CustomDateUtils.calculateDifferenceInDays(firstTransactionDate, currentDate) / 30
			}

			return totalAsaasIncome / numberOfMonthsAfterFirstIncomeReceived
		} catch (Exception e) {
			AsaasLogger.error("FinancialTransactionService.calculateMonthlyAsaasIncome >> Erro ao calcular renda mensal no Asaas", e)
			return null
		}
	}

    public FinancialTransaction saveChargedFee(ChargedFee chargedFee, String financialDescription, String promotionalCodeDescription, BigDecimal promotionalCodeCreditValue) {
        BigDecimal chargedFeeOriginalValue = chargedFee.value + promotionalCodeCreditValue

        if (!chargedFeeOriginalValue) return

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction financialTransaction = save([provider: chargedFee.customer,
                                                            value: FinancialTransactionUtils.toDebitValue(chargedFeeOriginalValue),
                                                            transactionType: chargedFee.type.toFinancialTransactionType(),
                                                            description: financialDescription,
                                                            chargedFee: chargedFee,
                                                            financialTransactionBatch: financialTransactionBatch], true)

        if (promotionalCodeCreditValue) saveChargedFeePromotionalCodeCredit(chargedFee, promotionalCodeCreditValue, financialTransactionBatch, promotionalCodeDescription)

		return financialTransaction
	}

    public FinancialTransaction refundChargedFee(ChargedFee chargedFee, String refundTransactionDescription, String refundPromotionalCodeDescription, BigDecimal promotionalCodeUsedValue) {
        FinancialTransaction reversedTransaction = FinancialTransaction.query([provider: chargedFee.customer, chargedFee: chargedFee, transactionType: chargedFee.type.toFinancialTransactionType()]).get()

        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch()
        financialTransactionBatch.save()

        FinancialTransaction financialTransaction = save([provider: reversedTransaction.provider,
                                                          description: refundTransactionDescription,
                                                          value: FinancialTransactionUtils.toCreditValue(reversedTransaction.value),
                                                          transactionType: FinancialTransactionType.CHARGED_FEE_REFUND,
                                                          chargedFee: chargedFee,
                                                          reversedTransaction: reversedTransaction,
                                                          financialTransactionBatch: financialTransactionBatch], true)

        reverseChargedFeePromotionalCodeIfNecessary(reversedTransaction, financialTransactionBatch, refundPromotionalCodeDescription, promotionalCodeUsedValue)

        return financialTransaction
    }

    public FinancialTransaction saveBalanceRefund(AsaasCardBalanceRefund asaasCardBalanceRefund) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasCardBalanceRefund.asaasCard.customer,
                                                 value: asaasCardBalanceRefund.amount,
                                                 transactionType: FinancialTransactionType.ASAAS_CARD_BALANCE_REFUND,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 description: "Estorno de saldo do cartão pré-pago ${asaasCardBalanceRefund.asaasCard.getFormattedName()}"], true)

        saveFinancialTransactionAsaasCardBalanceRefund(transaction, asaasCardBalanceRefund)

        return transaction
    }

    public FinancialTransaction saveAsaasCardBillPayment(AsaasCardBillPayment asaasCardBillPayment) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasCardBillPayment.customer,
                                                 value: FinancialTransactionUtils.toDebitValue(asaasCardBillPayment.value),
                                                 transactionType: FinancialTransactionType.ASAAS_CARD_BILL_PAYMENT,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 description: "Pagamento de fatura do cartão ${asaasCardBillPayment.asaasCard.getFormattedName()}"], true)

        saveFinancialTransactionAsaasCardBillPayment(transaction, asaasCardBillPayment)

        return transaction
    }

    public FinancialTransaction saveAsaasCardBillPaymentRefund(AsaasCardBillPaymentRefund asaasCardBillPaymentRefund) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransactionAsaasCardBillPayment financialTransactionAsaasCardBillPayment = FinancialTransactionAsaasCardBillPayment.query([asaasCardBillPayment: asaasCardBillPaymentRefund.asaasCardBillPayment]).get()
        if (!financialTransactionAsaasCardBillPayment) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        FinancialTransaction transaction = save([provider: asaasCardBillPaymentRefund.customer,
                                                 value: asaasCardBillPaymentRefund.value,
                                                 transactionType: FinancialTransactionType.ASAAS_CARD_BILL_PAYMENT_REFUND,
                                                 financialTransactionBatch: financialTransactionBatch,
                                                 reversedTransaction: financialTransactionAsaasCardBillPayment.financialTransaction,
                                                 description: "Estorno do pagamento de fatura do cartão ${asaasCardBillPaymentRefund.asaasCardBillPayment.asaasCard.getFormattedName()}"], true)

        saveFinancialTransactionAsaasCardBillPaymentRefund(transaction, asaasCardBillPaymentRefund)

        return transaction
    }

    public FinancialTransaction saveAsaasCardTransaction(AsaasCardTransaction asaasCardTransaction, Boolean isForcedAuthorization) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasCardTransaction.customer,
                                                    value: FinancialTransactionUtils.toDebitValue(asaasCardTransaction.netValue),
                                                    transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    description: "Transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"], true)

        saveFinancialTransactionAsaasCardTransaction(transaction, asaasCardTransaction)
        if (asaasCardTransaction.fee > 0) saveAsaasCardTransactionFee(asaasCardTransaction)

        if (!isForcedAuthorization) validateBalanceWithdrawedByCustomer(transaction.provider, transaction.value, transaction.transactionType, true, true)

        return transaction
    }

    public FinancialTransaction saveAsaasCardCashback(AsaasCardCashback asaasCardCashback) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        Map params = [
            provider: asaasCardCashback.customer,
            value: asaasCardCashback.value,
            transactionType: FinancialTransactionType.ASAAS_CARD_CASHBACK,
            financialTransactionBatch: financialTransactionBatch,
            description: "Cashback - ${asaasCardCashback.campaignName} - Cartão ${asaasCardCashback.asaasCard.getFormattedName().toLowerCase()}"
        ]

        FinancialTransaction transaction = save(params, true)

        saveFinancialTransactionAsaasCardCashback(transaction, asaasCardCashback)

        return transaction
    }

    public FinancialTransaction refundAsaasCardTransaction(AsaasCardTransaction asaasCardTransaction, Boolean isPartialRefund) {
        FinancialTransaction originalFinancialTransaction = FinancialTransaction.query([asaasCardTransaction: asaasCardTransaction.transactionOrigin, transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira da transação do cartão.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransactionType transactionType = FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND
        String description = "Estorno de transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"

        if (isPartialRefund) {
            transactionType = FinancialTransactionType.ASAAS_CARD_TRANSACTION_PARTIAL_REFUND
            description = "Estorno parcial de transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"
        }

        FinancialTransaction transaction = save([provider: originalFinancialTransaction.provider,
                                                    value: FinancialTransactionUtils.toCreditValue(asaasCardTransaction.netValue),
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction,
                                                    transactionType: transactionType,
                                                    description: description], true)

        saveFinancialTransactionAsaasCardTransaction(transaction, asaasCardTransaction)

        if (asaasCardTransaction.fee < 0) refundAsaasCardTransactionFee(asaasCardTransaction)

        if (originalFinancialTransaction.transactionDate == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(originalFinancialTransaction.provider, transaction.value)

        return transaction
    }

    public FinancialTransaction cancelRefundAsaasCardTransaction(AsaasCardTransaction asaasCardTransaction) {
        FinancialTransaction originalRefundFinancialTransaction = FinancialTransaction.query([asaasCardTransaction: asaasCardTransaction.transactionOrigin, transactionTypeList: [FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND, FinancialTransactionType.ASAAS_CARD_TRANSACTION_PARTIAL_REFUND]]).get()
        if (!originalRefundFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de estorno da transação do cartão.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransactionType transactionType = FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND_CANCELLATION
        String description = "Cancelamento do estorno de transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"

        if (originalRefundFinancialTransaction.transactionType.isAsaasCardTransactionPartialRefund()) {
            transactionType = FinancialTransactionType.ASAAS_CARD_TRANSACTION_PARTIAL_REFUND_CANCELLATION
            description = "Cancelamento do estorno parcial de transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"
        }

        FinancialTransaction transaction = save([provider: originalRefundFinancialTransaction.provider,
                                                    value: FinancialTransactionUtils.toDebitValue(originalRefundFinancialTransaction.value),
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalRefundFinancialTransaction,
                                                    transactionType: transactionType,
                                                    description: description], true)

        saveFinancialTransactionAsaasCardTransaction(transaction, asaasCardTransaction)
        validateBalanceWithdrawedByCustomer(transaction)

        return transaction
    }

    public FinancialTransaction saveAsaasMoneyCashback(AsaasMoneyCashback asaasMoneyCashback) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasMoneyCashback.payerCustomer,
                                                    description: "Crédito recebido ref. Cashback",
                                                    value: asaasMoneyCashback.value,
                                                    transactionType: FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CASHBACK,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionAsaasMoneyCashback(transaction, asaasMoneyCashback)

        return transaction
    }

    public FinancialTransaction refundAsaasMoneyCashback(AsaasMoneyCashback asaasMoneyCashback) {
        FinancialTransaction originalFinancialTransaction = FinancialTransactionAsaasMoneyCashback.query([column: "financialTransaction", asaasMoneyCashback: asaasMoneyCashback, financialTransactionType:  FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CASHBACK]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de Crédito recebido ref. Cashback.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: originalFinancialTransaction.provider,
                                                    description: "Estorno crédito recebido ref. Cashback",
                                                    value: FinancialTransactionUtils.toDebitValue(originalFinancialTransaction.value),
                                                    transactionType: FinancialTransactionType.ASAAS_MONEY_TRANSACTION_CASHBACK_REFUND,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction], true)

        saveFinancialTransactionAsaasMoneyCashback(transaction, asaasMoneyCashback)

        return transaction
    }

    public FinancialTransaction saveAsaasMoneyChargedFee(AsaasMoneyChargedFee asaasMoneyChargedFee, String financialDescription) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasMoneyChargedFee.payerCustomer,
                                                    description: financialDescription,
                                                    value: FinancialTransactionUtils.toDebitValue(asaasMoneyChargedFee.value),
                                                    transactionType: asaasMoneyChargedFee.type.toFinancialTransactionType(),
                                                    financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionAsaasMoneyChargedFee(transaction, asaasMoneyChargedFee)

        return transaction
    }

    public FinancialTransaction reverseAsaasMoneyChargedFee(AsaasMoneyChargedFee asaasMoneyChargedFee, String financialDescription) {
        FinancialTransaction originalFinancialTransaction = FinancialTransactionAsaasMoneyChargedFee.query([column: "financialTransaction", asaasMoneyChargedFee: asaasMoneyChargedFee, financialTransactionType: asaasMoneyChargedFee.type.toFinancialTransactionType()]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de débito da taxa de parcelamento AsaasMoney.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: originalFinancialTransaction.provider,
                                                    description: financialDescription,
                                                    value: FinancialTransactionUtils.toCreditValue(originalFinancialTransaction.value),
                                                    transactionType: asaasMoneyChargedFee.type.toReverseFinancialTransactionType(),
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction], true)

        saveFinancialTransactionAsaasMoneyChargedFee(transaction, asaasMoneyChargedFee)

        return transaction
    }

    public String getTransactionRelatedUrl(FinancialTransaction financialTransaction) {
        if (financialTransaction.transactionType.isReversal()) {
            financialTransaction = financialTransaction.reversedTransaction
        }

        Map transactionRelatedUrlParams = buildTransactionRelatedUrlParams(financialTransaction)

        if (transactionRelatedUrlParams) {
            return grailsLinkGenerator.link(transactionRelatedUrlParams + [absolute: true])
        }

        return null
    }

    private Map buildTransactionRelatedUrlParams(FinancialTransaction financialTransaction) {
        if (financialTransaction.isFromPayment()) {
            return [controller: 'payment', action: 'show', id: financialTransaction.payment.id]
        } else if (financialTransaction.isFromTransfer()) {
            return [controller: 'transfer', action: 'show', id: financialTransaction.creditTransferRequest.transfer.id]
        } else if (financialTransaction.isFromAnticipation()) {
            String receivableAnticipationController = SpringSecurityUtils.ifAllGranted('ROLE_SYSADMIN') ? 'ReceivableAnticipationAdmin' : 'ReceivableAnticipation'
            return [controller: receivableAnticipationController, action: 'show', id: financialTransaction.receivableAnticipation.id]
        } else if (financialTransaction.isFromPaymentSplit()) {
            return [controller: 'payment', action: 'show', id: financialTransaction.internalTransfer.paymentSplit.payment.id]
        } else if (financialTransaction.isFromBill()) {
            return [controller: 'bill', action: 'show', id: financialTransaction.bill.id]
        } else if (financialTransaction.isFromInvoice()) {
            return [controller: 'customerInvoice', action: 'show', id: financialTransaction.invoice.id]
        } else if (financialTransaction.isFromPaymentDunning()) {
            return [controller: 'paymentDunning', action: 'show', id: financialTransaction.chargedFee.dunning.id]
        } else if (financialTransaction.isFromRefundRequest()) {
            return [controller: 'payment', action: 'show', id: financialTransaction.refundRequest.payment.id]
        } else if (financialTransaction.isFromMessagingNotificationFee()) {
            return [controller: 'payment', action: 'show', id: financialTransaction.chargedFee.payment.id]
        } else if (financialTransaction.isFromPhoneCallNotificationFee()) {
            return [controller: 'payment', action: 'show', id: financialTransaction.chargedFee.phoneCallNotification.notificationRequest.payment.id]
        } else if (financialTransaction.isFromInstantTextMessage()) {
            return [controller: 'payment', action: 'show', id: financialTransaction.chargedFee.instantTextMessage.notificationRequest.payment.id]
        } else if (financialTransaction.isFromInternalTransfer()) {
            return [controller: 'transactionReceipt', action: 'show', id: financialTransaction.internalTransfer.getTransactionReceiptPublicId()]
        } else if (financialTransaction.transactionType.isPixTransaction()) {
            String pixTransactionController = financialTransaction.financialTransactionPixTransaction.pixTransaction.type.isEquivalentToDebit() ? "pixTransaction" : "pixCreditTransaction"
            return [controller: pixTransactionController, action: 'show', id: financialTransaction.financialTransactionPixTransaction.pixTransaction.id]
        } else if (financialTransaction.transactionType.isPixTransactionFee()) {
            String pixTransactionController = financialTransaction.chargedFee.pixTransaction.type.isEquivalentToDebit() ? "pixTransaction" : "pixCreditTransaction"
            return [controller: pixTransactionController, action: 'show', id: financialTransaction.chargedFee.pixTransaction.id]
        } else if (financialTransaction.transactionType.isAsaasCardRecharge()) {
            if (financialTransaction.asaasCardRecharge.asaasCard.type == AsaasCardType.ACESSO_CARD_MASTERCARD) return [:]
            return [controller: 'asaasCard', action: 'show', id: financialTransaction.asaasCardRecharge.asaasCard.id]
        } else if (financialTransaction.transactionType.isContractualEffectSettlement()) {
            return [controller: 'contractualEffectSettlement', action: 'showAtlas', params: ['id': financialTransaction.getContractualEffectSettlement().id]]
        } else if (financialTransaction.transactionType.isAsaasCardTransaction()) {
            AsaasCardTransaction asaasCardTransaction = FinancialTransactionAsaasCardTransaction.query([column: "asaasCardTransaction", financialTransaction: financialTransaction]).get()
            return [controller: 'asaasCardTransaction', action: 'show', params: [asaasCardId: asaasCardTransaction.asaasCardId, externalId: asaasCardTransaction.getTransactionExternalId()]]
        }

        return [:]
    }

    private FinancialTransactionAsaasMoneyTransaction saveFinancialTransactionAsaasMoneyTransaction(FinancialTransaction financialTransaction, AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        FinancialTransactionAsaasMoneyTransaction financialTransactionAsaasMoneyTransaction = new FinancialTransactionAsaasMoneyTransaction()
        financialTransactionAsaasMoneyTransaction.asaasMoneyTransactionInfo = asaasMoneyTransactionInfo
        financialTransactionAsaasMoneyTransaction.financialTransaction = financialTransaction
        financialTransactionAsaasMoneyTransaction.save(failOnError: true)

        return financialTransactionAsaasMoneyTransaction
    }

    private FinancialTransactionAsaasMoneyCashback saveFinancialTransactionAsaasMoneyCashback(FinancialTransaction financialTransaction, AsaasMoneyCashback asaasMoneyCashback) {
        FinancialTransactionAsaasMoneyCashback financialTransactionAsaasMoneyCashback = new FinancialTransactionAsaasMoneyCashback()
        financialTransactionAsaasMoneyCashback.asaasMoneyCashback = asaasMoneyCashback
        financialTransactionAsaasMoneyCashback.financialTransaction = financialTransaction
        financialTransactionAsaasMoneyCashback.save(failOnError: true)

        return financialTransactionAsaasMoneyCashback
    }

    private FinancialTransactionAsaasMoneyChargedFee saveFinancialTransactionAsaasMoneyChargedFee(FinancialTransaction financialTransaction, AsaasMoneyChargedFee asaasMoneyChargedFee) {
        FinancialTransactionAsaasMoneyChargedFee financialTransactionAsaasMoneyChargedFee = new FinancialTransactionAsaasMoneyChargedFee()
        financialTransactionAsaasMoneyChargedFee.asaasMoneyChargedFee = asaasMoneyChargedFee
        financialTransactionAsaasMoneyChargedFee.financialTransaction = financialTransaction
        financialTransactionAsaasMoneyChargedFee.save(failOnError: true)

        return financialTransactionAsaasMoneyChargedFee
    }

    private FinancialTransactionPaymentCustodyItem saveFinancialTransactionPaymentCustodyItem(FinancialTransaction financialTransaction, PaymentCustodyItem paymentCustodyItem) {
        FinancialTransactionPaymentCustodyItem financialTransactionPaymentCustodyItem = new FinancialTransactionPaymentCustodyItem()
        financialTransactionPaymentCustodyItem.paymentCustodyItem = paymentCustodyItem
        financialTransactionPaymentCustodyItem.financialTransaction = financialTransaction
        financialTransactionPaymentCustodyItem.save(failOnError: true)

        return financialTransactionPaymentCustodyItem
    }

    private FinancialTransaction saveAsaasCardTransactionFee(AsaasCardTransaction asaasCardTransaction) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: asaasCardTransaction.customer,
                                                    value: FinancialTransactionUtils.toDebitValue(asaasCardTransaction.fee),
                                                    transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION_FEE,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    description: "Taxa para transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"], true)

        saveFinancialTransactionAsaasCardTransaction(transaction, asaasCardTransaction)

        return transaction
    }

    private FinancialTransactionAsaasCardTransaction saveFinancialTransactionAsaasCardTransaction(FinancialTransaction financialTransaction, AsaasCardTransaction asaasCardTransaction) {
        FinancialTransactionAsaasCardTransaction financialTransactionAsaasCardTransaction = new FinancialTransactionAsaasCardTransaction()
        financialTransactionAsaasCardTransaction.asaasCardTransaction = asaasCardTransaction
        financialTransactionAsaasCardTransaction.financialTransaction = financialTransaction
        financialTransactionAsaasCardTransaction.save(failOnError: true)

        return financialTransactionAsaasCardTransaction
    }

    private FinancialTransactionAsaasCardCashback saveFinancialTransactionAsaasCardCashback(FinancialTransaction financialTransaction, AsaasCardCashback asaasCardCashback) {
        FinancialTransactionAsaasCardCashback financialTransactionAsaasCardCashback = new FinancialTransactionAsaasCardCashback()
        financialTransactionAsaasCardCashback.asaasCardCashback = asaasCardCashback
        financialTransactionAsaasCardCashback.financialTransaction = financialTransaction

        return financialTransactionAsaasCardCashback.save(failOnError: true)
    }

    private FinancialTransactionAsaasCardBalanceRefund saveFinancialTransactionAsaasCardBalanceRefund(FinancialTransaction financialTransaction, AsaasCardBalanceRefund asaasCardBalanceRefund) {
        FinancialTransactionAsaasCardBalanceRefund financialTransactionAsaasCardBalanceRefund = new FinancialTransactionAsaasCardBalanceRefund()
        financialTransactionAsaasCardBalanceRefund.asaasCardBalanceRefund = asaasCardBalanceRefund
        financialTransactionAsaasCardBalanceRefund.financialTransaction = financialTransaction
        financialTransactionAsaasCardBalanceRefund.save(failOnError: true)

        return financialTransactionAsaasCardBalanceRefund
    }

    public FinancialTransaction savePixTransactionDebit(PixTransaction pixTransaction, String financialDescription) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()
        FinancialTransaction transaction = saveFinancialTransactionForPixTransactionDebit(pixTransaction, financialDescription, financialTransactionBatch)

        Boolean hasConsumedBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimitConsumation.query([exists: true, pixTransactionId: pixTransaction.id, pixTransactionBankAccountInfoCheckoutLimitDeleted: false]).get().asBoolean()
        validateBalanceWithdrawedByCustomer(transaction, !hasConsumedBankAccountInfoCheckoutLimit)

        return transaction
    }

    public void savePixTransactionDebitList(Customer customer, List<PixTransaction> pixTransactionList) {
        BigDecimal totalTransactionValue = 0
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        for (PixTransaction pixTransaction : pixTransactionList) {
            String financialDescription = PixUtils.buildDebitFinancialDescription(pixTransaction.originType, pixTransaction.initiatedByExternalInstitution, pixTransaction.cashValueFinality, pixTransaction.externalAccount.name)
            saveFinancialTransactionForPixTransactionDebit(pixTransaction, financialDescription, financialTransactionBatch)

            totalTransactionValue = totalTransactionValue + BigDecimalUtils.abs(pixTransaction.value)
        }

        validateBalanceWithdrawedByCustomer(customer, totalTransactionValue, FinancialTransactionType.PIX_TRANSACTION_DEBIT, true)
    }

    public FinancialTransaction refundPixTransactionDebit(PixTransaction pixTransaction) {
        final String financialDescription = "Estorno de transação via Pix"

        Map reversedTransactionQueryParameters = [pixTransaction: pixTransaction, transactionType: FinancialTransactionType.PIX_TRANSACTION_DEBIT]

        PixTransaction refundedPixTransaction = pixTransaction.getRefundedTransaction()
        if (refundedPixTransaction) reversedTransactionQueryParameters.pixTransaction = refundedPixTransaction

        FinancialTransaction reversedTransaction = FinancialTransaction.query(reversedTransactionQueryParameters).get()
        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem. [pixTransactionId: ${pixTransaction.id}]")

        BigDecimal refundedValue = pixTransaction.value
        if (!refundedPixTransaction) refundedValue = FinancialTransactionUtils.toCreditValue(reversedTransaction.value)

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: reversedTransaction.provider,
                                                    value: refundedValue,
                                                    description: financialDescription,
                                                    reversedTransaction: reversedTransaction,
                                                    transactionType: FinancialTransactionType.PIX_TRANSACTION_DEBIT_REFUND,
                                                    financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionPixTransaction(transaction, pixTransaction)
        if (reversedTransaction.transactionDate == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(reversedTransaction.provider, transaction.value)

        return transaction
    }

    public FinancialTransaction savePixTransactionCredit(PixTransaction pixTransaction) {
        final String financialDescription = "Recebimento via Pix"

        BigDecimal value = pixTransaction.value
        if (pixTransaction.getRefundedTransaction()) value = FinancialTransactionUtils.toCreditValue(value)

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: pixTransaction.customer,
                                                    value: value,
                                                    description: financialDescription,
                                                    transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT,
                                                    financialTransactionBatch: financialTransactionBatch], true)
        saveFinancialTransactionPixTransaction(transaction, pixTransaction)

        return transaction
    }

    public FinancialTransaction cancelPixTransactionCreditRefund(PixTransaction pixTransaction) {
        if (pixTransaction.payment.asBoolean()) throw new BusinessException("Para estornos Pix vinculados a uma cobrança é necessário solicitar o cancelamento de estorno pela cobrança")
        final String financialDescription = "Cancelamento de estorno de recebimento via Pix"

        FinancialTransaction reversedTransaction = FinancialTransaction.query([pixTransaction: pixTransaction, transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT_REFUND]).get()
        if (!reversedTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem. [pixTransactionId: ${pixTransaction.id}]")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: pixTransaction.customer,
                                                 value: FinancialTransactionUtils.toCreditValue(pixTransaction.value),
                                                 transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT_REFUND_CANCELLATION,
                                                 description: financialDescription,
                                                 reversedTransaction: reversedTransaction,
                                                 financialTransactionBatch: financialTransactionBatch], true)

        saveFinancialTransactionPixTransaction(transaction, pixTransaction)
        if (reversedTransaction.transactionDate == new Date().clearTime()) customerCheckoutLimitService.refundDailyLimit(transaction.provider, transaction.value)

        return transaction
    }

    public FinancialTransaction refundPixTransactionCredit(PixTransaction pixTransaction) {
        if (pixTransaction.payment) throw new BusinessException("Para estornos Pix vinculados a uma cobrança é necessário solicitar o estorno pela cobrança")

        final String financialDescription = "Estorno de recebimento via Pix"

        PixTransaction refundedPixTransaction = pixTransaction.getRefundedTransaction()
        if (!refundedPixTransaction) throw new RuntimeException("Não foi possível localizar a Transação Pix de origem.")

        FinancialTransaction originalFinancialTransaction = FinancialTransaction.query([pixTransaction: refundedPixTransaction, transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: originalFinancialTransaction.provider,
                                                    value: pixTransaction.value,
                                                    description: financialDescription,
                                                    transactionType: FinancialTransactionType.PIX_TRANSACTION_CREDIT_REFUND,
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction], true)

        validateBalanceWithdrawedByCustomer(transaction)
        saveFinancialTransactionPixTransaction(transaction, pixTransaction)

        return transaction
    }

    public FinancialTransaction saveMobilePhoneRecharge(MobilePhoneRecharge mobilePhoneRecharge) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        Map params = [
            provider: mobilePhoneRecharge.customer,
            value: FinancialTransactionUtils.toDebitValue(mobilePhoneRecharge.value),
            transactionType: FinancialTransactionType.MOBILE_PHONE_RECHARGE,
            financialTransactionBatch: financialTransactionBatch,
            description: "Recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(mobilePhoneRecharge.phoneNumber)} (${mobilePhoneRecharge.operatorName})"
        ]

        FinancialTransaction transaction = save(params, true)

        saveFinancialTransactionMobilePhoneRecharge(transaction, mobilePhoneRecharge)

        validateBalanceWithdrawedByCustomer(transaction)

        return transaction
    }

    public FinancialTransaction refundMobilePhoneRecharge(MobilePhoneRecharge mobilePhoneRecharge) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransactionMobilePhoneRecharge financialTransactionMobilePhoneRecharge = FinancialTransactionMobilePhoneRecharge.query([mobilePhoneRecharge: mobilePhoneRecharge]).get()
        if (!financialTransactionMobilePhoneRecharge) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        Map params = [
            provider: mobilePhoneRecharge.customer,
            value: mobilePhoneRecharge.value,
            transactionType: FinancialTransactionType.REFUND_MOBILE_PHONE_RECHARGE,
            financialTransactionBatch: financialTransactionBatch,
            reversedTransaction: financialTransactionMobilePhoneRecharge.financialTransaction,
            description: "Estorno de recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(mobilePhoneRecharge.phoneNumber)} (${mobilePhoneRecharge.operatorName})"
        ]

        FinancialTransaction transaction = save(params, true)

        saveFinancialTransactionMobilePhoneRecharge(transaction, mobilePhoneRecharge)

        return transaction
    }

    public FinancialTransaction cancelMobilePhoneRecharge(MobilePhoneRecharge mobilePhoneRecharge) {
        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransactionMobilePhoneRecharge financialTransactionMobilePhoneRecharge = FinancialTransactionMobilePhoneRecharge.query([mobilePhoneRecharge: mobilePhoneRecharge]).get()
        if (!financialTransactionMobilePhoneRecharge) throw new RuntimeException("Não foi possível localizar a movimentação financeira de origem.")

        Map params = [
            provider: mobilePhoneRecharge.customer,
            value: mobilePhoneRecharge.value,
            transactionType: FinancialTransactionType.CANCEL_MOBILE_PHONE_RECHARGE,
            financialTransactionBatch: financialTransactionBatch,
            reversedTransaction: financialTransactionMobilePhoneRecharge.financialTransaction,
            description: "Cancelamento de recarga para o celular ${PhoneNumberUtils.formatPhoneNumber(mobilePhoneRecharge.phoneNumber)} (${mobilePhoneRecharge.operatorName})"
        ]

        FinancialTransaction transaction = save(params, true)

        saveFinancialTransactionMobilePhoneRecharge(transaction, mobilePhoneRecharge)

        return transaction
    }

    private FinancialTransactionAsaasCardBillPayment saveFinancialTransactionAsaasCardBillPayment(FinancialTransaction financialTransaction, AsaasCardBillPayment asaasCardBillPayment) {
        FinancialTransactionAsaasCardBillPayment financialTransactionAsaasCardBillPayment = new FinancialTransactionAsaasCardBillPayment()
        financialTransactionAsaasCardBillPayment.asaasCardBillPayment = asaasCardBillPayment
        financialTransactionAsaasCardBillPayment.financialTransaction = financialTransaction
        financialTransactionAsaasCardBillPayment.save(failOnError: true)

        return financialTransactionAsaasCardBillPayment
    }

    private FinancialTransactionAsaasCardBillPaymentRefund saveFinancialTransactionAsaasCardBillPaymentRefund(FinancialTransaction financialTransaction, AsaasCardBillPaymentRefund asaasCardBillPaymentRefund) {
        FinancialTransactionAsaasCardBillPaymentRefund financialTransactionAsaasCardBillPayment = new FinancialTransactionAsaasCardBillPaymentRefund()
        financialTransactionAsaasCardBillPayment.asaasCardBillPaymentRefund = asaasCardBillPaymentRefund
        financialTransactionAsaasCardBillPayment.financialTransaction = financialTransaction
        financialTransactionAsaasCardBillPayment.save(failOnError: true)

        return financialTransactionAsaasCardBillPayment
    }

    private FinancialTransactionMobilePhoneRecharge saveFinancialTransactionMobilePhoneRecharge(FinancialTransaction financialTransaction, MobilePhoneRecharge mobilePhoneRecharge) {
        FinancialTransactionMobilePhoneRecharge financialTransactionMobilePhoneRecharge = new FinancialTransactionMobilePhoneRecharge()
        financialTransactionMobilePhoneRecharge.mobilePhoneRecharge = mobilePhoneRecharge
        financialTransactionMobilePhoneRecharge.financialTransaction = financialTransaction
        financialTransactionMobilePhoneRecharge.save(failOnError: true)

        return financialTransactionMobilePhoneRecharge
    }

    private FinancialTransactionPixTransaction saveFinancialTransactionPixTransaction(FinancialTransaction financialTransaction, PixTransaction pixTransaction) {
        FinancialTransactionPixTransaction financialTransactionPixTransaction = new FinancialTransactionPixTransaction()
        financialTransactionPixTransaction.pixTransaction = pixTransaction
        financialTransactionPixTransaction.financialTransaction = financialTransaction
        financialTransactionPixTransaction.save(failOnError: true)
        return financialTransactionPixTransaction
    }

    private void validateBalanceWithdrawedByCustomer(FinancialTransaction transaction, Boolean consumeDailyLimit = true) {
        validateBalanceWithdrawedByCustomer(transaction.provider, transaction.value, transaction.transactionType, consumeDailyLimit, false)
    }

    private void validateBalanceWithdrawedByCustomer(Customer customer, BigDecimal value, FinancialTransactionType type, Boolean consumeDailyLimit = true, Boolean consumeDailyBalanceWithTimeout = false) {
        if (consumeDailyLimit) customerCheckoutLimitService.consumeDailyLimit(customer, BigDecimalUtils.abs(value), consumeDailyBalanceWithTimeout)
        if (!CustomerParameter.getValue(customer, CustomerParameterName.BYPASS_CUSTOMER_BALANCE_RECALCULATION)) recalculateBalance(customer)

        BigDecimal finalBalance = FinancialTransaction.getCustomerBalance(customer)
        if (finalBalance >= 0) return

        String additionalInfo = "[customer.id: ${customer.id}, saldo final: ${finalBalance}, valor da transação: ${BigDecimalUtils.abs(value)}, tipo da transação: ${type}]"

        Boolean negativeBalanceCausedByCurrentTransaction = ((finalBalance + BigDecimalUtils.abs(value)) >= 0)
        if (negativeBalanceCausedByCurrentTransaction) {
            AsaasLogger.error("FinancialTransactionService.validateBalanceWithdrawedByCustomer() -> Operação resultaria em saldo negativo para o Customer. ${additionalInfo}")
        } else {
            AsaasLogger.error("FinancialTransactionService.validateBalanceWithdrawedByCustomer() -> Customer com saldo negativo tentando efetuar uma nova transação. ${additionalInfo}")
        }
        throw new RuntimeException("O cliente não possui saldo suficiente para realizar esta transação.")
    }

    private FinancialTransaction refundAsaasCardTransactionFee(AsaasCardTransaction asaasCardTransaction) {
        FinancialTransaction originalFinancialTransaction = FinancialTransaction.query([asaasCardTransaction: asaasCardTransaction.transactionOrigin, transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION_FEE]).get()
        if (!originalFinancialTransaction) throw new RuntimeException("Não foi possível localizar a movimentação financeira da taxa de transação do cartão.")

        FinancialTransactionBatch financialTransactionBatch = new FinancialTransactionBatch().save()

        FinancialTransaction transaction = save([provider: originalFinancialTransaction.provider,
                                                    value: FinancialTransactionUtils.toCreditValue(asaasCardTransaction.fee),
                                                    financialTransactionBatch: financialTransactionBatch,
                                                    reversedTransaction: originalFinancialTransaction,
                                                    transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION_FEE_REFUND,
                                                    description: "Estorno de taxa para transação efetuada com o cartão ${asaasCardTransaction.asaasCard.getFormattedName()}"], true)

        saveFinancialTransactionAsaasCardTransaction(transaction, asaasCardTransaction)

        return transaction
    }

    private void saveChargedFeePromotionalCodeCredit(ChargedFee chargedFee, BigDecimal creditValue, FinancialTransactionBatch financialTransactionBatch, String promotionalCodeDescription) {
        save([provider: chargedFee.customer,
                value: BigDecimalUtils.abs(creditValue),
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                chargedFee: chargedFee,
                financialTransactionBatch: financialTransactionBatch,
                description: promotionalCodeDescription])
    }

    private void reverseChargedFeePromotionalCodeIfNecessary(FinancialTransaction reversedTransaction, FinancialTransactionBatch financialTransactionBatch, String refundPromotionalCodeDescription, BigDecimal promotionalCodeUsedValue) {
        if (!promotionalCodeUsedValue) return

        save([provider: reversedTransaction.provider,
                value: FinancialTransactionUtils.toDebitValue(promotionalCodeUsedValue),
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_DEBIT,
                chargedFee: reversedTransaction.chargedFee,
                reversedTransaction: reversedTransaction,
                financialTransactionBatch: financialTransactionBatch,
                description: refundPromotionalCodeDescription])
    }

    private void savePaymentPostalServiceBatchPromotionalCodeCredit(PaymentPostalServiceBatch paymentPostalServiceBatch, BigDecimal creditValue, FinancialTransactionBatch financialTransactionBatch) {
        save([provider: paymentPostalServiceBatch.customer,
                value: BigDecimalUtils.abs(creditValue),
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                paymentPostalServiceBatch: paymentPostalServiceBatch,
                description: "Desconto na taxa de envio de ${paymentPostalServiceBatch.items.size().toString()} boletos via Correios",
                financialTransactionBatch: financialTransactionBatch])
    }

    private FinancialTransaction save(Map params, Boolean flushOnSave = false) {
        final Integer descriptionMaxLength = 255

        if (params.description?.length() > descriptionMaxLength) params.description = Utils.truncateString(params.description, descriptionMaxLength)

        params.transactionDate = new Date().clearTime()

        FinancialTransaction financialTransaction = new FinancialTransaction(params)

        if (!financialTransaction.description) {
            financialTransaction.description = Utils.truncateString(financialTransaction.getBuildDescription(), descriptionMaxLength)
        }

        financialTransaction.save(flush: flushOnSave, failOnError: true)

        CustomerStatistic.expire(financialTransaction.provider, CustomerStatisticName.TOTAL_VALUE_AVAILABLE_FOR_ANTICIPATION)

        financialTransactionAfterSaveAsyncActionService.save(financialTransaction)

        return financialTransaction
    }

    private FinancialTransaction saveFinancialTransactionForPixTransactionDebit(PixTransaction pixTransaction, String financialDescription, FinancialTransactionBatch financialTransactionBatch) {
        if (!financialTransactionBatch) throw new RuntimeException("Operação inválida para a transação Pix ${pixTransaction.id}")

        FinancialTransaction transaction = save([
            provider: pixTransaction.customer,
            value: pixTransaction.value,
            description: financialDescription,
            transactionType: FinancialTransactionType.PIX_TRANSACTION_DEBIT,
            financialTransactionBatch: financialTransactionBatch
        ], true)

        saveFinancialTransactionPixTransaction(transaction, pixTransaction)

        return transaction
    }

    private BigDecimal getPaymentCreditValue(Customer customer) {
        CustomerPaymentReceivedValueConsolidatedCache customerPaymentReceivedValueConsolidatedCache = CustomerPaymentReceivedValueConsolidatedCacheRepository.query(["customerId": customer.id]).readOnly().get()

        Map searchPaymentValues = [:]
        searchPaymentValues."customerId" = customer.id

        BigDecimal paymentValueConsolidated = 0.00
        BigDecimal paymentAnticipatedRefundedFee = 0.0
        if (customerPaymentReceivedValueConsolidatedCache) {
            paymentValueConsolidated = customerPaymentReceivedValueConsolidatedCache.value
            searchPaymentValues."creditDate[gt]" = customerPaymentReceivedValueConsolidatedCache.date

            paymentAnticipatedRefundedFee = getPaymentAnticipatedRefundedFee(customer, customerPaymentReceivedValueConsolidatedCache.date, customerPaymentReceivedValueConsolidatedCache.dateCreated)
        }

        BigDecimal paymentReceivedValue = Payment.sumNetValue(searchPaymentValues + [status: PaymentStatus.RECEIVED]).get()
        BigDecimal paymentRefundedValue = Payment.sumNetValue(searchPaymentValues + ["paymentRefundValueDebited[exists]": true, statusList: [PaymentStatus.REFUNDED, PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUND_IN_PROGRESS]]).get()
        BigDecimal paymentChargebackValue = Payment.sumNetValue(searchPaymentValues + ["paymentDate[isNotNull]": true, billingType: BillingType.MUNDIPAGG_CIELO, statusList: [PaymentStatus.CHARGEBACK_REQUESTED, PaymentStatus.CHARGEBACK_DISPUTE, PaymentStatus.AWAITING_CHARGEBACK_REVERSAL]]).get()

        return paymentValueConsolidated + paymentReceivedValue + paymentRefundedValue + paymentChargebackValue - paymentAnticipatedRefundedFee
    }

    private BigDecimal getPaymentAnticipatedRefundedFee(Customer customer, Date paymentReceivedValueCacheConsolidatedDate, Date paymentReceivedValueCacheDateCreated) {
        Map search = [:]
        search.partnerAcquisitionCustomer = customer
        search.partnerAcquisitionNotDeleted = true
        search."partnerAcquisitionStatusList[in]" = [ReceivableAnticipationPartnerAcquisitionStatus.DEBITED, ReceivableAnticipationPartnerAcquisitionStatus.OVERDUE]
        search."paymentAnticipatedRefundedAndDebited[exists]" = ["paymentRefundedDate[gt]": paymentReceivedValueCacheDateCreated, "paymentCreditDate[le]": paymentReceivedValueCacheConsolidatedDate]

        return ReceivableAnticipationPartnerAcquisitionItem.sumPaymentFee(search).get()
    }
}
