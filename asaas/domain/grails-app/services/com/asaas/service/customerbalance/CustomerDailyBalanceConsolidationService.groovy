package com.asaas.service.customerbalance

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDailyBalanceConsolidationService {

    def financialTransactionService

    public List<Long> consolidate() {
        Map search = [:]

        Date yesterday = CustomDateUtils.getYesterday()
        search."transactionDate" = yesterday
        search.distinct = "provider.id"
        search."customerDailyBalanceConsolidation[notExists]" = true

        final Integer maxItemsPerCycle = 4000
        List<Long> customerIdList = FinancialTransaction.query(search).list(max: maxItemsPerCycle)

        final Integer numberOfThreads = 4
        Utils.processWithThreads(customerIdList, numberOfThreads, { List<Long> idList ->
            Utils.forEachWithFlushSession(idList, 100, { Long customerId ->
                Utils.withNewTransactionAndRollbackOnError({
                    Customer customer = Customer.read(customerId)
                    save(customer, yesterday)
                }, [logErrorMessage: "CustomerDailyBalanceConsolidationService.consolidate >> Erro ao processar transações do cliente: [${customerId}]"])
            })
        })

        return customerIdList
    }

    public void consolidateSpecificDateIfNotExists(Long customerId, Date transactionDate) {
        if (transactionDate.clearTime() >= new Date().clearTime()) throw new BusinessException("Só é permitido criar consolidações de datas retroativas.")

        Map search = [:]
        search."transactionDate" = transactionDate.clone().clearTime()
        search."exists" = true

        Boolean hasTransaction = FinancialTransaction.query(search).get().asBoolean()
        if (!hasTransaction) throw new BusinessException("Não existem transações para esse cliente na data informada.")

        Boolean hasBalanceConsolidationOnDate = CustomerDailyBalanceConsolidation.query([exists: true, customerId: customerId, consolidationDate: transactionDate.clearTime()]).get().asBoolean()
        if (hasBalanceConsolidationOnDate) throw new BusinessException("Já existe uma consolidação de saldo para a data e cliente informados.")

        Customer customer = Customer.read(customerId)
        save(customer, transactionDate)

        financialTransactionService.recalculateBalance(customer)
    }

    private void save(Customer customer, Date consolidationDate) {
        Boolean hasBalanceConsolidationOnDate = CustomerDailyBalanceConsolidation.query([exists: true, customer: customer, consolidationDate: consolidationDate.clearTime()]).get().asBoolean()
        if (hasBalanceConsolidationOnDate) {
            AsaasLogger.error("CustomerDailyBalanceConsolidationService.save >> O cliente ID: ${customer.id} já possui consolidação para o dia ${consolidationDate}")
            return
        }

        Boolean hasBalanceConsolidation = CustomerDailyBalanceConsolidation.query([exists: true, customer: customer]).get().asBoolean()
        Boolean hasNextBalanceConsolidation = CustomerDailyBalanceConsolidation.query([exists: true, customer: customer, isLastConsolidation: true, "consolidationDate[gt]": consolidationDate.clearTime()]).get().asBoolean()

        CustomerDailyBalanceConsolidation previousBalanceConsolidation = null
        if (hasBalanceConsolidation && !hasNextBalanceConsolidation) {
            previousBalanceConsolidation = CustomerDailyBalanceConsolidation.query([customer: customer, isLastConsolidation: true, "consolidationDate[lt]": consolidationDate.clearTime()]).get()
            previousBalanceConsolidation.isLastConsolidation = false
            previousBalanceConsolidation.save(failOnError: true)
        }

        CustomerDailyBalanceConsolidation balanceConsolidation = new CustomerDailyBalanceConsolidation()
        balanceConsolidation.customer = customer
        Map balanceConsolidationInfoMap = buildBalanceConsolidationInfoMap(customer.id, consolidationDate, previousBalanceConsolidation, hasBalanceConsolidation, hasNextBalanceConsolidation)
        balanceConsolidation.consolidatedBalance = balanceConsolidationInfoMap.sumValue
        balanceConsolidation.firstFinancialTransactionId = balanceConsolidationInfoMap.firstFinancialTransactionId
        balanceConsolidation.lastFinancialTransactionId = balanceConsolidationInfoMap.lastFinancialTransactionId
        balanceConsolidation.consolidationDate = consolidationDate.clearTime()
        balanceConsolidation.isLastConsolidation = isLastConsolidation(hasBalanceConsolidation, hasNextBalanceConsolidation)
        balanceConsolidation.save(failOnError: true)
    }

    private Boolean isLastConsolidation(Boolean hasBalanceConsolidation, Boolean hasNextBalanceConsolidation) {
        if (!hasBalanceConsolidation || !hasNextBalanceConsolidation) return true

        return false
    }

    private Map buildBalanceConsolidationInfoMap(Long customerId, Date consolidationDate, CustomerDailyBalanceConsolidation previousBalanceConsolidation, Boolean hasBalanceConsolidation, Boolean hasNextBalanceConsolidation) {
        if (!hasBalanceConsolidation || hasNextBalanceConsolidation) return FinancialTransaction.sumValueWithMinAndMaxId([customerId: customerId, "transactionDate[le]": CustomDateUtils.setTimeToEndOfDay(consolidationDate)]).get()

        Map balanceSinceLastConsolidationInfoMap = FinancialTransaction.sumValueWithMinAndMaxId([customerId: customerId, "transactionDate[gt]": previousBalanceConsolidation.consolidationDate, "transactionDate[le]": consolidationDate]).get()

        balanceSinceLastConsolidationInfoMap.sumValue += previousBalanceConsolidation.consolidatedBalance

        return balanceSinceLastConsolidationInfoMap
    }
}
