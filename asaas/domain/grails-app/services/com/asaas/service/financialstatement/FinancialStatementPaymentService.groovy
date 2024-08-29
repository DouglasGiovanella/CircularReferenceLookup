package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementPaymentService {

    def financialStatementAsaasErpService
    def financialStatementAsaasMoneyService
    def financialStatementPlanService
    def financialStatementService

    public void saveForConfirmedPaymentsWithNewTransaction(Bank bank, List<Long> confirmedPaymentIdList) {
        Utils.withNewTransactionAndRollbackOnError({
            saveForConfirmedPayments(bank, confirmedPaymentIdList)
        })
    }

    public void saveForConfirmedPayments(Bank bank, List<Long> confirmedPaymentIdList) {
        List<Long> asaasErpPaymentIdList = []
        List<Long> asaasMoneyCreditCardPaymentIdList = []
        List<Long> planPaymentIdList = []
        List<Payment> receivedPaymentList = []

        List<Payment> confirmedPaymentList = []
        final Integer paymentIdListCollateSize = 2500
        for (List<Long> paymentIdPartialList : confirmedPaymentIdList.collate(paymentIdListCollateSize)) {
            confirmedPaymentList += Payment.getAll(paymentIdPartialList)
        }

        for (Payment payment : confirmedPaymentList) {
            Long paymentId = payment.id
            if (payment.isPlanPayment()) {
                planPaymentIdList << paymentId
            } else if (payment.provider.isAsaasErpProvider()) {
                asaasErpPaymentIdList << paymentId
            } else if (payment.billingType.isCreditCard() && payment.provider.isAsaasMoneyProvider()) {
                asaasMoneyCreditCardPaymentIdList << paymentId
            } else {
                receivedPaymentList << payment
            }
        }

        if (receivedPaymentList) saveForReceivedPayments(bank, receivedPaymentList)

        if (planPaymentIdList) financialStatementPlanService.create(bank, planPaymentIdList)

        if (asaasErpPaymentIdList) financialStatementAsaasErpService.save(bank, asaasErpPaymentIdList)

        if (asaasMoneyCreditCardPaymentIdList) financialStatementAsaasMoneyService.createForProviderCreditCard(bank, asaasMoneyCreditCardPaymentIdList)
    }

    public void saveFeeDiscount(List<FinancialTransaction> feeDiscountTransactionList, Bank bank) {
        if (!feeDiscountTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE],
            [financialStatementType: FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", feeDiscountTransactionList, financialStatementInfoMapList, bank)
    }

    private void saveForReceivedPayments(Bank bank, List<Payment> paymentList) {
        if (!paymentList) return

        switch (paymentList.first().billingType) {
            case BillingType.BOLETO:
                financialStatementService.createForReceivedBankSlipPayments(paymentList, bank, true)
                break
            case BillingType.PIX:
                Map<Date, List<Payment>> paymentListGroupedByCreditDateMap = paymentList.groupBy { Payment payment -> payment.creditDate }

                for (Date creditDate : paymentListGroupedByCreditDateMap.keySet()) {
                    List<Payment> paymentListGroupByDate = paymentListGroupedByCreditDateMap[creditDate]

                    List<Payment> directPixPaymentList = paymentListGroupByDate.findAll { Payment payment -> PixTransaction.query([column: "payment", payment: payment, receivedWithAsaasQrCode: true]).get() }
                    if (directPixPaymentList) financialStatementService.saveCustomerRevenue(directPixPaymentList, bank, creditDate.clearTime())

                    paymentListGroupByDate.removeAll(directPixPaymentList)
                    CustomDateUtils.setDateForNextBusinessDayIfHoliday(creditDate)
                    if (paymentListGroupByDate) financialStatementService.saveCustomerRevenue(paymentListGroupByDate, bank, creditDate.clearTime())
                }
                break
            case BillingType.DEBIT_CARD:
            case BillingType.MUNDIPAGG_CIELO:
                Map<Date, List<Payment>> paymentListGroupedByCreditDateMap = paymentList.groupBy { Payment payment -> payment.creditDate }

                for (Date creditDate : paymentListGroupedByCreditDateMap.keySet()) {
                    List<Payment> paymentListGroupByDate = paymentListGroupedByCreditDateMap[creditDate]

                    financialStatementService.saveCustomerRevenue(paymentListGroupByDate, bank, creditDate.clearTime())
                }
                break
            default:
                financialStatementService.saveCustomerRevenue(paymentList, bank, new Date().clearTime())
        }
    }
}
