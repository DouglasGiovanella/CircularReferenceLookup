package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiCustomerFiscalInfoParser
import com.asaas.api.ApiMobileUtils
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.invoice.api.manager.MunicipalRequestManager
import com.asaas.integration.invoice.api.vo.MunicipalFiscalOptionsVO
import com.asaas.integration.invoice.api.vo.MunicipalServiceVO
import com.asaas.invoice.validator.CustomerFiscalInfoValidator
import com.asaas.invoice.validator.CustomerInvoiceValidator
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiCustomerFiscalInfoService extends ApiBaseService {

    def apiResponseBuilderService
    def customerFiscalInfoService
    def customerMunicipalFiscalOptionsService

    def find(params) {
        Customer customer = getProviderInstance(params)
        CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.query([customerId: customer.id]).get()

        if (!customerFiscalInfo && ApiMobileUtils.isMobileAppRequest()) {
            customerFiscalInfo = new CustomerFiscalInfo()
            customerFiscalInfo.customer = customer
        }

        if (!customerFiscalInfo) {
            throw new ResourceNotFoundException("Configurações para emissão de nota fiscal inexistente.")
        }

        Map responseMap = ApiCustomerFiscalInfoParser.buildResponseItem(customerFiscalInfo)

        if (ApiMobileUtils.isMobileAppRequest()) {
            if (!customerFiscalInfoService.validateCustomerFiscalUse(customer)) {
                MunicipalFiscalOptionsVO municipalFiscalOptionsVO = customerMunicipalFiscalOptionsService.getOptions(customer)
                responseMap.municipalOptions = ApiCustomerFiscalInfoParser.buildMunicipalFiscalOptions(municipalFiscalOptionsVO)
            } else {
                responseMap.municipalOptions = null
            }
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    def save(params) {
        withValidation(params) { Customer customer, Map fields ->
            CustomerFiscalInfo customerFiscalInfo = customerFiscalInfoService.save(customer, fields)
            if (customerFiscalInfo.hasErrors()) return apiResponseBuilderService.buildErrorList(customerFiscalInfo)

            CustomerInvoiceValidator customerInvoiceValidator = new CustomerInvoiceValidator()
            if (!customerInvoiceValidator.validate(customerFiscalInfo.customer, null)) return apiResponseBuilderService.buildErrorFrom("failed", customerInvoiceValidator.getMessageAsString())

            CustomerFiscalInfoValidator customerFiscalInfoValidator = new CustomerFiscalInfoValidator(customerFiscalInfo, null)
            if (!customerFiscalInfoValidator.validate()) return apiResponseBuilderService.buildErrorFrom("failed", customerFiscalInfoValidator.getMessageAsString())

            Map responseMap = customerFiscalInfoService.synchronize(customerFiscalInfo, ApiCustomerFiscalInfoParser.parseCustomerFiscalInfoCredentialsParams(params), true)
            if (!responseMap.success) return apiResponseBuilderService.buildErrorFrom("failed", responseMap.error)

            return ApiCustomerFiscalInfoParser.buildResponseItem(customerFiscalInfo)
        }
    }

    public Map listMunicipalServices(Map params) {
        Customer customer = getProviderInstance(params)

        if (!customer.city) {
            return apiResponseBuilderService.buildErrorFrom("invalid_city", "Informe a sua cidade para obter a lista de serviços do seu município.")
        }

        Integer totalCount = 0
        List<MunicipalServiceVO> municipalServices = []
        if (customerMunicipalFiscalOptionsService.isMunicipalServiceCodeEnabled(customer)) {
            MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(customer.city)
            municipalServices = municipalRequestManager.getServiceList(null, params.description, getOffset(params), getLimit(params))
            totalCount = municipalRequestManager.getTotalCount()

            if (municipalRequestManager.requestFailed()) {
                return apiResponseBuilderService.buildErrorFrom("error", "Falha ao buscar a lista de serviços municipais. Tente novamente")
            }
        } else {
            return apiResponseBuilderService.buildErrorFrom("error", "O código de serviços municipais não está habilitado para esta conta.")
        }

        List<Map> responseItems = municipalServices.collect { ApiCustomerFiscalInfoParser.buildMunicipalServiceResponseItem(it) }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), totalCount)
    }

    def municipalOptions(params) {
        withValidation(params) { Customer customer, Map fields ->
            MunicipalFiscalOptionsVO municipalFiscalOptionsVO = customerMunicipalFiscalOptionsService.getOptions(customer)

            if (!municipalFiscalOptionsVO) {
                return apiResponseBuilderService.buildErrorFrom("unknown.error", "Não foi possível estabelecer conexão com a prefeitura de ${getProviderInstance(params).city.toString()}. Se o problema persistir, por favor entre em contato com a nossa equipe de suporte.")
            }

            return ApiCustomerFiscalInfoParser.buildMunicipalFiscalOptions(municipalFiscalOptionsVO)
        }
    }

    def validateFiscalUse(params) {
        Customer customer = getProviderInstance(params)

        Map response = [:]
        response.fiscalUseDeniedReason = customerFiscalInfoService.validateCustomerFiscalUse(customer)

        if (!response.fiscalUseDeniedReason) {
            CustomerFiscalInfoValidator customerFiscalInfoValidator = new CustomerFiscalInfoValidator(customer.id)
            if (!customerFiscalInfoValidator.validate() || !customerFiscalInfoValidator.validateIfAnyCredentialsHasBeenSent()) {
                response.fiscalInfoInconsistencies = ApiBaseParser.buildAsaasError(customerFiscalInfoValidator.getMessage())
            } else {
                response.fiscalInfoInconsistencies = null
            }

            CustomerInvoiceValidator customerInvoiceValidator = new CustomerInvoiceValidator()
            if (!customerInvoiceValidator.validate(customer, null)) {
                response.customerInvoiceInconsistencies = ApiBaseParser.buildAsaasError(customerInvoiceValidator.getMessage())
            } else {
                response.customerInvoiceInconsistencies = null
            }
        }

        return apiResponseBuilderService.buildSuccess(response)
    }

    public Map getStatus(Map params) {
        Long providerId = getProvider(params)

        Boolean wasSent
        Boolean isInvoiceEnabled = CustomerFeature.isInvoiceEnabled(providerId)

        if (isInvoiceEnabled) {
            CustomerFiscalInfoValidator customerFiscalInfoValidator = new CustomerFiscalInfoValidator(providerId)
            wasSent = customerFiscalInfoValidator.validate() && customerFiscalInfoValidator.validateIfAnyCredentialsHasBeenSent()
        }

        return apiResponseBuilderService.buildSuccess([status: wasSent ? "SENT" : "NOT_SENT"])
    }

    public Map updateUseNationalPortal(Map params) {
        Map validationError = validateUpdateUseNationalPortal(params)
        if (validationError) {
            return apiResponseBuilderService.buildErrorFrom(validationError.code, validationError.message)
        }

        Customer customer = getProviderInstance(params)
        Boolean useNationalPortal = Utils.toBoolean(params.enabled)
        Boolean customerUseNationalPortal = CustomerFiscalInfo.query([
            customerId: customer.id,
            column: "useNationalPortal"
        ]).get().asBoolean()

        if (customerUseNationalPortal != useNationalPortal) customerFiscalInfoService.toggleUseNationalPortal(customer)

        Map response = [success: true, useNationalPortal: useNationalPortal]

        return apiResponseBuilderService.buildSuccess(response)
    }

    private withValidation(Map params, Closure action) {
        Customer customer = getProviderInstance(params)
        Map fields = ApiCustomerFiscalInfoParser.parseRequestParams(params)

        Map disableReasons = customerFiscalInfoService.validateCustomerFiscalUse(customer)
        if (disableReasons) {
            return apiResponseBuilderService.buildErrorFrom(disableReasons.code, disableReasons.message)
        }

        return action(customer, fields)
    }

    private Map validateUpdateUseNationalPortal(Map params) {
        Customer customer = getProviderInstance(params)

        if (!customer.isLegalPerson()) {
            return [code: "invalid_person_type", message: Utils.getMessageProperty("customerFiscalInfo.invalidPersonType")]
        }

        if (!customerFiscalInfoService.isApprovedToSetupFiscalInfo(customer)) {
            return [code: "not_fully_approved", message: Utils.getMessageProperty("customerFiscalInfo.notFullyApproved")]
        }

        if (!Utils.isValidBooleanValue(params.enabled)) {
            return [code: "enabled_is_mandatory", message: Utils.getMessageProperty("customerFiscalConfig.nationalPortal.missingArgument")]
        }
    }
}
