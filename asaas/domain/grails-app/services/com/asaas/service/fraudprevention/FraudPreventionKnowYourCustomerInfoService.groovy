package com.asaas.service.fraudprevention

import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.sauron.adapter.fraudpreventionknowyourcustomerinfo.FraudPreventionKnowYourCustomerInfoAdapter
import com.asaas.service.integration.sauron.fraudpreventionknowyourcustomerinfo.FraudPreventionKnowYourCustomerInfoManagerService
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FraudPreventionKnowYourCustomerInfoService {

    FraudPreventionKnowYourCustomerInfoManagerService fraudPreventionKnowYourCustomerInfoManagerService

    public FraudPreventionKnowYourCustomerInfoAdapter find(String cpfCnpj) {
        if (Utils.isEmptyOrNull(cpfCnpj)) throw new ResourceNotFoundException('Cliente não possui CPF/CNPJ')
        FraudPreventionKnowYourCustomerInfoAdapter knowYourCustomerInfoAdapter = fraudPreventionKnowYourCustomerInfoManagerService.find(cpfCnpj)

        return knowYourCustomerInfoAdapter
    }
}
