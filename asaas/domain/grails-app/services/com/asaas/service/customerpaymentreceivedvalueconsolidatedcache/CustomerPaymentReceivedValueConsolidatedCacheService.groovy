package com.asaas.service.customerpaymentreceivedvalueconsolidatedcache

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.payment.CustomerPaymentReceivedValueConsolidatedCache
import com.asaas.domain.payment.Payment
import com.asaas.payment.CustomerPaymentReceivedValueConsolidatedCacheRepository
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerPaymentReceivedValueConsolidatedCacheService {

    public Boolean consolidate() {
        Map search = [:]
        search.distinct = "customer.id"
        search.isLastConsolidation = true
        search."customerPaymentReceivedValueConsolidatedCacheUpdated[notExists]" = true
        search.disableSort = true

        final Integer maxItemsPerCycle = 4000
        List<Long> customerIdList = CustomerDailyBalanceConsolidation.query(search).list(max: maxItemsPerCycle)
        if (!customerIdList) return false

        final Integer numberOfThreads = 4
        final Integer batchSize = 100
        final Integer flushEvery = 100
        Utils.processWithThreads(customerIdList, numberOfThreads, { List<Long> customerIdSubList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdSubList, batchSize, flushEvery, { Long customerId ->
                Customer customer = Customer.read(customerId)

                Date yesterday = CustomDateUtils.getYesterday()
                save(customer, yesterday)
            }, [logErrorMessage: "CustomerPaymentReceivedValueConsolidatedCacheService.consolidate >> Erro ao gerar do valor recebido de cobranças para os clientes.",
                appendBatchToLogErrorMessage: true,
                logLockAsWarning: true])
        })

        return true
    }

    private BigDecimal calculatePaymentAlreadyReceivedValue(Customer customer, Date consolidationDate, Date lastConsolidationDate) {
        Map searchPaymentValues = [:]
        searchPaymentValues."customerId" = customer.id
        if (lastConsolidationDate) searchPaymentValues."creditDate[gt]" = lastConsolidationDate
        searchPaymentValues."creditDate[le]" = consolidationDate

        BigDecimal paymentReceivedValue = Payment.sumNetValue(searchPaymentValues + [status: PaymentStatus.RECEIVED]).get()
        BigDecimal paymentRefundedValue = Payment.sumNetValue(searchPaymentValues + ["paymentRefundValueDebited[exists]": true, statusList: [PaymentStatus.REFUNDED, PaymentStatus.REFUND_REQUESTED, PaymentStatus.REFUND_IN_PROGRESS]]).get()
        BigDecimal paymentChargebackValue = Payment.sumNetValue(searchPaymentValues + ["paymentDate[isNotNull]": true, billingType: BillingType.MUNDIPAGG_CIELO, statusList: [PaymentStatus.CHARGEBACK_REQUESTED, PaymentStatus.CHARGEBACK_DISPUTE, PaymentStatus.AWAITING_CHARGEBACK_REVERSAL]]).get()

        return paymentReceivedValue + paymentRefundedValue + paymentChargebackValue
    }

    private CustomerPaymentReceivedValueConsolidatedCache save(Customer customer, Date consolidationDate) {
        CustomerPaymentReceivedValueConsolidatedCache customerPaymentReceivedValueConsolidatedCache = CustomerPaymentReceivedValueConsolidatedCacheRepository.query(["customerId": customer.id]).get()
        if (!customerPaymentReceivedValueConsolidatedCache) {
            customerPaymentReceivedValueConsolidatedCache = new CustomerPaymentReceivedValueConsolidatedCache()
            customerPaymentReceivedValueConsolidatedCache.customer = customer
            customerPaymentReceivedValueConsolidatedCache.value = 0.00
        }

        customerPaymentReceivedValueConsolidatedCache.value += calculatePaymentAlreadyReceivedValue(customer, consolidationDate, customerPaymentReceivedValueConsolidatedCache.date)
        customerPaymentReceivedValueConsolidatedCache.date = consolidationDate
        customerPaymentReceivedValueConsolidatedCache.save(failOnError: true)

        return customerPaymentReceivedValueConsolidatedCache
    }
}

