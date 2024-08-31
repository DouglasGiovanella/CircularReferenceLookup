package com.asaas.service.fraudtrackingaccount

import com.asaas.domain.customer.Customer
import com.asaas.integration.sauron.adapter.fraudtracking.FraudTrackingAccountProductListAdapter
import grails.transaction.Transactional

@Transactional
class FraudTrackingAccountProductService {

    def customerAsaasProductsService

    public FraudTrackingAccountProductListAdapter buildAccountProduct(Customer customer) {
        List<Map> asaasProductListMap = customerAsaasProductsService.getCustomerAsaasProducts(customer)
        return new FraudTrackingAccountProductListAdapter(asaasProductListMap)
    }
}
