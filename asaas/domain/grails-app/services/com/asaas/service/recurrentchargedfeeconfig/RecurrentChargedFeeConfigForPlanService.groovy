package com.asaas.service.recurrentchargedfeeconfig

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.customerplan.adapters.NotifyCanceledCustomerPlanAdapter
import com.asaas.customerplan.enums.CustomerPlanCancelReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerplan.CustomerPlan
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.product.Cycle
import com.asaas.recurrentchargedfeeconfig.enums.RecurrentChargedFeeConfigStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class RecurrentChargedFeeConfigForPlanService {

    private static final Integer FLUSH_EVERY = 50
    private static final Integer MAX_ITEMS_PER_CYCLE = 200

    def customerPlanDowngradeService
    def promotionalCodeService
    def recurrentChargedFeeConfigService

    public Boolean hasEnoughBalance(Long customerId, BigDecimal value) {
        Map balanceInformation = getAccountBalanceAndPromotionalBalanceInformation(customerId)

        return balanceInformation.totalBalanceWithPromotional >= value
    }

    public Map getAccountBalanceAndPromotionalBalanceInformation(Long customerId) {
        BigDecimal promotionalBalance = BigDecimalUtils.abs(promotionalCodeService.getPromotionalBalance(customerId))
        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customerId)
        BigDecimal totalBalanceWithPromotional = promotionalBalance + currentBalance

        return [
            promotionalBalance: promotionalBalance,
            currentBalance: currentBalance,
            totalBalanceWithPromotional: totalBalanceWithPromotional
        ]
    }

    public List<Long> downgradeCustomerPlanForOverdueRecurrentChargedFeeConfigRelatedPlans() {
        List<Long> customerIdList = listCustomersWithOverdueRecurrentChargedFeeConfigRelatedPlansToDowngrade()
        if (!customerIdList) return customerIdList

        Utils.forEachWithFlushSession(customerIdList, FLUSH_EVERY, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)
                NotifyCanceledCustomerPlanAdapter notifyCanceledCustomerPlanAdapter = new NotifyCanceledCustomerPlanAdapter(customer, CustomerPlanCancelReason.OVERDUE_PAYMENT)
                customerPlanDowngradeService.cancelSubscriptionAndScheduleCustomerPlanDowngrade(customer, notifyCanceledCustomerPlanAdapter)
            }, [logErrorMessage: "RecurrentChargedFeeConfigForPlanService.downgradeCustomerPlanForOverdueRecurrentChargedFeeConfigRelatedPlans >> Erro ao agendar downgrade do plano. [customerId: ${customerId}]"])
        })

        return customerIdList
    }

    public List<Long> chargePlansWithRecurrentChargedFeeConfig() {
        List<Long> recurrentChargeFeeConfigIdList = listRecurrentChargedFeeConfigRelatedPlanToCharge()
        if (!recurrentChargeFeeConfigIdList) return recurrentChargeFeeConfigIdList

        Utils.forEachWithFlushSession(recurrentChargeFeeConfigIdList, FLUSH_EVERY, { Long recurrentChargedFeeId ->
            Utils.withNewTransactionAndRollbackOnError({
                RecurrentChargedFeeConfig recurrentChargedFeeConfig = RecurrentChargedFeeConfig.get(recurrentChargedFeeId)
                recurrentChargedFeeConfigService.chargeFee(recurrentChargedFeeConfig, Cycle.MONTHLY, ChargedFeeType.CONTRACTED_CUSTOMER_PLAN)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (exception instanceof BusinessException) {
                        AsaasLogger.warn("RecurrentChargedFeeConfigForPlanService.chargePlansWithRecurrentChargedFeeConfig >> Erro de negócio ao processar pagamento", exception)
                        return
                    }

                    AsaasLogger.error("RecurrentChargedFeeConfigForPlanService.chargePlansWithRecurrentChargedFeeConfig >> Erro ao processar pagamento. [recurrentChargedFeeId: ${recurrentChargedFeeId}]", exception)
                    throw exception
                }
            ])
        })

        return recurrentChargeFeeConfigIdList
    }

    private List<Long> listCustomersWithOverdueRecurrentChargedFeeConfigRelatedPlansToDowngrade() {
        Map queryParameters = [:]
        queryParameters.distinct = "customer.id"
        queryParameters."nextDueDate[le]" = CustomDateUtils.sumDays(new Date(), CustomerPlan.DAYS_LIMIT_TO_DOWNGRADE_PLAN_FOR_OVERDUE_PAYMENT * -1)
        queryParameters.type = ChargedFeeType.CONTRACTED_CUSTOMER_PLAN
        queryParameters.status = RecurrentChargedFeeConfigStatus.ACTIVE
        queryParameters.cycle = Cycle.MONTHLY
        queryParameters.disableSort = true
        return RecurrentChargedFeeConfig.query(queryParameters).list(max: MAX_ITEMS_PER_CYCLE)
    }

    private List<Long> listRecurrentChargedFeeConfigRelatedPlanToCharge() {
        Map queryParameters = [:]
        queryParameters.distinct = "id"
        queryParameters."nextDueDate[le]" = new Date()
        queryParameters.type = ChargedFeeType.CONTRACTED_CUSTOMER_PLAN
        queryParameters.status = RecurrentChargedFeeConfigStatus.ACTIVE
        queryParameters.cycle = Cycle.MONTHLY
        queryParameters.disableSort = true
        return RecurrentChargedFeeConfig.query(queryParameters).list(max: MAX_ITEMS_PER_CYCLE)
    }
}
