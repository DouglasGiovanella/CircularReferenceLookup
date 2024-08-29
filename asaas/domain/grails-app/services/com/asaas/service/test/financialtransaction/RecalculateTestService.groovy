package com.asaas.service.test.financialtransaction

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.credit.CreditType
import com.asaas.customerplan.adapters.NotifyCanceledCustomerPlanAdapter
import com.asaas.customerplan.enums.CustomerPlanCancelReason
import com.asaas.customerplan.enums.CustomerPlanPaymentSource
import com.asaas.domain.credit.Credit
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.plan.Plan
import com.asaas.log.AsaasLogger
import com.asaas.plan.CustomerPlanName
import com.asaas.utils.BigDecimalUtils
import grails.transaction.Transactional

@Transactional
class RecalculateTestService {

    def asaasCardRecalculateTestService
    def creditService
    def customerPlanDowngradeService
    def customerPlanUpgradeService
    def customerPlanService
    def financialTransactionService
    def receivableAnticipationRecalculateTestService
    def recalculateCustomerCommissionTestService
    def recurrentChargedFeeConfigForPlanService
    def refundRecalculateTestService

    public Boolean runTests() {
        try {
            transactionStatus.setRollbackOnly()

            Customer customer = Customer.get(AsaasApplicationHolder.getConfig().asaas.test.recalculateBalance.customer.id)
            if (!customer) throw new RuntimeException("Não foi encontrado o customer informado para os testes de recálculo")

            BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customer)
            if (currentBalance < 0.00) creditService.save(customer.id, CreditType.MANUAL_INTERNAL_TRANSFER, "Crédito de cobertura de conta negativa", BigDecimalUtils.abs(currentBalance), new Date())

            recalculateBalanceTest(customer)
            return true
        } catch (Exception exception) {
            String warningMessage = """
                ******************************
                           ATENCAO
                ******************************
                *
                Chame o time responsavel pela feature que quebrou e ajuste seu ambiente local.
            """.stripIndent()

            AsaasLogger.error("RecalculateTestService.runTests >> Erro no recalculo.\n \n${warningMessage}\n \n", exception)
            return false
        }
    }

    public void recalculateBalanceTest(Customer customer) {
        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customer)

        String currentTest
        try {
            currentTest = "Cartão Asaas"
            currentBalance += asaasCardRecalculateTestService.asaasCardDebitsAndCredits(customer)
            validateBalance(customer , currentBalance)

            currentTest = "Lançamento de crédito manual"
            currentBalance += creditManualInternalTransferScenario(customer)
            validateBalance(customer , currentBalance)

            currentTest = "Planos"
            currentBalance += creditContractedAndCancelledCustomerPlanScenario(customer)
            validateBalance(customer , currentBalance)

            currentTest = "Crédito de comissionamentos"
            currentBalance += recalculateCustomerCommissionTestService.creditCustomerCommissionScenario(customer)
            validateBalance(customer , currentBalance)

            currentTest = "Débito de comissionamentos"
            currentBalance += recalculateCustomerCommissionTestService.debitCustomerCommissionScenario(customer)
            validateBalance(customer , currentBalance)

            currentTest = "Antecipação"
            currentBalance += receivableAnticipationRecalculateTestService.executeScenarios(customer)
            validateBalance(customer , currentBalance)

            currentTest = "Estorno"
            currentBalance += refundRecalculateTestService.executeScenarios(customer)
            validateBalance(customer , currentBalance)
        } catch (Exception exception) {
            throw new RuntimeException("Falha no teste de ${currentTest}", exception)
        }
    }

    private BigDecimal creditManualInternalTransferScenario(Customer customer) {
        BigDecimal creditValue = 10
        Credit credit = creditService.save(customer.id, CreditType.MANUAL_INTERNAL_TRANSFER, "teste de saldo", creditValue, new Date())
        return credit.value
    }

    private BigDecimal creditContractedAndCancelledCustomerPlanScenario(Customer customer) {
        customerPlanService.createIfNotExists(customer)
        BigDecimal value = 0
        CustomerPlanPaymentSource paymentSource = CustomerPlanPaymentSource.ACCOUNT_BALANCE
        CustomerPlanName planNameUpgrade = customer.isLegalPerson() ? CustomerPlanName.ADVANCED : CustomerPlanName.STANDARD
        Plan plan = Plan.query([name: planNameUpgrade.toString()]).get()
        if (!recurrentChargedFeeConfigForPlanService.hasEnoughBalance(customer.id, plan.value)) {
            Credit credit = creditService.save(customer.id, CreditType.MANUAL_INTERNAL_TRANSFER, "teste de saldo", plan.value, new Date())
            value = credit.value
        }

        customerPlanUpgradeService.upgrade(customer, plan, null, paymentSource)
        customerPlanDowngradeService.cancelSubscriptionAndScheduleCustomerPlanDowngrade(customer, new NotifyCanceledCustomerPlanAdapter(customer, CustomerPlanCancelReason.USER_ACTION))
        return value
    }

    private void validateBalance(Customer customer, BigDecimal currentBalance) {
        if (currentBalance != FinancialTransaction.getCustomerBalance(customer)) throw new RuntimeException("Saldo após movimentação está diferente do atual")
        financialTransactionService.recalculateBalance(customer)
    }
}
