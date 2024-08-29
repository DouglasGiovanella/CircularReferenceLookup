package com.asaas.service.customerdebtappropriation

import com.asaas.customerdebtappropriation.CustomerDebtAppropriationStatus
import com.asaas.debit.DebitType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDebtDisappropriationService {

    def customerStatusService
    def debitService
    def financialTransactionService

    public void disappropriateAll() {
        Map search = [:]
        search.distinct = "customer.id"
        search.status = CustomerDebtAppropriationStatus.APPROPRIATED
        search.hasPositiveFinancialTransactionYesterday = true
        search."todayDebtDisappropriationDebit[notExists]" = true
        search.disableSort = true

        List<Long> customerIdList = CustomerDebtAppropriation.query(search).list()

        Utils.forEachWithFlushSession(customerIdList, 100, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)

                BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
                if (currentBalanceWithoutPartnerSettlement <= 0) return

                List<CustomerDebtAppropriation> customerDebtAppropriationList = CustomerDebtAppropriation.query([customer: customer, status: CustomerDebtAppropriationStatus.APPROPRIATED, sort: "id", order: "asc"]).list()
                BigDecimal totalRemainingAppropriationValue = customerDebtAppropriationList.remainingAppropriatedValue.sum()

                Boolean isPartialDebtDisappropriation = currentBalanceWithoutPartnerSettlement < totalRemainingAppropriationValue
                BigDecimal debitValue = isPartialDebtDisappropriation ? currentBalanceWithoutPartnerSettlement : totalRemainingAppropriationValue

                String debitDescription = "Débito referente a desapropriação das seguintes apropriações de débito: ${customerDebtAppropriationList.id.join(", ")}"
                debitService.save(customer, debitValue, DebitType.CUSTOMER_DEBT_DISAPPROPRIATION, debitDescription, null)

                saveDisappropriation(customerId, customerDebtAppropriationList, debitValue, isPartialDebtDisappropriation)
            }, [logErrorMessage: "CustomerDebtDisappropriationService.disappropriateAll >> Erro ao criar desapropriação de débito para o cliente ID: [${customerId}]"])
        })
    }

    public void disappropriateSpecificCustomer(Customer customer) {
        List<CustomerDebtAppropriation> customerDebtAppropriationList = CustomerDebtAppropriation.query([customer: customer, status: CustomerDebtAppropriationStatus.APPROPRIATED, sort: "id", order: "asc"]).list()
        if (!customerDebtAppropriationList) throw new BusinessException("Cliente [${customer.id}] não possui débito apropriado.")

        BigDecimal totalRemainingAppropriationValue = customerDebtAppropriationList.remainingAppropriatedValue.sum()
        BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
        if (currentBalanceWithoutPartnerSettlement < totalRemainingAppropriationValue) throw new BusinessException("Cliente [${customer.id}] não possui saldo suficiente para a desapropriação de débito.")

        String debitDescription = "Débito referente a desapropriação das seguintes apropriações de débito: ${customerDebtAppropriationList.id.join(", ")}"
        debitService.save(customer, totalRemainingAppropriationValue, DebitType.CUSTOMER_DEBT_DISAPPROPRIATION, debitDescription, null)

        saveDisappropriation(customer.id, customerDebtAppropriationList, totalRemainingAppropriationValue, false)
    }

    private void saveDisappropriation(Long customerId, List<CustomerDebtAppropriation> customerDebtAppropriationList, BigDecimal debitValue, Boolean isPartialDebtDisappropriation) {
        if (isPartialDebtDisappropriation) {
            savePartialDisappropriation(customerDebtAppropriationList, debitValue)
            return
        }

        for (CustomerDebtAppropriation customerDebtAppropriation : customerDebtAppropriationList) {
            saveTotalDisappropriation(customerDebtAppropriation)
        }

        unblockCustomerIfNecessary(customerId)
    }

    private void savePartialDisappropriation(List<CustomerDebtAppropriation> customerDebtAppropriationList, BigDecimal debitValue) {
        for (CustomerDebtAppropriation customerDebtAppropriation : customerDebtAppropriationList) {
            if (customerDebtAppropriation.remainingAppropriatedValue > debitValue) {
                customerDebtAppropriation.remainingAppropriatedValue -= debitValue
                customerDebtAppropriation.save(failOnError: true)
                break
            } else {
                debitValue -= customerDebtAppropriation.remainingAppropriatedValue

                saveTotalDisappropriation(customerDebtAppropriation)
            }
        }
    }

    private void unblockCustomerIfNecessary(Long customerId) {
        Customer customer = Customer.get(customerId)
        if (customer.accountDisabled()) return

        customerStatusService.unblock(customerId)
    }

    private void saveTotalDisappropriation(CustomerDebtAppropriation customerDebtAppropriation) {
        customerDebtAppropriation.remainingAppropriatedValue = new BigDecimal(0)
        customerDebtAppropriation.status = CustomerDebtAppropriationStatus.DISAPPROPRIATED
        customerDebtAppropriation.save(failOnError: true)
    }
}
