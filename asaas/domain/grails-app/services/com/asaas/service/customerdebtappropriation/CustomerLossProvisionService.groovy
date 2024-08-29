package com.asaas.service.customerdebtappropriation

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.customerdebtappropriation.CustomerLossProvision
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerLossProvisionService {

    def grailsApplication

    public void create() {
        Map search = [:]
        search.column = "id"
        search.disableSort = true
        search."customerCpfCnpj[ne]" = grailsApplication.config.asaas.cnpj.substring(1)
        search."consolidatedBalance[lt]" = 0
        search.isLastConsolidation = true
        search."todayCustomerLossProvision[notExists]" = true
        final Integer maxItemsPerCycle = 1000
        search.limit = maxItemsPerCycle

        List<Long> balanceConsolidationIdList = CustomerDailyBalanceConsolidation.query(search).list()

        final Integer numberOfThreads = 4
        Utils.processWithThreads(balanceConsolidationIdList, numberOfThreads, { List<Long> idList ->
            Utils.forEachWithFlushSession(idList, 100, { Long balanceConsolidationId ->
                Utils.withNewTransactionAndRollbackOnError({
                    Map lastBalanceConsolidation = CustomerDailyBalanceConsolidation.query([id: balanceConsolidationId, columnList: ["customer", "consolidatedBalance"]]).get()

                    Integer daysWithNegativeBalance = calculateDaysWithNegativeBalance(lastBalanceConsolidation.customer)

                    BigDecimal partnerSettlementValue = ReceivableAnticipationPartnerSettlement.getUnpaidValueToPartnerByCustomer(lastBalanceConsolidation.customer, ReceivableAnticipationPartner.VORTX)

                    BigDecimal negativeValueWithoutPartnerSettlement = lastBalanceConsolidation.consolidatedBalance.abs() - partnerSettlementValue
                    if (negativeValueWithoutPartnerSettlement < 0) negativeValueWithoutPartnerSettlement = new BigDecimal(0)

                    BigDecimal provisionedValue = calculateProvisionedValue(negativeValueWithoutPartnerSettlement, daysWithNegativeBalance)

                    save(lastBalanceConsolidation.customer, negativeValueWithoutPartnerSettlement, provisionedValue, daysWithNegativeBalance, partnerSettlementValue)
                }, [logErrorMessage: "CustomerLossProvisionService.create >> Erro ao criar provisão de perdas para a consolidação: [${balanceConsolidationId}]"])
            })
        })
    }

    private Integer calculateDaysWithNegativeBalance(Customer customer) {
        Map search = [:]
        search.columnList = ["consolidatedBalance", "consolidationDate"]
        search.customer = customer
        search.sort = "consolidationDate"
        search.order = "desc"

        List<Map> balanceConsolidationMapList = CustomerDailyBalanceConsolidation.query(search).list()

        Date negativeDate
        for (Map balanceConsolidationMap : balanceConsolidationMapList) {
            if (balanceConsolidationMap.consolidatedBalance >= 0) break

            negativeDate = balanceConsolidationMap.consolidationDate
        }

        return CustomDateUtils.calculateDifferenceInDays(negativeDate, CustomDateUtils.getYesterday())
    }

    private BigDecimal calculateProvisionedValue(BigDecimal negativeValue, Integer daysWithNegativeBalance) {
        switch (daysWithNegativeBalance) {
            case 0..4:
                return 0
            case 5..14:
                return negativeValue * 0.005
            case 15..30:
                return negativeValue * 0.01
            case 31..60:
                return negativeValue * 0.03
            case 61..90:
                return negativeValue * 0.1
            case 91..120:
                return negativeValue * 0.3
            case 121..150:
                return negativeValue * 0.5
            case 151..180:
                return negativeValue * 0.7
            case { it > 180 }:
                return negativeValue
        }
    }

    private void save(Customer customer, BigDecimal negativeValueWithoutPartnerSettlement, BigDecimal provisionedValue, Integer daysWithNegativeBalance, BigDecimal partnerSettlementNegativeValue) {
        CustomerLossProvision lossProvision = new CustomerLossProvision()
        lossProvision.customer = customer
        lossProvision.negativeValueWithoutPartnerSettlement = negativeValueWithoutPartnerSettlement
        lossProvision.provisionedValue = provisionedValue
        lossProvision.daysWithNegativeBalance = daysWithNegativeBalance
        lossProvision.partnerSettlementNegativeValue = partnerSettlementNegativeValue
        lossProvision.save(failOnError: true)
    }
}
