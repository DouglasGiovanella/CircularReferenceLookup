package com.asaas.service.customertransferconfig

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerTransferConfig
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class CustomerTransferConfigService {

    def asyncActionService
    def customerInteractionService
    def customerTransferConfigParameterService

    public void replicateAccountOwnerConfigIfNecessary(Customer childAccount) {
        if (!childAccount.accountOwner) return

        Map accountOwnerCustomerTransferConfig = CustomerTransferConfig.query([columnList: ["monthlyQuantityPixWithoutFee", "mustConsiderTedInMonthlyQuantityPixWithoutFee"] , customer: childAccount.accountOwner]).get()
        if (!accountOwnerCustomerTransferConfig) return

        save(childAccount, accountOwnerCustomerTransferConfig.monthlyQuantityPixWithoutFee, accountOwnerCustomerTransferConfig.mustConsiderTedInMonthlyQuantityPixWithoutFee, true)

        AsaasLogger.info("CustomerTransferConfigService.replicateAccountOwnerConfigIfNecessary() -> Réplica de config feita com sucesso. [id conta pai: ${childAccount.accountOwner.id}, id conta filha: ${childAccount.id}]")
    }

    public CustomerTransferConfig save(Customer customer, Integer monthlyQuantityPixWithoutFee, Boolean mustConsiderTedInMonthlyQuantityPixWithoutFee, Boolean fromAccountOwner) {
        CustomerTransferConfig customerTransferConfig = build(customer)
        if (monthlyQuantityPixWithoutFee != null) customerTransferConfig.monthlyQuantityPixWithoutFee = monthlyQuantityPixWithoutFee
        if (mustConsiderTedInMonthlyQuantityPixWithoutFee != null) customerTransferConfig.mustConsiderTedInMonthlyQuantityPixWithoutFee = mustConsiderTedInMonthlyQuantityPixWithoutFee
        customerTransferConfig.save(failOnError: true, flush: true)

        customerInteractionService.saveCustomerTransferConfigInteraction(customer, monthlyQuantityPixWithoutFee, mustConsiderTedInMonthlyQuantityPixWithoutFee, fromAccountOwner)

        return customerTransferConfig
    }

    public CustomerTransferConfig save(Customer customer, Map customerTransferConfigMap) {
        CustomerTransferConfig customerTransferConfig = save(customer, customerTransferConfigMap.monthlyQuantityPixWithoutFee, customerTransferConfigMap.mustConsiderTedInMonthlyQuantityPixWithoutFee, customerTransferConfigMap.fromAccountOwner)
        return customerTransferConfig
    }

    public CustomerTransferConfig createOrUpdate(Long customerId, Integer monthlyQuantityPixWithoutFee, Boolean mustConsiderTedInMonthlyQuantityPixWithoutFee, Boolean applyForAllChildAccounts) {
        Customer customer = Customer.read(customerId)

        CustomerTransferConfig customerTransferConfig = save(customer, monthlyQuantityPixWithoutFee, mustConsiderTedInMonthlyQuantityPixWithoutFee, false)

        if (applyForAllChildAccounts) replicateAccountOwnerConfigForAllChildAccounts(customer, customerTransferConfig)

        return customerTransferConfig
    }

    private void replicateAccountOwnerConfigForAllChildAccounts(Customer accountOwner, CustomerTransferConfig customerTransferConfig) {
        Map customerTransferConfigMap = [
            monthlyQuantityPixWithoutFee: customerTransferConfig.monthlyQuantityPixWithoutFee,
            mustConsiderTedInMonthlyQuantityPixWithoutFee: customerTransferConfig.mustConsiderTedInMonthlyQuantityPixWithoutFee
        ]

        customerTransferConfigMap.each {
            customerTransferConfigParameterService.saveParameter(accountOwner.id, it.key, it.value)
            asyncActionService.save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_TRANSFER_CONFIG_REPLICATION , [accountOwnerId: accountOwner.id, name: it.key])
        }
    }

    private CustomerTransferConfig build(Customer customer) {
        CustomerTransferConfig customerTransferConfig = CustomerTransferConfig.query([customer: customer]).get()
        if (!customerTransferConfig) {
            customerTransferConfig = new CustomerTransferConfig()
            customerTransferConfig.customer = customer
        }

        return customerTransferConfig
    }
}
