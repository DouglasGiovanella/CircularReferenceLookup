package com.asaas.service.api.asaasmoney

import com.asaas.api.ApiCustomerRegisterStatusParser
import com.asaas.api.ApiInternalOnboardingParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiAsaasMoneyHomeService extends ApiBaseService {

    def accountActivationRequestService

    public Map index(Map params) {
        Customer customer = getProviderInstance(params)
        Boolean shouldRequestAccountActivation = accountActivationRequestService.getCurrentActivationStep(customer).asBoolean()

        Map responseMap = [:]

        responseMap.accountBalance = FinancialTransaction.getCustomerBalance(customer.id)
        responseMap.shouldRequestAccountActivation = accountActivationRequestService.getCurrentActivationStep(customer).asBoolean()
        responseMap.internalOnboardingStep = ApiInternalOnboardingParser.buildStep(customer, shouldRequestAccountActivation)?.toString()
        responseMap.registerStatus = ApiCustomerRegisterStatusParser.buildResponseItem(customer)
        responseMap.hasAnyInvoiceCreated = Payment.queryByPayerCpfCnpj(customer.cpfCnpj, [exists: true]).get().asBoolean()

        return responseMap
    }

}
