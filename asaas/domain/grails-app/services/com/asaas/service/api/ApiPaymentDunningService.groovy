package com.asaas.service.api

import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiPaymentDunningParser
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.payment.PartialPayment
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentDunningHistory
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.user.UserUtils
import com.asaas.validation.BusinessValidation
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiPaymentDunningService extends ApiBaseService {

    def apiResponseBuilderService
    def creditBureauDunningService
    def debtRecoveryAssistanceDunningService
    def paymentDunningCustomerAccountInfoService
    def paymentDunningService
    def temporaryFileService

    def find(params) {
        PaymentDunning paymentDunning = PaymentDunning.find(params.id, getProviderInstance(params))
        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildResponseItem(paymentDunning))
    }

    def list(params) {
        Customer customer = getProviderInstance(params)
        Map filterParams = ApiPaymentDunningParser.parseFilters(params)

        List<PaymentDunning> paymentDunnings = PaymentDunning.query(filterParams + [customer: customer]).list(max: getLimit(params), offset: getOffset(params), readonly: true)

        List<Map> buildedDunnings = paymentDunnings.collect { ApiPaymentDunningParser.buildResponseItem(it) }

        List<Map> extraData = []
        if (ApiMobileUtils.isMobileAppRequest()) {
            extraData << [isAccountNotFullyApproved: customer.isNotFullyApproved()]
        }

        return apiResponseBuilderService.buildList(buildedDunnings, getLimit(params), getOffset(params), paymentDunnings.totalCount, extraData)
    }

    def save(params) {
        if (!canRequestDunning(params)){
            return apiResponseBuilderService.buildForbidden("Para solicitar negativações via API solicite a liberação junto ao seu gerente de contas.")
        }

        Customer customer = getProviderInstance(params)
        params.temporaryFileIdList = getTemporaryFileIdList(params)
        Map parsedFields = ApiPaymentDunningParser.parseRequestParams(params)

        if (!parsedFields.type?.isCreditBureau()) {
            throw new BusinessException("Tipo de negativação não suportado")
        }

        PaymentDunning paymentDunning = paymentDunningService.save(customer, UserUtils.getCurrentUser(), parsedFields.paymentId, parsedFields)
        if (paymentDunning.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentDunning)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildResponseItem(paymentDunning))
    }

    def simulate(params) {
        Map fields = ApiPaymentDunningParser.parseSimulateRequestParams(params)
        Payment payment = Payment.find(fields.paymentId, getProvider(params))

        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildSimulatedResponseItem(payment))
    }

    public Map getLatestCustomerAccountInfo(Map params) {
        CustomerAccount customerAccount = CustomerAccount.find(params.customer, getProvider(params))

        Map latestCustomerAccountInfo = paymentDunningCustomerAccountInfoService.getLatestCustomerAccountInfo(customerAccount)

        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildLatestCustomerAccountInfoResponseMap(latestCustomerAccountInfo))
    }

    public Map cancel(Map params) {
        PaymentDunning paymentDunning = PaymentDunning.find(params.id, getProviderInstance(params))

        if (paymentDunning.type.isCreditBureau()) {
            paymentDunning = creditBureauDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.REQUESTED_BY_PROVIDER)
        } else {
            paymentDunning = debtRecoveryAssistanceDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.REQUESTED_BY_PROVIDER, true)
        }

        if (paymentDunning.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentDunning)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildResponseItem(paymentDunning))
    }

    public Map activate(Map params) {
        PaymentDunning activatedDunning = paymentDunningService.activate(params.id, getProviderInstance(params))

        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildResponseItem(activatedDunning))
    }

    public Map saveDocuments(Map params) {
        Customer customer = getProviderInstance(params)

        PaymentDunning paymentDunning = PaymentDunning.find(params.id, customer)

        paymentDunning = paymentDunningService.resendToAnalysis(customer.id, paymentDunning.id, getTemporaryFileIdList(params))

        if (paymentDunning.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(paymentDunning)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentDunningParser.buildResponseItem(paymentDunning))
    }

    public Map listPaymentsAvailableForDunning(Map params) {
        Customer customer = getProviderInstance(params)

        Map filterParams = ApiPaymentDunningParser.parsePaymentAvailableListFilter(params)

        List<Payment> paymentAvailableForDunningList = paymentDunningService.listPaymentsAvailableForDunning(customer, filterParams, getLimit(params), getOffset(params))

        List<Map> buildedPaymentList = paymentAvailableForDunningList.collect { ApiPaymentDunningParser.buildPaymentAvailableForDunningResponseItem(it) }

        return apiResponseBuilderService.buildList(buildedPaymentList, getLimit(params), getOffset(params), paymentAvailableForDunningList.totalCount)
    }

    public Map listHistory(Map params) {
        PaymentDunning paymentDunning = PaymentDunning.find(params.id, getProviderInstance(params))

        List<PaymentDunningHistory> dunningHistoryList = PaymentDunningHistory.query([dunning: paymentDunning]).list(max: getLimit(params), offset: getOffset(params))

        List<Map> buildedDunningHistoryList = dunningHistoryList.collect { ApiPaymentDunningParser.buildResponseHistory(it) }

        return apiResponseBuilderService.buildList(buildedDunningHistoryList, getLimit(params), getOffset(params), dunningHistoryList.totalCount)
    }

    public Map listPartialPayments(Map params) {
        PaymentDunning paymentDunning = PaymentDunning.find(params.id, getProviderInstance(params))

        List<PartialPayment> partialPaymentsList = PartialPayment.query([dunning: paymentDunning]).list(max: getLimit(params), offset: getOffset(params))

        List<Map> buildedPartialPaymentsList = partialPaymentsList.collect { ApiPaymentDunningParser.buildResponsePartialPayment(it) }

        return apiResponseBuilderService.buildList(buildedPartialPaymentsList, getLimit(params), getOffset(params), partialPaymentsList.totalCount)
    }

    private List<Long> getTemporaryFileIdList(Map params) {
        List<Long> temporaryFileIdList = []

        if (params.containsKey("temporaryFileIdList")) {
            temporaryFileIdList = params.temporaryFileIdList.collect { TemporaryFile.findByPublicId(it).id }
        } else if (params.containsKey("documentsFromMultiFileMap")) {
            params.documentsFromMultiFileMap.each { file ->
                TemporaryFile temporaryFile = temporaryFileService.save(getProviderInstance(params), file, true)
                temporaryFileIdList.add(temporaryFile.id)
            }
        }

        return temporaryFileIdList
    }

    private Boolean canRequestDunning(Map params) {
        if (!AsaasEnvironment.isProduction()) return true
        if (ApiMobileUtils.isMobileAppRequest()) return true
        if (CustomerParameter.getValue(getProviderInstance(params), CustomerParameterName.ALLOW_PAYMENT_DUNNING_REQUEST_VIA_API)) return true

        return false
    }
}
