package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.domain.creditcard.CreditCardTransactionAttempt
import com.asaas.domain.creditcard.CreditCardTransactionAttemptDailySummary
import com.asaas.domain.customer.Customer
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditCardTransactionAttemptDailySummaryService {

    public void processSaveCreditCardAttemptSummary() {
        final Integer intervalInMinutes = 5
        final Date transactionAttemptDate =  CustomDateUtils.sumMinutes(new Date(), intervalInMinutes * -1)

        Map search = [:]
        search.distinct = "customer.id"
        search.origin = CreditCardTransactionOriginInterface.INVOICE_PAYMENT_CAMPAIGN
        search."dateCreated[ge]" = transactionAttemptDate

        List<Long> customerIdList = CreditCardTransactionAttempt.query(search).list()

        for (Long customerId : customerIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)

                CreditCardTransactionAttemptDailySummary creditCardTransactionAttemptDailySummary = saveIfNecessary(customer, transactionAttemptDate, CreditCardTransactionOriginInterface.INVOICE_PAYMENT_CAMPAIGN)
                consolidate(creditCardTransactionAttemptDailySummary)
            }, [logErrorMessage: "CreditCardTransactionAttemptDailySummaryService.processSaveCreditCardAttemptSummary >> Erro ao executar para o cliente ${customerId}"])
        }
    }

    private void consolidate(CreditCardTransactionAttemptDailySummary creditCardTransactionAttemptDailySummary) {
        Map commonSearch = [:]
        commonSearch."customer" = creditCardTransactionAttemptDailySummary.customer
        commonSearch."origin" = CreditCardTransactionOriginInterface.INVOICE_PAYMENT_CAMPAIGN
        commonSearch."dateCreated[ge]" = creditCardTransactionAttemptDailySummary.transactionAttemptDate.clone().clearTime()
        commonSearch."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(creditCardTransactionAttemptDailySummary.transactionAttemptDate.clone())

        creditCardTransactionAttemptDailySummary.authorizedValue = CreditCardTransactionAttempt.sumValue(commonSearch + [authorized: true]).get()
        creditCardTransactionAttemptDailySummary.nonAuthorizedValue = CreditCardTransactionAttempt.sumValue(commonSearch + [authorized: false]).get()
        creditCardTransactionAttemptDailySummary.distinctCreditCardHashCount = CreditCardTransactionAttempt.query(commonSearch + [countDistinct: "creditCardHash"]).get()
        creditCardTransactionAttemptDailySummary.distinctRemoteIpCount =  CreditCardTransactionAttempt.query(commonSearch + [countDistinct: "remoteIp"]).get()
        creditCardTransactionAttemptDailySummary.save(failOnError: true)
    }

    private CreditCardTransactionAttemptDailySummary saveIfNecessary(Customer customer, Date transactionAttemptDate, CreditCardTransactionOriginInterface origin) {
        Map search = [:]
        search.customerId = customer.id
        search.transactionAttemptDate = transactionAttemptDate.clone().clearTime()
        search.origin = origin

        CreditCardTransactionAttemptDailySummary creditCardTransactionAttemptDailySummary = CreditCardTransactionAttemptDailySummary.query(search).get()
        if (creditCardTransactionAttemptDailySummary) return creditCardTransactionAttemptDailySummary

        creditCardTransactionAttemptDailySummary = new CreditCardTransactionAttemptDailySummary()
        creditCardTransactionAttemptDailySummary.customer = customer
        creditCardTransactionAttemptDailySummary.transactionAttemptDate = transactionAttemptDate
        creditCardTransactionAttemptDailySummary.origin = origin
        creditCardTransactionAttemptDailySummary.distinctCreditCardHashCount = 0
        creditCardTransactionAttemptDailySummary.distinctRemoteIpCount = 0
        creditCardTransactionAttemptDailySummary.authorizedValue = BigDecimal.ZERO
        creditCardTransactionAttemptDailySummary.nonAuthorizedValue = BigDecimal.ZERO
        creditCardTransactionAttemptDailySummary.save(failOnError: true)

        return creditCardTransactionAttemptDailySummary
    }
}
