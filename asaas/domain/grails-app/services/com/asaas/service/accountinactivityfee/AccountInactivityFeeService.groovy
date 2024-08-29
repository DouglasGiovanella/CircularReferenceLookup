package com.asaas.service.accountinactivityfee

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig
import com.asaas.log.AsaasLogger
import com.asaas.product.Cycle
import com.asaas.recurrentchargedfeeconfig.enums.RecurrentChargedFeeConfigStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AccountInactivityFeeService {

    private static final BigDecimal INACTIVITY_FEE_VALUE = 19.99
    private static final BigDecimal INITIAL_BALANCE = 0
    private static final BigDecimal LIMIT_BALANCE = 3000
    private static final Integer MONTHS_WITHOUT_ACTIVITY = 6

    def grailsApplication
    def recurrentChargedFeeConfigService

    public Boolean create(Long customerId) {
        final Integer flushEvery = 100

        List<Map> inactiveAccountMapInfoList = listInactiveAccountMapInfo(customerId)
        if (!inactiveAccountMapInfoList) return false

        Utils.forEachWithFlushSession(inactiveAccountMapInfoList, flushEvery, { Map consolidateBalanceMap ->
            Utils.withNewTransactionAndRollbackOnError({
                Long consolidatedCustomerId = consolidateBalanceMap."customer.id"
                Date consolidationDate = consolidateBalanceMap.consolidationDate

                Boolean existsFinancialTransactionAfterConsolidateBalance = FinancialTransaction.query([customerId: consolidatedCustomerId, "transactionDate[gt]": consolidationDate, exists: true]).get().asBoolean()
                if (existsFinancialTransactionAfterConsolidateBalance) {
                    AsaasLogger.warn("AccountInactivityFeeService.create >> Existe transação financeira após a data de consolidação. [customerId: ${consolidatedCustomerId}, consolidationDate: ${consolidationDate}]")
                    return
                }

                recurrentChargedFeeConfigService.save(Customer.load(consolidatedCustomerId), Cycle.MONTHLY, AccountInactivityFeeService.INACTIVITY_FEE_VALUE, ChargedFeeType.ACCOUNT_INACTIVITY, new Date().clearTime())
            }, [logErrorMessage: "AccountInactivityFeeService.create >> Erro ao criar taxa de conta inativa. [customerId: ${consolidateBalanceMap."customer.id"}]"])
        })

        return true
    }

    public Boolean chargeAccountInactivityWithChargeFeeConfig() {
        final Integer flushEvery = 100

        List<Long> recurrentChargedFeeConfigIdList = listRecurrentChargedFeeConfigRelatedToAccountInactivity()
        if (!recurrentChargedFeeConfigIdList) return false

        Utils.forEachWithFlushSession(recurrentChargedFeeConfigIdList, flushEvery, { Long recurrentChargedFeeId ->
            Utils.withNewTransactionAndRollbackOnError({
                RecurrentChargedFeeConfig recurrentChargedFeeConfig = RecurrentChargedFeeConfig.get(recurrentChargedFeeId)
                recurrentChargedFeeConfigService.chargeFee(recurrentChargedFeeConfig, Cycle.MONTHLY, ChargedFeeType.ACCOUNT_INACTIVITY)
            }, [logErrorMessage: "AccountInactivityFeeService.chargeAccountInactivityWithChargeFeeConfig >> Erro ao processar pagamento. [recurrentChargedFeeId: ${recurrentChargedFeeId}]"])
        })

        return true
    }

    private List<Map> listInactiveAccountMapInfo(Long customerId) {
        final Integer maxItemsPerCycle = 5000
        List<Long> customerWithoutInactivityFeeChargeIdList = CustomerParameter.query([column: "customer.id", name: CustomerParameterName.BYPASS_INACTIVITY_FEE_CHARGE]).list()

        Map search = [:]
        search.columnList = ["customer.id", "consolidationDate"]
        search.disableSort = true
        search."customerCpfCnpj[ne]" = grailsApplication.config.asaas.cnpj.substring(1)
        search."consolidatedBalance[gt]" = AccountInactivityFeeService.INITIAL_BALANCE
        search."consolidatedBalance[lt]" = AccountInactivityFeeService.LIMIT_BALANCE
        search."activeRecurrentChargedFeeConfig[notExists]" = true
        search."financialTransactionAfterLastConsolidation[notExists]" = true
        search.isLastConsolidation = true
        search."consolidationDate[lt]" = CustomDateUtils.addMonths(new Date().clearTime(), AccountInactivityFeeService.MONTHS_WITHOUT_ACTIVITY * -1)
        search."customerStatus[in]" = [CustomerStatus.ACTIVE, CustomerStatus.AWAITING_ACTIVATION]
        search."customerEmail[%notLike%]" = "@asaas.com.br"
        if (customerWithoutInactivityFeeChargeIdList) search."customerAndAccountOwnerId[notIn]" = customerWithoutInactivityFeeChargeIdList
        if (customerId) search.customerId = customerId
        search.limit = maxItemsPerCycle

        return CustomerDailyBalanceConsolidation.query(search).list()
    }

    private List<Long> listRecurrentChargedFeeConfigRelatedToAccountInactivity() {
        final Integer maxItemsPerCycle = 2500

        Map search = [:]
        search.distinct = "id"
        search."nextDueDate[le]" = new Date()
        search.type = ChargedFeeType.ACCOUNT_INACTIVITY
        search.status = RecurrentChargedFeeConfigStatus.ACTIVE
        search.cycle = Cycle.MONTHLY
        search.disableSort = true
        search.limit = maxItemsPerCycle

        return RecurrentChargedFeeConfig.query(search).list()
    }
}
