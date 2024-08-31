package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.chargeback.ChargebackStatus
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.PaymentCreditCardDepositFileItem
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.financialstatement.FinancialStatementAcquirerUtils
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.payment.PaymentStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementCreditCardTransitoryService {

    def financialStatementService

    public void createAllStatementsForCreditCardCaptured(Date date) {
        final Date creditCardTransitoryStatementsStartDate = CustomDateUtils.fromString("01/11/2023")
        if (date < creditCardTransitoryStatementsStartDate) return

        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            Utils.withNewTransactionAndRollbackOnError({
                createForCreditCardCaptured(acquirer, date)
            }, [
                logErrorMessage: "CreditCardTransitoryFinancialStatement.FinancialStatementCreditCardTransitoryService.createForCreditCardCaptured >> Falha ao gerar os lançamentos contábeis de cartão de crédito.",
                onError: { Exception exception -> throw exception }
            ])
        }
    }

    public void createAllStatementsForCreditCardPaymentReceived(Date date) {
        final Date creditCardTransitoryStatementsStartDate = CustomDateUtils.fromString("01/11/2023")
        if (date < creditCardTransitoryStatementsStartDate) return

        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            Utils.withNewTransactionAndRollbackOnError({
                createForCreditCardPaymentReceived(acquirer, date)
            }, [
                logErrorMessage: "CreditCardTransitoryFinancialStatement.FinancialStatementCreditCardTransitoryService.createForCreditCardPaymentReceived >> Falha ao gerar os lançamentos contábeis de cartão de crédito liquidado.",
                onError: { Exception exception -> throw exception }
            ])

            Utils.flushAndClearSession()
        }
    }

    public void createAllStatementsForChargeback(Date date) {
        final Date creditCardTransitoryStatementsStartDate = CustomDateUtils.fromString("01/11/2023")
        if (date < creditCardTransitoryStatementsStartDate) return

        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            Utils.withNewTransactionAndRollbackOnError({
                createForChargeback(acquirer, date)
            }, [
                logErrorMessage: "CreditCardTransitoryFinancialStatement.FinancialStatementCreditCardTransitoryService.createForChargeback >> Falha ao gerar os lançamentos contábeis de chargeback de cartão de crédito.",
                onError: { Exception exception -> throw exception }
            ])
        }
    }

    public void createAllStatementsForRefund(Date date) {
        final Date creditCardTransitoryStatementsStartDate = CustomDateUtils.fromString("01/11/2023")
        if (date < creditCardTransitoryStatementsStartDate) return

        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            Utils.withNewTransactionAndRollbackOnError({
                createForRefund(acquirer, date)
            }, [
                logErrorMessage: "CreditCardTransitoryFinancialStatement.FinancialStatementCreditCardTransitoryService.createForRefund >> Falha ao gerar os lançamentos contábeis de estorno de cartão de crédito.",
                onError: { Exception exception -> throw exception }
            ])
        }
    }

    public void createFinancialStatementsForCreditCardTransactionFee(CreditCardAcquirer acquirer, Date importDate) {
        Utils.withNewTransactionAndRollbackOnError({
            FinancialStatementType statementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeTransitoryDebitFinancialStatementType(acquirer)

            Map search = [:]
            search.columnList = ["payment.id", "payment.confirmedDate", "transactionFeeAmount"]
            search.creditCardTransactionInfoAcquirer = acquirer
            final Integer hoursDelayToAvoidProcessingQueue = 12
            search."dateCreated[ge]" = CustomDateUtils.sumHours(importDate, hoursDelayToAvoidProcessingQueue * -1)
            search."dateCreated[le]" = new Date()
            search."paymentConfirmedDate[isNotNull]" = true
            search."financialStatementTypeList[notExists]" = [statementType]
            search.disableSort = true

            List<Map> paymentTransactionFeeAmountInfoList = PaymentCreditCardDepositFileItem.query(search).list()
            if (!paymentTransactionFeeAmountInfoList) return

            Map<Date, List<Map>> paymentTransactionFeeAmountInfoListGroupedByDateMap = paymentTransactionFeeAmountInfoList.groupBy { it."payment.confirmedDate" }

            for (Date confirmedDate : paymentTransactionFeeAmountInfoListGroupedByDateMap.keySet()) {
                List<Map> paymentTransactionFeeAmountInfoListGroupedByDate = paymentTransactionFeeAmountInfoListGroupedByDateMap[confirmedDate]

                Map financialStatementInfoMap = [
                    financialStatementType: statementType,
                    totalValue: paymentTransactionFeeAmountInfoListGroupedByDate.transactionFeeAmount.sum()
                ]

                financialStatementService.saveForPaymentIdList(paymentTransactionFeeAmountInfoListGroupedByDate.collect { it."payment.id" }, [financialStatementInfoMap], confirmedDate, null)
            }
        }, [logErrorMessage: "CreditCardTransitoryFinancialStatement.FinancialStatementCreditCardTransitoryService.createFinancialStatementsForCreditCardTransactionFee >> Falha ao gerar os lançamentos contábeis de custos de cartão de crédito."])
    }

    private void createForCreditCardCaptured(CreditCardAcquirer acquirer, Date date) {
        FinancialStatementType toReceiveStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardToReceiveTransitoryCreditFinancialStatementType(acquirer)
        FinancialStatementType toPayStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardToPayTransitoryDebitFinancialStatementType(acquirer)
        FinancialStatementType feeStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardFeeTransitoryCreditFinancialStatementType(acquirer)

        List<Map> paymentMapList = CreditCardTransactionInfo.query([
            columnList: ["payment.id", "payment.value", "payment.netValue"],
            acquirer: acquirer,
            paymentConfirmedDate: date,
            "financialStatementTypeList[notExists]": [toReceiveStatementType],
            disableSort: true
        ]).list()
        if (!paymentMapList) return

        BigDecimal paymentTotalValue = paymentMapList.collect { BigDecimalUtils.abs(it."payment.value") }.sum()

        BigDecimal feeStatementTypeTotalValue = 0
        for (Map paymentMap : paymentMapList) {
            BigDecimal feeDifference = BigDecimalUtils.abs(paymentMap."payment.value") - BigDecimalUtils.abs(paymentMap."payment.netValue")
            if (feeDifference == 0) {
                BigDecimal transactionValue = FinancialTransaction.query([paymentId: paymentMap."payment.id", transactionType: FinancialTransactionType.PAYMENT_FEE, column: "value"]).get()
                feeStatementTypeTotalValue += BigDecimalUtils.abs(transactionValue ?: 0)
            } else {
                feeStatementTypeTotalValue += feeDifference
            }
        }

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: toReceiveStatementType,
             totalValue: paymentTotalValue],
            [financialStatementType: toPayStatementType,
             totalValue: paymentTotalValue],
            [financialStatementType: feeStatementType,
             totalValue: feeStatementTypeTotalValue]
        ]

        List<Long> paymentIdList = paymentMapList.collect { it."payment.id" }
        financialStatementService.saveForPaymentIdList(paymentIdList, financialStatementInfoMapList, date, null)
    }

    private void createForCreditCardPaymentReceived(CreditCardAcquirer acquirer, Date date) {
        final Date statementProcessingWithTransactionStartDate = CustomDateUtils.fromString("05/07/2024")
        if (date >= statementProcessingWithTransactionStartDate) return

        FinancialStatementType statementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardReceivedTransitoryCreditFinancialStatementType(acquirer)

        List<FinancialTransaction> transactionList = listCreditedCreditCardFinancialTransaction(acquirer, FinancialTransactionType.PAYMENT_RECEIVED, statementType, date)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: statementType]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createForChargeback(CreditCardAcquirer acquirer, Date date) {
        FinancialStatementType chargebackDebitStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardChargebackTransitoryDebitFinancialStatementType(acquirer)

        Map search = [:]
        search.status = ChargebackStatus.DONE
        search."lastUpdated[ge]" = date
        search."lastUpdated[le]" = CustomDateUtils.setTimeToEndOfDay(date)
        search."financialStatementTypeList[notExists]" = [chargebackDebitStatementType]
        search.disableSort = true

        List<Chargeback> paymentChargebackList = Chargeback.query(search + ["paymentCreditCardTransactionInfoAcquirer[exists]": acquirer]).list(readOnly: true)
        List<Chargeback> installmentChargebackList = Chargeback.query(search + ["installmentCreditCardTransactionInfoAcquirer[exists]": acquirer]).list(readOnly: true)
        if (!paymentChargebackList && !installmentChargebackList) return

        List<Chargeback> chargebackList = paymentChargebackList + installmentChargebackList
        FinancialStatement financialStatement = financialStatementService.save(chargebackDebitStatementType, date, null, chargebackList.value.sum())

        financialStatementService.saveItems(financialStatement, chargebackList)

        List<Map> unpaidPaymentMapList = paymentChargebackList.collect { Chargeback chargeback ->
            if (!chargeback.payment.paymentDate) {
                return [id: chargeback.payment.id,
                        value: chargeback.payment.value,
                        netValue: chargeback.payment.netValue]
            }
        }.findAll { it }

        List<Map> unpaidInstallmentPaymentMapList = installmentChargebackList.collectMany { Chargeback chargeback ->
            chargeback.installment.payments.findAll { Payment payment -> !payment.paymentDate }
        }.collect { Payment payment ->
            return [id: payment.id,
                    value: payment.value,
                    netValue: payment.netValue]
        }

        List<Map> paymentMapList = unpaidPaymentMapList + unpaidInstallmentPaymentMapList
        if (!paymentMapList) return

        BigDecimal paymentTotalValue = paymentMapList.collect { BigDecimalUtils.abs(it.value) }.sum()
        BigDecimal paymentTotalNetValue = paymentMapList.collect { BigDecimalUtils.abs(it.netValue) }.sum()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementAcquirerUtils.getAcquirerCreditCardCustomerChargebackTransitoryCreditFinancialStatementType(acquirer),
             totalValue: paymentTotalValue],
            [financialStatementType: FinancialStatementAcquirerUtils.getAcquirerCreditCardFeeChargebackTransitoryDebitFinancialStatementType(acquirer),
             totalValue: paymentTotalValue - paymentTotalNetValue]
        ]

        financialStatementService.saveForPaymentIdList(paymentMapList.id, financialStatementInfoMapList, date, null)
    }

    private void createForRefund(CreditCardAcquirer acquirer, Date date) {
        FinancialStatementType refundTransitoryDebitStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardRefundTransitoryDebitFinancialStatementType(acquirer)
        FinancialStatementType refundTransitoryCreditStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardRefundTransitoryCreditFinancialStatementType(acquirer)
        FinancialStatementType feeRefundTransitoryDebitStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardFeeChargebackTransitoryDebitFinancialStatementType(acquirer)

        Map search = [:]
        search."refundedDate[ge]" = date
        search."refundedDate[le]" = CustomDateUtils.setTimeToEndOfDay(date)
        search.statusList = [PaymentStatus.REFUNDED]
        search.billingType = BillingType.MUNDIPAGG_CIELO
        search."financialStatementTypeList[notExists]" = [refundTransitoryDebitStatementType]
        search."chargeback[notExists]" = true
        search."installmentChargeback[notExists]" = true
        search.acquirer = acquirer
        search.columnList = ["id", "value", "netValue", "paymentDate"]
        search.disableSort = true

        List<Map> paymentMapList = Payment.query(search).list()
        if (!paymentMapList) return

        BigDecimal paymentTotalValue = paymentMapList.sum { it.value }
        Map financialStatementInfoMap = [financialStatementType: refundTransitoryDebitStatementType,
                                         totalValue: paymentTotalValue]
        financialStatementService.saveForPaymentIdList(paymentMapList.id, [financialStatementInfoMap], date, null)

        List<Map> unpaidPaymentMapList = paymentMapList.findAll { !it.paymentDate }
        if (!unpaidPaymentMapList) return

        BigDecimal paymentTotalUnpaidValue = unpaidPaymentMapList.sum { it.value }
        BigDecimal paymentTotalUnpaidFeeValue = unpaidPaymentMapList.sum { it.value - it.netValue }
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: refundTransitoryCreditStatementType,
             totalValue: paymentTotalUnpaidValue],
            [financialStatementType: feeRefundTransitoryDebitStatementType,
             totalValue: paymentTotalUnpaidFeeValue]
        ]

        financialStatementService.saveForPaymentIdList(unpaidPaymentMapList.id, financialStatementInfoMapList, date, null)
    }

    private List<FinancialTransaction> listCreditedCreditCardFinancialTransaction(CreditCardAcquirer acquirer, FinancialTransactionType transactionType, FinancialStatementType statementType, Date date) {
        Map search = [:]
        search.creditedCreditCardAcquirer = acquirer
        search.transactionType = transactionType
        search.paymentBillingType = BillingType.MUNDIPAGG_CIELO
        search.paymentStatus = PaymentStatus.RECEIVED
        search."financialStatementTypeList[notExists]" = [statementType]
        search.transactionDate = date

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
