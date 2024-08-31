package com.asaas.service.api

import com.asaas.api.ApiCustomerFiscalInfoParser
import com.asaas.api.ApiCustomerInvoiceParser
import com.asaas.api.ApiMobileUtils
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.invoice.Invoice
import com.asaas.exception.BusinessException
import com.asaas.integration.invoice.api.manager.MunicipalRequestManager
import com.asaas.integration.invoice.api.vo.MunicipalServiceVO
import com.asaas.invoice.CustomerInvoiceSummary
import com.asaas.invoice.validator.CustomerFiscalInfoValidator
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class ApiCustomerInvoiceService extends ApiBaseService {

    def apiResponseBuilderService
    def customerInvoiceService
    def customerMunicipalFiscalOptionsService
    def customerProductService
    def installmentService
    def paymentService

    def show(params) {
        Invoice invoice = Invoice.find(params.id, getProvider(params))

        return ApiCustomerInvoiceParser.buildResponseItem(invoice, [:])
    }

    def list(params) {
        Map filterParams = ApiCustomerInvoiceParser.parseListingFilters(params) + [asaasInvoice: false, customerId: getProvider(params)]

        List<Invoice> invoiceList = Invoice.query(filterParams).list(max: getLimit(params), offset: getOffset(params))

        List<Map> responseItems = invoiceList.collect { invoice -> ApiCustomerInvoiceParser.buildResponseItem(invoice, [:]) }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), invoiceList.totalCount)
    }

    def save(params) {
        withValidation { Customer customer ->
            Map fields = ApiCustomerInvoiceParser.parseRequestParams(getProvider(params), params)

            Invoice invoice

            if (fields.payment) {
                invoice = paymentService.saveInvoiceFromExistingPayment(fields.payment.id, customer, fields.invoiceFiscalVO, fields.customerProductVO, fields)
            } else if (fields.installment) {
                if (!ApiMobileUtils.isMobileAppRequest()) {
                    fields.invoiceFiscalVO.fiscalConfigVo.invoiceOnceForAllPayments = true
                }
                fields.installmentId = fields.installment.id
                fields.installment = null

                invoice = installmentService.saveInvoicesForExistingInstallment(fields.installmentId, customer, fields.invoiceFiscalVO, fields.customerProductVO, fields).first()
            } else if (fields.customerAccountId) {
                CustomerProduct customerProduct = customerProductService.findOrSave(customer, fields.customerProductVO)
                invoice = customerInvoiceService.saveDetachedInvoice(customer, fields.invoiceFiscalVO, fields + [customerProductId: customerProduct.id])
            } else {
                throw new BusinessException("Deve ser informado se a nota fiscal será gerada para uma Cobrança, Parcelamento ou Pagador.")
            }

            if (invoice.hasErrors()) throw new BusinessException(Utils.getMessageProperty(invoice.errors.allErrors.first()))
            invoice.refresh()

            return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceParser.buildResponseItem(invoice, [:]))
        }
    }

    def update(params) {
        Invoice invoice = Invoice.find(params.id, getProvider(params))

        Map fields = ApiCustomerInvoiceParser.parseRequestParams(getProvider(params), params)

        invoice = customerInvoiceService.update(invoice.id, getProviderInstance(params), fields)

        if (invoice.hasErrors()) return apiResponseBuilderService.buildErrorList(invoice)

        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceParser.buildResponseItem(invoice, [:]))
    }

    def synchronize(params) {
        Invoice invoice = Invoice.find(params.id, getProvider(params))

        Map synchronizedResultMap = customerInvoiceService.synchronize(invoice.id)

        if (!synchronizedResultMap.success) {
            return apiResponseBuilderService.buildErrorFrom("failed", synchronizedResultMap.messageList.join("; "))
        }

        invoice.refresh()

        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceParser.buildResponseItem(invoice, [:]))
    }

    def cancel(params) {
        Customer customer = getProviderInstance(params)
        Invoice invoice = Invoice.find(params.id, customer.id)
        Boolean supportsCancellation = customerInvoiceService.supportsCancellation(invoice)

        if (supportsCancellation && Boolean.valueOf(params.cancelOnlyOnAsaas)) {
            return apiResponseBuilderService.buildErrorFrom("failed", "${customer.city.toString()} suporta essa funcionalidade. Para efetivar o cancelamento, não envie o atributo 'cancelOnlyOnAsaas'")
        } else if (!ApiMobileUtils.isMobileAppRequest() && !supportsCancellation && !Boolean.valueOf(params.cancelOnlyOnAsaas)) {
            return apiResponseBuilderService.buildErrorFrom("failed", "${customer.city.toString()} ainda não tem essa funcionalidade disponível.")
        }

        invoice = customerInvoiceService.cancel(invoice)

        if (invoice.hasErrors()) return apiResponseBuilderService.buildErrorList(invoice)

        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceParser.buildResponseItem(invoice, [:]))
    }

    def municipalServices(params) {
        Customer customer = getProviderInstance(params)

        if (!customer.city) {
            return apiResponseBuilderService.buildErrorFrom("invalid_city", "Cadastre a sua cidade para obter a lista de serviços do seu município.")
        }

        List<MunicipalServiceVO> municipalServices = []
        if (customerMunicipalFiscalOptionsService.isMunicipalServiceCodeEnabled(customer)) {
            MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(customer)
            municipalServices = municipalRequestManager.getServiceList(params.description)

            if (municipalRequestManager.requestFailed()) {
                return apiResponseBuilderService.buildErrorFrom("error", "Falha ao buscar a lista de serviços municipais. Tente novamente")
            }
        } else {
            return apiResponseBuilderService.buildErrorFrom("error", "O código de serviços municipais não está habilitado para esta conta.")
        }

        List<Map> responseItems = municipalServices.collect { ApiCustomerFiscalInfoParser.buildMunicipalServiceResponseItem(it) }.unique()

        return apiResponseBuilderService.buildList(responseItems, responseItems.size(), 0, responseItems.size())
    }

    def getUpdateSummary(params) {
        Invoice invoice = Invoice.find(params.id, getProvider(params))

        Map fields = ApiCustomerInvoiceParser.parseSummaryRequestParams(getProvider(params), params)

        CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(invoice, fields)

        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceParser.buildInvoiceSummaryResponseItem(customerInvoiceSummary))
    }

    def getSaveSummary(params) {
        Map fields = ApiCustomerInvoiceParser.parseSummaryRequestParams(getProvider(params), params)

        CustomerInvoiceSummary customerInvoiceSummary

        if (fields.domainObject) {
            customerInvoiceSummary = new CustomerInvoiceSummary(fields.domainObject, fields.invoiceFiscalVO, fields.customerProductVO)
        } else {
            customerInvoiceSummary = new CustomerInvoiceSummary(fields.invoiceFiscalVO, fields.customerProductVO)
        }

        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceParser.buildInvoiceSummaryResponseItem(customerInvoiceSummary))
    }

    private withValidation(Closure action) {
        Customer customer = getProviderInstance(params)

        CustomerFiscalInfoValidator customerFiscalInfoValidator = new CustomerFiscalInfoValidator(customer.id)
        if (!customerFiscalInfoValidator.validate() || !customerFiscalInfoValidator.validateIfAnyCredentialsHasBeenSent()) {
            List<AsaasError> asaasErrorList = customerFiscalInfoValidator.getMessage()

            if (asaasErrorList.any({ it.code == "customer.fiscal.info.validation.error.customerFiscalInfo.notExists" })) {
                return apiResponseBuilderService.buildErrorFrom("invalid_fiscal_info", "Você precisa informar suas informações fiscais antes de emitir notas fiscais de serviço")
            }

            return apiResponseBuilderService.buildErrorList("invalid_fiscal_info", asaasErrorList)
        }

        return action(customer)
    }
}
