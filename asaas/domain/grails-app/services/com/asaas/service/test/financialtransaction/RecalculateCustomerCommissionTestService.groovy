package com.asaas.service.test.financialtransaction

import com.asaas.customercommission.CustomerCommissionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionItem
import com.asaas.service.customercommission.CustomerCommissionService
import com.asaas.utils.CustomDateUtils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class RecalculateCustomerCommissionTestService {

    CustomerCommissionService customerCommissionService

    public BigDecimal creditCustomerCommissionScenario(Customer customer) {
        final BigDecimal creditCommissionValue = 10.0

        return testCustomerCommission(customer, CustomerCommissionType.BANK_SLIP_FEE, creditCommissionValue)
    }

    public BigDecimal debitCustomerCommissionScenario(Customer customer) {
        final BigDecimal debitCommissionValue = -10.0

        return testCustomerCommission(customer, CustomerCommissionType.BANK_SLIP_FEE_REFUND, debitCommissionValue)
    }

    private BigDecimal testCustomerCommission(Customer customer, CustomerCommissionType type, BigDecimal value) {
        CustomerCommissionItem item = new CustomerCommissionItem()
        item.customer = customer
        item.type = type
        item.asaasFee = value
        item.value = value

        CustomerCommission customerCommission = customerCommissionService.saveWithItem(customer, customer, CustomDateUtils.getYesterday(), type, item)

        return customerCommissionService.settle(customer, type, [customerCommission]).value
    }
}
