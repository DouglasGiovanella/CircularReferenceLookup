package com.asaas.service.customerdealinfo.feeconfig

import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigItem
import com.asaas.domain.feenegotiation.FeeNegotiationFloor
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoFeeConfigItemService {

    def feeNegotiationFloorService

    public CustomerDealInfoFeeConfigItem saveOrUpdate(CustomerDealInfoFeeConfigGroup feeConfigGroup, Map params) {
        Map search = [:]
        search.column = "id"
        search.group = feeConfigGroup
        search.productFeeType = params.productFeeType

        Long feeConfigItemId = CustomerDealInfoFeeConfigItem.query(search).get()
        if (feeConfigItemId) return update(feeConfigItemId, params.value)

        return save(feeConfigGroup, params)
    }

    public CustomerDealInfoFeeConfigItem save(CustomerDealInfoFeeConfigGroup feeConfigGroup, Map params) {
        CustomerDealInfoFeeConfigItem validatedDomain = validateSave(feeConfigGroup, params)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerDealInfoFeeConfigItem feeConfigItem = new CustomerDealInfoFeeConfigItem()

        feeConfigItem.group = feeConfigGroup
        feeConfigItem.properties["productFeeType", "value", "valueType"] = params
        feeConfigItem.save(failOnError: true)

        return feeConfigItem
    }

    public CustomerDealInfoFeeConfigItem update(Long id, BigDecimal value) {
        CustomerDealInfoFeeConfigItem feeConfigItem = CustomerDealInfoFeeConfigItem.get(id)

        CustomerDealInfoFeeConfigItem validatedDomain = validateUpdate(feeConfigItem, value)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        feeConfigItem.value = value
        feeConfigItem.save(failOnError: true)

        return feeConfigItem
    }

    public void deleteAll(Long feeConfigGroupId) {
        List<Long> feeConfigItemIdList = CustomerDealInfoFeeConfigItem.query([column: "id", groupId: feeConfigGroupId]).list()

        for (Long feeConfigItemId : feeConfigItemIdList) {
            delete(feeConfigItemId)
        }
    }

    private void delete(Long id) {
        CustomerDealInfoFeeConfigItem feeConfigItem = CustomerDealInfoFeeConfigItem.get(id)

        feeConfigItem.deleted = true
        feeConfigItem.save(failOnError: true)
    }

    private CustomerDealInfoFeeConfigItem validateSave(CustomerDealInfoFeeConfigGroup feeConfigGroup, Map params) {
        CustomerDealInfoFeeConfigItem validatedDomain = new CustomerDealInfoFeeConfigItem()
        String productFeeType = params.productFeeType

        if (!feeConfigGroup) DomainUtils.addError(validatedDomain, "O grupo da negociação informado não existe")

        if (!productFeeType) DomainUtils.addError(validatedDomain, "É necessário informar a taxa do produto")

        if (!params.valueType) DomainUtils.addError(validatedDomain, "É necessário informar o tipo do valor")

        FeeNegotiationFloor validatedNegotiationValue = feeNegotiationFloorService.validateNegotiationValue(feeConfigGroup.product, productFeeType, params.value, params.valueType, feeConfigGroup.customerDealInfo, feeConfigGroup)
        if (validatedNegotiationValue.hasErrors()) DomainUtils.copyAllErrorsFromObject(validatedNegotiationValue, validatedDomain)

        return validatedDomain
    }

    private CustomerDealInfoFeeConfigItem validateUpdate(CustomerDealInfoFeeConfigItem feeConfigItem, BigDecimal value) {
        CustomerDealInfoFeeConfigItem validatedDomain = new CustomerDealInfoFeeConfigItem()

        FeeNegotiationProduct product = feeConfigItem.group.product
        String productFeeType = feeConfigItem.productFeeType

        FeeNegotiationFloor validatedNegotiationValue = feeNegotiationFloorService.validateNegotiationValue(product, productFeeType, value, feeConfigItem.valueType, feeConfigItem.group.customerDealInfo, feeConfigItem.group)
        if (validatedNegotiationValue.hasErrors()) DomainUtils.copyAllErrorsFromObject(validatedNegotiationValue, validatedDomain)

        return validatedDomain
    }
}
