package com.asaas.service.financialstatement

import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.bank.Bank
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.payment.Payment
import com.asaas.financialstatement.FinancialStatementType
import grails.transaction.Transactional

@Transactional
class FinancialStatementPlanService {

    def financialStatementService

    public void create(Bank bank, List<Long> planPaymentIdList) {
        List<Payment> paymentList = Payment.query(["id[in]": planPaymentIdList, "financialStatementTypeList[notExists]": [FinancialStatementType.PLAN_REVENUE, FinancialStatementType.PIX_PLAN_REVENUE]]).list()
        if (!paymentList) return

        if (paymentList.first().billingType.isBoleto()) {
            financialStatementService.createForReceivedBankSlipPayments(paymentList, bank, false)
        } else {
            Date creditDate = paymentList.first().creditDate
            financialStatementService.savePlanRevenue(paymentList, bank, creditDate)

            List<Long> paymentIdList = paymentList.collect { it.id }
            createForCreditCardByAcquirer(paymentIdList, creditDate, CreditCardAcquirer.ADYEN, [FinancialStatementType.ADYEN_CREDIT_CARD_PLAN_CREDIT, FinancialStatementType.ADYEN_CREDIT_CARD_PLAN_DEBIT])
            createForCreditCardByAcquirer(paymentIdList, creditDate, CreditCardAcquirer.REDE, [FinancialStatementType.REDE_CREDIT_CARD_PLAN_CREDIT, FinancialStatementType.REDE_CREDIT_CARD_PLAN_DEBIT])
        }
    }

    private void createForCreditCardByAcquirer(List<Long> creditCardPlanPaymentIdList, Date statementDate, CreditCardAcquirer acquirer, List<FinancialStatementType> financialStatementTypeList) {
        List<Payment> paymentList = listPaymentByAcquirer(creditCardPlanPaymentIdList, acquirer, [financialStatementTypeList.first()])
        if (!paymentList) return

        BigDecimal totalValue = paymentList.value.sum()
        List<Map> financialStatementTypeInfoList = []

        for (FinancialStatementType statementType : financialStatementTypeList) {
            financialStatementTypeInfoList.add([
                financialStatementType: statementType, totalValue: totalValue
            ])
        }

        List<Long> paymentIdList = paymentList.collect { it.id }
        financialStatementService.saveForPaymentIdList(paymentIdList, financialStatementTypeInfoList, statementDate, null)
    }

    private List<Payment> listPaymentByAcquirer(List<Long> paymentIdList, CreditCardAcquirer acquirer, List<FinancialStatementType> financialStatementTypeList) {
        return CreditCardTransactionInfo.query([
            column: "payment",
            acquirer: acquirer,
            "paymentId[in]": paymentIdList,
            "financialStatementTypeList[notExists]": financialStatementTypeList
        ]).list()
    }
}
