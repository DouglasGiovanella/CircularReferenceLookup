package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerAccountGroup
import com.asaas.domain.customer.GroupCustomerAccount
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class CustomerAccountGroupService {

    public CustomerAccountGroup saveOrUpdate(Long id, Customer provider, Map params) {
		Boolean isUpdate = id?.asBoolean()
    	CustomerAccountGroup group = isUpdate ? CustomerAccountGroup.find(id, provider.id) : new CustomerAccountGroup()

		group.properties["description", "name"] = params
		group.provider = provider
		if (!isUpdate) group.createdBy = UserUtils.getCurrentUser() ?: params.createdBy

		group.save(flush: true)

		return group
    }

	public void delete(params) {
		CustomerAccountGroup group = CustomerAccountGroup.find(Long.valueOf(params.id), params.provider.id)
		group.delete(flush: true)
	}

	public GroupCustomerAccount associateCustomerAccount(Customer customer, Long id, String name, Long customerAccountId) {
		CustomerAccountGroup customerAccountGroup
		CustomerAccount customerAccount = CustomerAccount.find(customerAccountId, customer.id)

		if (id < 0) {
			customerAccountGroup = saveOrUpdate(null, customer, [name: name, createdBy: customerAccount.createdBy])
		} else {
			customerAccountGroup = CustomerAccountGroup.find(id, customer.id)
		}

		GroupCustomerAccount groupCustomerAccount = GroupCustomerAccount.findByGroupAndCustomerAccount(customerAccountGroup, customerAccount)

		if (!groupCustomerAccount) {
			groupCustomerAccount = new GroupCustomerAccount()
			groupCustomerAccount.group = customerAccountGroup
			groupCustomerAccount.customerAccount = customerAccount
		}

		groupCustomerAccount.save(failOnError: true)

		return groupCustomerAccount
	}

	public void unassociateCustomerAccount(Customer customer, Long id, Long customerAccountId) {
		CustomerAccountGroup customerAccountGroup = CustomerAccountGroup.find(id, customer.id)
		CustomerAccount customerAccount = CustomerAccount.find(customerAccountId, customer.id)

		GroupCustomerAccount groupCustomerAccount = GroupCustomerAccount.findWhere(group: customerAccountGroup, customerAccount: customerAccount)
		if (groupCustomerAccount) groupCustomerAccount.delete(failOnError: true)
	}
}
