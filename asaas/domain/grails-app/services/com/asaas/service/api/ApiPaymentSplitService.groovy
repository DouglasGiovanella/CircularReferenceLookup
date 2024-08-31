package com.asaas.service.api

import com.asaas.api.ApiSplitParser
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.ResourceNotFoundException
import com.asaas.paymentsplit.repository.PaymentSplitRepository
import com.asaas.utils.Utils
import grails.compiler.GrailsCompileStatic
import grails.gorm.PagedResultList
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class ApiPaymentSplitService extends ApiBaseService {

    ApiResponseBuilderService apiResponseBuilderService

    public Map findPaid(Map params) {
        Long customerId = Utils.toLong(getProvider(params))
        Map query = [publicId: params.id, originCustomerId: customerId]

        return buildPaymentSplitResponse(query)
    }

    public Map findReceived(Map params) {
        Long customerId = Utils.toLong(getProvider(params))
        Map query = [publicId: params.id, destinationCustomerId: customerId]

        return buildPaymentSplitResponse(query)
    }

    public Map listPaid(Map params) {
        Long customerId = Utils.toLong(getProvider(params))
        Map query = ApiSplitParser.parseListFilters(params)
        query.originCustomerId = customerId

        return buildPaymentSplitListResponse(query, params)
    }

    public Map listReceived(Map params) {
        Long customerId = Utils.toLong(getProvider(params))
        Map query = ApiSplitParser.parseListFilters(params)
        query.destinationCustomerId = customerId

        return buildPaymentSplitListResponse(query, params)
    }

    private Map buildPaymentSplitResponse(Map query) {
        PaymentSplit paymentSplit = PaymentSplitRepository.query(query).get()

        if (!paymentSplit) {
            throw new ResourceNotFoundException("Split inexistente.")
        }

        return apiResponseBuilderService.buildSuccess(ApiSplitParser.buildPaymentResponseItem(paymentSplit)) as Map
    }

    private Map buildPaymentSplitListResponse(Map query, Map params) {
        PagedResultList paymentSplitsList = PaymentSplitRepository.query(query).readOnly().list(max: getLimit(params), offset: getOffset(params))
        List<Map> responseItemsList = paymentSplitsList.collect { paymentSplit -> ApiSplitParser.buildPaymentResponseItem(paymentSplit as PaymentSplit) as Map }

        return apiResponseBuilderService.buildList(responseItemsList, getLimit(params), getOffset(params), paymentSplitsList.totalCount) as Map
    }
}
