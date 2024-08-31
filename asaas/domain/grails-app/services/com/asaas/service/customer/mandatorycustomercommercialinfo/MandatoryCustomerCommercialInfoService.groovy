package com.asaas.service.customer.mandatorycustomercommercialinfo

import com.asaas.customer.knowyourcustomerinfo.KnowYourCustomerInfoIncomeRange
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.transaction.Transactional

@Transactional
class MandatoryCustomerCommercialInfoService {

    def customerCommercialInfoExpirationService
    def customerInteractionService
    def knowYourCustomerInfoService

    public void update(Customer customer, Map params) {
        params.cpfCnpj = customer.cpfCnpj
        Map parsedParams = parseUpdateParams(params)

        List<AsaasError> asaasErrorList = validateUpdateParams(customer, parsedParams)
        if (asaasErrorList) {
            throw new BusinessException(asaasErrorList.first().getMessage())
        }

        if (params.incomeValue) {
            knowYourCustomerInfoService.save(customer, Utils.toBigDecimal(parsedParams.incomeValue))
        } else {
            knowYourCustomerInfoService.saveWithIncomeRange(customer, parsedParams.incomeRange)
        }

        customerCommercialInfoExpirationService.save(customer)

        customerInteractionService.save(customer, "Dados comerciais obrigatórios atualizados.")
    }

    private Map parseUpdateParams(Map params) {
        if (params.containsKey("incomeRange")) {
            params.incomeRange = KnowYourCustomerInfoIncomeRange.convert(params.incomeRange)
        }

        return params
    }

    private List<AsaasError> validateUpdateParams(Customer customer, Map params) {
        List<AsaasError> asaasErrorList = []

        if (params.incomeValue) {
            asaasErrorList = knowYourCustomerInfoService.validateIncomeValue(customer, params)
        } else {
            asaasErrorList = knowYourCustomerInfoService.validateIncomeRange(customer, params)
        }

        return asaasErrorList
    }
}
