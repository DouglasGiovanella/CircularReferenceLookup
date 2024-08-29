package com.asaas.service.salespartner

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.domain.salespartner.SalesPartnerCustomer
import grails.transaction.Transactional

@Transactional
class SalesPartnerCustomerService {

    def customerParameterService

    public SalesPartnerCustomer save(Customer customer, String salesPartnerName) {
        SalesPartner salesPartner = SalesPartner.query([name: salesPartnerName]).get()
        if (!salesPartner) return

        SalesPartnerCustomer salesPartnerCustomer = new SalesPartnerCustomer()
        salesPartnerCustomer.customer = customer
        salesPartnerCustomer.salesPartner = salesPartner
        salesPartnerCustomer.save(failOnError: true)

        if (!salesPartner.discountBankSlipFee) {
            customerParameterService.save(customer, CustomerParameterName.CANNOT_USE_REFERRAL, true)
        }

        return salesPartnerCustomer
    }
}
