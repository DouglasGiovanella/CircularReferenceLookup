package com.asaas.service.customerpixfraudstatistics

import com.asaas.domain.customerpixfraudstatistics.CustomerPixFraudStatistics
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.accountstatistic.SynchronizeAccountStatisticAdapter
import com.asaas.pix.adapter.accountstatistic.children.SynchronizeAccountStatisticEventCounterAdapter
import grails.transaction.Transactional

@Transactional
class CustomerPixFraudStatisticsService {

    def customerPixFraudStatisticsCacheService

    public void saveOrUpdate(SynchronizeAccountStatisticAdapter accountStatisticAdapter) {
        if (!accountStatisticAdapter.customer) {
            AsaasLogger.warn("CustomerPixFraudStatisticsService.saveOrUpdate > Customer não informado ao salvar estatísticas de análise de risco")
            return
        }

        try {
            CustomerPixFraudStatistics customerStatistics = CustomerPixFraudStatistics.query([customer: accountStatisticAdapter.customer]).get()
            if (!customerStatistics) {
                customerStatistics = new CustomerPixFraudStatistics()
                customerStatistics.customer = accountStatisticAdapter.customer
            }

            customerStatistics.registeredAccounts = accountStatisticAdapter.registeredAccounts
            customerStatistics.infractionInProgress = accountStatisticAdapter.infractionInProgress
            customerStatistics.pspInfractionInProgress = accountStatisticAdapter.quantityDistinctPspInfractionInProgress

            buildTotalCustomerStatistics(customerStatistics, accountStatisticAdapter.eventCounterList)

            customerStatistics.save(failOnError: true)

            customerPixFraudStatisticsCacheService.evictTotalAccountFraudConfirmedInLastOneYear(accountStatisticAdapter.customer.id)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerPixFraudStatisticsService.saveOrUpdate > Erro ao salvar CustomerPixFraudStatisticsService CustomerId: [${accountStatisticAdapter.customer.id}]", exception)
            throw exception
        }
    }

    private void buildTotalCustomerStatistics(CustomerPixFraudStatistics customerStatistics, List<SynchronizeAccountStatisticEventCounterAdapter> accountStatisticEventCounterAdapterList) {
        SynchronizeAccountStatisticEventCounterAdapter spiEventCounterAdapter = accountStatisticEventCounterAdapterList.find { it.statisticGroup.isSpi() }
        customerStatistics.totalSpiTransactionsInLastThreeMonths = spiEventCounterAdapter.totalEventsInLastThreeMonths
        customerStatistics.totalSpiTransactionsInLastOneYear = spiEventCounterAdapter.totalEventsInLastOneYear
        customerStatistics.totalSpiTransactionsInLastFiveYears = spiEventCounterAdapter.totalEventsInLastFiveYears

        List<SynchronizeAccountStatisticEventCounterAdapter> accountConfirmedFraudEventCounterAdapterList = accountStatisticEventCounterAdapterList.findAll() { it.statisticGroup.isAccountFraudConfirmed() }
        customerStatistics.totalAccountFraudConfirmedInLastThreeMonths = accountConfirmedFraudEventCounterAdapterList.sum { it.totalEventsInLastThreeMonths }
        customerStatistics.totalAccountFraudConfirmedInLastOneYear = accountConfirmedFraudEventCounterAdapterList.sum { it.totalEventsInLastOneYear }
        customerStatistics.totalAccountFraudConfirmedInLastFiveYears = accountConfirmedFraudEventCounterAdapterList.sum { it.totalEventsInLastFiveYears }

        SynchronizeAccountStatisticEventCounterAdapter rejectedInfractionEventCounterAdapter = accountStatisticEventCounterAdapterList.find() { it.statisticGroup.isInfraction() && it.type.isRejectedInfraction() }
        customerStatistics.totalRejectedInfractionInLastThreeMonths = rejectedInfractionEventCounterAdapter.totalEventsInLastThreeMonths
        customerStatistics.totalRejectedInfractionInLastOneYear = rejectedInfractionEventCounterAdapter.totalEventsInLastOneYear
        customerStatistics.totalRejectedInfractionInLastFiveYears = rejectedInfractionEventCounterAdapter.totalEventsInLastFiveYears
    }
}
