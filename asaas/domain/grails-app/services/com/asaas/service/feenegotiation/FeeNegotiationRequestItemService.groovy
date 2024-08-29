package com.asaas.service.feenegotiation

import com.asaas.domain.feenegotiation.FeeNegotiationFloor
import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.feenegotiation.FeeNegotiationRequestGroup
import com.asaas.domain.feenegotiation.FeeNegotiationRequestItem
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.feenegotiation.adapter.FeeConfigGroupItemAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FeeNegotiationRequestItemService {

    def feeNegotiationFloorService

    @Deprecated
    public FeeNegotiationRequestItem saveOrUpdate(FeeNegotiationRequestGroup requestGroup, Map params) {
        Map search = [:]
        search.column = "id"
        search.feeNegotiationRequestGroup = requestGroup
        search.productFeeType = params.productFeeType

        Long requestItemId = FeeNegotiationRequestItem.query(search).get()
        if (requestItemId) return update(requestItemId, params.value)

        return save(requestGroup, params)
    }

    public FeeNegotiationRequestItem saveOrUpdate(FeeNegotiationRequestGroup requestGroup, FeeConfigGroupItemAdapter itemAdapter) {
        Map search = [:]
        search.column = "id"
        search.feeNegotiationRequestGroup = requestGroup
        search.productFeeType = itemAdapter.productFeeType

        Long requestItemId = FeeNegotiationRequestItem.query(search).get()
        if (requestItemId) return update(requestItemId, itemAdapter.value)

        return save(requestGroup, itemAdapter)
    }

    public void delete(Long id) {
        FeeNegotiationRequestItem feeNegotiationRequestItem = FeeNegotiationRequestItem.get(id)

        feeNegotiationRequestItem.deleted = true
        feeNegotiationRequestItem.save(failOnError: true)
    }

    @Deprecated
    private FeeNegotiationRequestItem save(FeeNegotiationRequestGroup requestGroup, Map params) {
        String productFeeType = params.productFeeType

        FeeNegotiationRequestItem validatedDomain = validateSave(requestGroup, params)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        FeeNegotiationRequestItem requestItem = new FeeNegotiationRequestItem()

        requestItem.feeNegotiationRequestGroup = requestGroup
        requestItem.productFeeType = productFeeType
        requestItem.value = Utils.toBigDecimal(params.value)
        requestItem.valueType = FeeNegotiationValueType.valueOf(params.valueType.toString())
        requestItem.save(failOnError: true)

        return requestItem
    }

    private FeeNegotiationRequestItem save(FeeNegotiationRequestGroup requestGroup, FeeConfigGroupItemAdapter itemAdapter) {
        FeeNegotiationRequestItem validatedDomain = validateSave(requestGroup, itemAdapter)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        FeeNegotiationRequestItem requestItem = new FeeNegotiationRequestItem()
        requestItem.feeNegotiationRequestGroup = requestGroup
        requestItem.productFeeType = itemAdapter.productFeeType
        requestItem.value = Utils.toBigDecimal(itemAdapter.value)
        requestItem.valueType = FeeNegotiationValueType.valueOf(itemAdapter.valueType.toString())
        requestItem.save(failOnError: true)

        return requestItem
    }

    private FeeNegotiationRequestItem update(Long id, BigDecimal value) {
        FeeNegotiationRequestItem requestItem = FeeNegotiationRequestItem.get(id)

        FeeNegotiationRequestItem validatedDomain = validateUpdate(requestItem, value)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        requestItem.value = value
        requestItem.save(failOnError: true)

        return requestItem
    }

    @Deprecated
    private FeeNegotiationRequestItem validateSave(FeeNegotiationRequestGroup requestGroup, Map params) {
        FeeNegotiationRequestItem validatedDomain = new FeeNegotiationRequestItem()

        if (!requestGroup) return DomainUtils.addError(validatedDomain, "O grupo da solicitação de negociação informado não existe")

        String productFeeType = params.productFeeType
        if (!productFeeType) DomainUtils.addError(validatedDomain, "É necessário informar a taxa do produto")

        if (!params.valueType) DomainUtils.addError(validatedDomain, "É necessário informar o tipo do valor")

        FeeNegotiationRequest feeNegotiationRequest = requestGroup.feeNegotiationRequest
        FeeNegotiationFloor validatedNegotiationValue = feeNegotiationFloorService.validateNegotiationValue(requestGroup.product, productFeeType, params.value, params.valueType, feeNegotiationRequest.customerDealInfo, null)
        if (validatedNegotiationValue.hasErrors()) DomainUtils.copyAllErrorsFromObject(validatedNegotiationValue, validatedDomain)

        return validatedDomain
    }

    private FeeNegotiationRequestItem validateSave(FeeNegotiationRequestGroup requestGroup, FeeConfigGroupItemAdapter itemAdapter) {
        FeeNegotiationRequestItem validatedDomain = new FeeNegotiationRequestItem()

        if (!requestGroup) return DomainUtils.addError(validatedDomain, "O grupo da solicitação de negociação informado não existe")

        if (!itemAdapter.productFeeType) DomainUtils.addError(validatedDomain, "É necessário informar a taxa do produto")

        if (!itemAdapter.value) DomainUtils.addError(validatedDomain, "É necessário informar o valor")

        if (!itemAdapter.valueType) DomainUtils.addError(validatedDomain, "É necessário informar o tipo do valor")

        FeeNegotiationRequest feeNegotiationRequest = requestGroup.feeNegotiationRequest
        FeeNegotiationFloor validatedNegotiationValue = feeNegotiationFloorService.validateNegotiationValue(requestGroup.product, itemAdapter.productFeeType, itemAdapter.value, itemAdapter.valueType, feeNegotiationRequest.customerDealInfo, null)
        if (validatedNegotiationValue.hasErrors()) DomainUtils.copyAllErrorsFromObject(validatedNegotiationValue, validatedDomain)

        return validatedDomain
    }

    private FeeNegotiationRequestItem validateUpdate(FeeNegotiationRequestItem requestItem, BigDecimal value) {
        FeeNegotiationRequestItem validatedDomain = new FeeNegotiationRequestItem()

        FeeNegotiationRequest feeNegotiationRequest = requestItem.feeNegotiationRequestGroup.feeNegotiationRequest
        FeeNegotiationFloor validatedNegotiationValue = feeNegotiationFloorService.validateNegotiationValue(requestItem.feeNegotiationRequestGroup.product, requestItem.productFeeType, value, requestItem.valueType, feeNegotiationRequest.customerDealInfo, null)
        if (validatedNegotiationValue.hasErrors()) DomainUtils.copyAllErrorsFromObject(validatedNegotiationValue, validatedDomain)

        return validatedDomain
    }
}
