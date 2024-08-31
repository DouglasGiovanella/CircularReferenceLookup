package com.asaas.service.regulatory.cadoc

import com.asaas.customer.CustomerStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.regulatory.cadoc.refundrequest.SvrCustomerBalanceAdapter
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import groovy.transform.CompileDynamic

@Transactional
@GrailsCompileStatic
class SvrRefundRequestService {

    @CompileDynamic
    public List<SvrCustomerBalanceAdapter> listCustomersWithBalance(String destinationCpfCnpj) {
        List<Map> customerList = Customer.query([
            columnList: ['id', 'name'],
            disableSort: true,
            cpfCnpj: destinationCpfCnpj,
            status: CustomerStatus.DISABLED
        ]).list() as List<Map>

        List<SvrCustomerBalanceAdapter> customersWithBalanceList = []

        customerList.each { Map customer ->
            BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(customer.id as Long)
            if (customerBalance <= BigDecimal.ZERO) return

            customersWithBalanceList.add(new SvrCustomerBalanceAdapter(destinationCpfCnpj, customer, customerBalance))
        }

        return customersWithBalanceList
    }
}
