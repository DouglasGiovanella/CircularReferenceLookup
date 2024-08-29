package com.asaas.service.customerdebtappropriation

import com.asaas.credit.CreditType
import com.asaas.customerdebtappropriation.CustomerDebtAppropriationStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.domain.customerdebtappropriation.CustomerLossProvision
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.subscription.SubscriptionStatus
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDebtAppropriationService {

    def customerStatusService
    def creditService
    def financialTransactionService
    def subscriptionService

    public void create() {
        final Integer maxItemsPerCycle = 500

        final BigDecimal limitValueForDebtAppropriationAboveOneYear = 100
        final Integer minimumDaysForDebtAppropriationUpToOneHundred = 365
        List<Long> lossProvisionListUpToOneHundred = listLossProvisionId(["negativeValueWithoutPartnerSettlement[le]": limitValueForDebtAppropriationAboveOneYear, "negativeValueWithoutPartnerSettlement[gt]": 0, "daysWithNegativeBalance[ge]": minimumDaysForDebtAppropriationUpToOneHundred], maxItemsPerCycle)

        final Integer minimumDaysForDebtAppropriationAboveOneHundred = 548
        List<Long> lossProvisionListAboveOneHundred = listLossProvisionId(["negativeValueWithoutPartnerSettlement[gt]": limitValueForDebtAppropriationAboveOneYear, "daysWithNegativeBalance[ge]": minimumDaysForDebtAppropriationAboveOneHundred], maxItemsPerCycle)

        List<Long> lossProvisionIdList = lossProvisionListUpToOneHundred + lossProvisionListAboveOneHundred

        final Integer numberOfThreads = 4
        Utils.processWithThreads(lossProvisionIdList, numberOfThreads, { List<Long> idList ->
            Utils.forEachWithFlushSession(idList, 100, { Long lossProvisionId ->
                Utils.withNewTransactionAndRollbackOnError({
                    CustomerLossProvision customerLossProvision = CustomerLossProvision.get(lossProvisionId)

                    CustomerDebtAppropriation debtAppropriation = save(customerLossProvision, customerLossProvision.negativeValueWithoutPartnerSettlement, false)

                    if (shouldBlockCustomer(debtAppropriation.customer)) {
                        customerStatusService.block(debtAppropriation.customer.id, false, "Cliente com débito apropriado")
                    }

                    String creditDescription = "Crédito referente a apropriação de débito ID: ${debtAppropriation.id}"
                    creditService.save(debtAppropriation.customer.id, CreditType.CUSTOMER_DEBT_APPROPRIATION, creditDescription, debtAppropriation.appropriatedValue, null)
                }, [logErrorMessage: "CustomerDebtAppropriationService.create >> Erro ao criar apropriação de débito para a provisão: [${lossProvisionId}]"])
            })
        })
    }

    public void createWhenBalanceChange() {
        Map search = [:]
        final Date today = new Date().clearTime()
        search."dateCreated[ge]" = today
        search."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(today)
        search.column = "id"
        search."activeCustomerDebtAppropriationWithLowerAppropriatedValue[exists]" = true
        search."todayCustomerDebtAppropriation[notExists]" = true

        List<Long> lossProvisionIdList = CustomerLossProvision.query(search).list()

        Utils.forEachWithFlushSession(lossProvisionIdList, 100, { Long lossProvisionId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerLossProvision customerLossProvision = CustomerLossProvision.get(lossProvisionId)
                BigDecimal totalAppropriatedValue = CustomerDebtAppropriation.sumAppropriatedValue([customer: customerLossProvision.customer, "status[in]": CustomerDebtAppropriationStatus.listActiveAppropriationStatus()]).get()

                if (customerLossProvision.negativeValueWithoutPartnerSettlement > totalAppropriatedValue) {
                    CustomerDebtAppropriation debtAppropriation = save(customerLossProvision, customerLossProvision.negativeValueWithoutPartnerSettlement - totalAppropriatedValue, true)

                    String creditDescription = "Crédito referente a apropriação de débito ID: ${debtAppropriation.id}"
                    creditService.save(debtAppropriation.customer.id, CreditType.CUSTOMER_DEBT_APPROPRIATION, creditDescription, debtAppropriation.appropriatedValue, null)
                }
            }, [logErrorMessage: "CustomerDebtAppropriationService.createWhenBalanceChange >> Erro ao criar apropriação de débito para a provisão: [${lossProvisionId}]"])
        })
    }

    public void appropriateManually(Customer customer) {
        BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
        if (currentBalanceWithoutPartnerSettlement >= 0) throw new BusinessException("O cliente informado não está com o saldo negativo")

        CustomerLossProvision customerLossProvision = CustomerLossProvision.query([customer: customer, sort: "id", order: "desc"]).get()
        if (!customerLossProvision) throw new BusinessException("O cliente informado não possui provisão de perdas")

        CustomerDebtAppropriation debtAppropriation = save(customerLossProvision, currentBalanceWithoutPartnerSettlement.abs(), false)

        if (shouldBlockCustomer(customer)) {
            customerStatusService.block(customer.id, false, "Cliente com débito apropriado manualmente")
        }

        String creditDescription = "Crédito referente a apropriação de débito ID: ${debtAppropriation.id}"
        creditService.save(customer.id, CreditType.CUSTOMER_DEBT_APPROPRIATION, creditDescription, debtAppropriation.appropriatedValue, null)
    }

    public void deleteSubscriptionForAppropriatedCustomer(Customer customer) {
        if (!customer.getIsBlocked()) throw new BusinessException("O cliente informado não está bloqueado")

        Boolean hasActiveDebtAppropriation = CustomerDebtAppropriation.active([customer: customer, exists: true]).get().asBoolean()
        if (!hasActiveDebtAppropriation) throw new BusinessException("O cliente informado não possui débito apropriado")

        List<Long> subscriptionIdList = Subscription.query([column: "id", customerId: customer.id, status: SubscriptionStatus.ACTIVE]).list()

        for (Long subscriptionId : subscriptionIdList) {
            Boolean deletePendingOrOverduePayments = true
            subscriptionService.delete(subscriptionId, customer.id, deletePendingOrOverduePayments)
        }
    }

    private List<Long> listLossProvisionId(Map params, Integer max) {
        Map search = [:]
        final Date today = new Date().clearTime()
        search."dateCreated[ge]" = today
        search."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(today)
        search.column = "id"
        search."activeCustomerDebtAppropriation[notExists]" = CustomerDebtAppropriationStatus.listActiveAppropriationStatus()
        search.isLastCustomerLossProvision = true
        search.disableSort = true

        return CustomerLossProvision.query(search + params).list(max: max)
    }

    private Boolean shouldBlockCustomer(Customer customer) {
        if (UserUtils.hasAsaasEmail(customer.email)) return false

        if (customer.accountDisabled()) return false

        return true
    }

    private CustomerDebtAppropriation save(CustomerLossProvision customerLossProvision, BigDecimal appropriatedValue, Boolean customerBalanceChanged) {
        CustomerDebtAppropriation debtAppropriation = new CustomerDebtAppropriation()
        debtAppropriation.customer = customerLossProvision.customer
        debtAppropriation.appropriatedValue = appropriatedValue
        debtAppropriation.remainingAppropriatedValue = appropriatedValue
        debtAppropriation.customerLossProvision = customerLossProvision
        debtAppropriation.customerBalanceChanged = customerBalanceChanged

        return debtAppropriation.save(failOnError: true)
    }
}
