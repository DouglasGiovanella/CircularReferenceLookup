package com.asaas.service.financialstatement.asyncaction

import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.financialstatement.FinancialStatementTransitoryCreditCardFeeAsyncAction
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class FinancialStatementTransitoryCreditCardFeeAsyncActionService {

    def baseAsyncActionService
    def financialStatementCreditCardTransitoryService

    public void save(CreditCardAcquirer acquirer, Date importDate) {
        Map asyncActionData = [
            acquirer  : acquirer,
            importDate: importDate.toString()
        ]

        baseAsyncActionService.save(new FinancialStatementTransitoryCreditCardFeeAsyncAction(), asyncActionData)
    }

    public void process() {
        Map asyncActionMap = baseAsyncActionService.getPending(FinancialStatementTransitoryCreditCardFeeAsyncAction, [:])
        if (!asyncActionMap) return

        baseAsyncActionService.processListWithNewTransaction(FinancialStatementTransitoryCreditCardFeeAsyncAction, [asyncActionMap], { Map asyncActionData ->
            CreditCardAcquirer acquirer = CreditCardAcquirer.parse(asyncActionData.acquirer)
            Date importDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.importDate)

            financialStatementCreditCardTransitoryService.createFinancialStatementsForCreditCardTransactionFee(acquirer, importDate)
        })
    }
}
