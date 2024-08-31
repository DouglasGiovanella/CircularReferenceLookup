package com.asaas.service.api

import com.asaas.api.ApiAccountNumberParser
import com.asaas.api.ApiMyAccountParser
import com.asaas.api.ApiMyAccountFeesParser
import com.asaas.api.ApiProviderParser
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.DisabledReason
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.exception.BusinessException
import com.asaas.user.UserUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ApiMyAccountService extends ApiBaseService {

    def apiResponseBuilderService
    def customerAccountManagerService
    def customerPlanService
    def customerStatusService

    public Map find(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess(ApiProviderParser.buildResponseItem(customer))
    }

    public Map getStatus(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess(ApiMyAccountParser.buildAccountStatusResponseMap(customer))
    }

    public Map getDocumentationStatus(Map params) {
        DocumentAnalysis lastApprovedOrRejectedAnalysis = DocumentAnalysis.approvedOrRejected(getProviderInstance(params)).get()

        if (!lastApprovedOrRejectedAnalysis) {
            return apiResponseBuilderService.buildErrorFrom("invalid_action", "Seus documentos ainda não foram analisados.")
        }

        return apiResponseBuilderService.buildSuccess(ApiMyAccountParser.buildDocumentStatusResponseMap(lastApprovedOrRejectedAnalysis))
    }

    public Map getAccountFees(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess(ApiMyAccountFeesParser.buildResponseMap(customer))
    }

    public Map getAccountPlan(Map params) {
        Customer customer = getProviderInstance(params)
        Boolean isPhoneCallCustomerServiceEnabled = customerPlanService.isPhoneCallCustomerServiceEnabled(customer)

        return apiResponseBuilderService.buildSuccess(ApiMyAccountParser.buildCustomerPlan(customer, isPhoneCallCustomerServiceEnabled))
    }

    public Map getAccountNumber(Map params) {
        Customer customer = getProviderInstance(params)
        AccountNumber accountNumber = customer.getAccountNumber()

        if (!accountNumber) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiAccountNumberParser.buildResponseItem(accountNumber))
    }

    public Map getAccountManager(Map params) {
        Customer customer = getProviderInstance(params)
        return apiResponseBuilderService.buildSuccess([email: customer.accountManager.email])
    }

    public Map disableAccount(Map params) {
        Customer customer = getProviderInstance(params)

        if (!params.removeReason) throw new BusinessException("É obrigatório informar o motivo da remoção.")

        Map response = [:]

        if (CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) {
            BusinessValidation canRequestDisableAccount = customerStatusService.canRequestDisableAccount(customer, DisabledReason.API_INTEGRATION, params.removeReason)

            if (!canRequestDisableAccount.isValid()) return apiResponseBuilderService.buildErrorFrom("invalid_action", canRequestDisableAccount.getFirstErrorMessage())

            customerStatusService.executeAccountDisable(customer.id, UserUtils.getCurrentUser())
            customerStatusService.saveCustomerDisabledReason(customer, DisabledReason.API_INTEGRATION, params.removeReason, null)
            response.observations = 'Conta desabilitada com sucesso.'
        } else {
            return apiResponseBuilderService.buildErrorFrom("invalid_action", "Acesse a interface WEB da sua conta ou o APP para solicitar a exclusão.")
        }

        return apiResponseBuilderService.buildSuccess(response)
    }
}
