package com.asaas.service.customerbalance

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerbalance.CustomerDailyPartialBalance
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerDailyPartialBalanceService {

    private static final Integer LAST_UPDATED_MINUTES_LIMIT = 5
    private static final Integer HOURS_LIMIT = 5

    def financialTransactionService
    def recalculateBalanceAsyncActionService

    public void createAllWithAsynchronousRecalculateBalance() {
        List<Long> customerWithBypassBalanceRecalculationIdList = CustomerParameter.query([column: "customer.id", name: CustomerParameterName.BYPASS_CUSTOMER_BALANCE_RECALCULATION]).list()
        if (!customerWithBypassBalanceRecalculationIdList) return

        final Integer maxItemsPerCycle = 100
        final Integer minItemsPerThread = 10

        Map financialTransactionSearch = [:]
        financialTransactionSearch."lastUpdated[lt]" = CustomDateUtils.sumMinutes(new Date(), CustomerDailyPartialBalanceService.LAST_UPDATED_MINUTES_LIMIT * -1)
        financialTransactionSearch."lastUpdated[ge]" = CustomDateUtils.sumHours(financialTransactionSearch."lastUpdated[lt]", CustomerDailyPartialBalanceService.HOURS_LIMIT * -1)
        financialTransactionSearch."lastAnalyzedFinancialTransaction[ge][notExists]" = true
        financialTransactionSearch."customerId[in]" = customerWithBypassBalanceRecalculationIdList
        financialTransactionSearch.limit = maxItemsPerCycle

        List<Map> lastIdByProviderMapList = FinancialTransaction.lastIdByProvider(financialTransactionSearch).list()
        create(lastIdByProviderMapList, true, minItemsPerThread)
    }

    public void createAllWithSynchronousRecalculateBalance() {
        List<Long> customerWithBypassBalanceRecalculationIdList = CustomerParameter.query([column: "customer.id", name: CustomerParameterName.BYPASS_CUSTOMER_BALANCE_RECALCULATION]).list()
        final Integer maxItemsPerCycle = 13000
        final Integer minItemsPerThread = 1000

        Map financialTransactionSearch = [:]
        financialTransactionSearch."lastUpdated[lt]" = CustomDateUtils.sumMinutes(new Date(), CustomerDailyPartialBalanceService.LAST_UPDATED_MINUTES_LIMIT * -1)
        financialTransactionSearch."lastUpdated[ge]" = CustomDateUtils.sumHours(financialTransactionSearch."lastUpdated[lt]", CustomerDailyPartialBalanceService.HOURS_LIMIT * -1)
        financialTransactionSearch."lastAnalyzedFinancialTransaction[ge][notExists]" = true
        if (customerWithBypassBalanceRecalculationIdList) financialTransactionSearch."customerId[notIn]" = customerWithBypassBalanceRecalculationIdList
        financialTransactionSearch.limit = maxItemsPerCycle

        List<Map> lastIdByProviderMapList = FinancialTransaction.lastIdByProvider(financialTransactionSearch).list()

        create(lastIdByProviderMapList, false, minItemsPerThread)
    }

    private void create(List<Map> lastIdByProviderMapList, Boolean recalculateBalanceAsynchronously, Integer minItemsPerThread) {
        Integer batchSize = 30
        Integer flushEvery = 30

        ThreadUtils.processWithThreadsOnDemand(lastIdByProviderMapList, minItemsPerThread, { List<Map> lastIdByProviderMapSubList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(lastIdByProviderMapSubList, batchSize, flushEvery, { Map lastIdByProviderMap ->
                Long lastFinancialTransactionId = lastIdByProviderMap.lastFinancialTransactionId
                Long customerId = lastIdByProviderMap.providerId

                CustomerDailyPartialBalance partialBalance = CustomerDailyPartialBalance.query([customerId: customerId]).get()
                BigDecimal balance = 0

                Map search = [:]
                search.customerId = customerId
                search."id[le]" = lastFinancialTransactionId

                if (partialBalance?.lastAnalyzedFinancialTransaction) {
                    search."id[gt]" = partialBalance.lastAnalyzedFinancialTransaction.id
                    balance += partialBalance.balance
                }

                balance += FinancialTransaction.sumValue(search).get()

                partialBalance = saveOrUpdate(partialBalance, customerId, balance, lastFinancialTransactionId)

                if (recalculateBalanceAsynchronously) {
                    recalculateBalanceAsyncActionService.saveIfNecessary(customerId)
                } else {
                    financialTransactionService.recalculateBalance(partialBalance.customer)
                }
            }, [logErrorMessage: "CustomerDailyPartialBalanceService.create >> Erro ao criar o saldo parcial diário do cliente",
                appendBatchToLogErrorMessage: true,
                logLockAsWarning: true
            ])
        })
    }

    public void recalculateSpecificCustomer(Customer customer) {
        Date lastUpdatedLimit = CustomDateUtils.sumMinutes(new Date(), CustomerDailyPartialBalanceService.LAST_UPDATED_MINUTES_LIMIT * -1)
        Long lastFinancialTransactionId = FinancialTransaction.query([column: "id", provider: customer, "lastUpdated[lt]": lastUpdatedLimit, sort: "id", order: "desc"]).get()

        if (!lastFinancialTransactionId) throw new BusinessException("O cliente [${customer.id}] não possui transação anterior a ${CustomerDailyPartialBalanceService.LAST_UPDATED_MINUTES_LIMIT} minutos atrás")

        CustomerDailyPartialBalance partialBalance = CustomerDailyPartialBalance.query([customer: customer]).get()

        BigDecimal balance = FinancialTransaction.sumValue([provider: customer, "id[le]": lastFinancialTransactionId]).get()

        saveOrUpdate(partialBalance, customer.id, balance, lastFinancialTransactionId)

        financialTransactionService.recalculateBalance(customer)
    }

    private CustomerDailyPartialBalance saveOrUpdate(CustomerDailyPartialBalance partialBalance, Long customerId, BigDecimal balance, Long lastFinancialTransactionId) {
        if (!partialBalance) {
            partialBalance = new CustomerDailyPartialBalance()
            partialBalance.customer = Customer.load(customerId)
        }

        partialBalance.balance = balance
        partialBalance.balanceDate = new Date()
        partialBalance.lastAnalyzedFinancialTransaction = FinancialTransaction.load(lastFinancialTransactionId)
        return partialBalance.save(failOnError: true)
    }
}
