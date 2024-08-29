package com.asaas.service.customerdealinfo.feeconfig.product

import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigItem
import com.asaas.domain.pix.PixCreditFee
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.pix.PixCreditFeeType
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoPixCreditFeeConfigService {

    def customerDealInfoFeeConfigService
    def childAccountParameterBinderService
    def feeAdminService
    def feeNegotiationRequestService
    def pixCreditFeeParameterService
    def pixCreditFeeService

    public void save(Map params) {
        Map groupParams = customerDealInfoFeeConfigService.buildFeeConfigGroupParams(params, FeeNegotiationProduct.PIX_CREDIT)
        FeeNegotiationValueType valueType = FeeNegotiationValueType.convert(params.valueType)
        List<Map> itemParamsList = buildItemParamsList(valueType, params)

        if (valueType.isPercentage()) validatePercentageFeeValues(itemParamsList)

        feeNegotiationRequestService.saveNegotiationRequest(groupParams.customerDealInfo, groupParams, itemParamsList)
    }

    public void applyPixCreditFee(Long customerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        FeeNegotiationValueType valueType = itemParamsList.any { it.valueType.isPercentage() } ? FeeNegotiationValueType.PERCENTAGE : FeeNegotiationValueType.FIXED
        PixCreditFeeType pixCreditFeeType = PixCreditFeeType.convert(valueType)
        Map pixCreditFeeConfig = buildPixCreditFeeConfigToApply(valueType, itemParamsList)

        applyToCustomerIfPossible(customerId, pixCreditFeeConfig, pixCreditFeeType, replicationType)
        applyToChildAccountIfPossible(customerId, pixCreditFeeConfig, pixCreditFeeType, replicationType)
    }

    public void deleteOldConfigIfChangedValueType(CustomerDealInfoFeeConfigGroup currentFeeConfigGroup, List<Map> itemParamsList) {
        Map search = [:]
        search.column = "id"
        search.group = currentFeeConfigGroup

        FeeNegotiationValueType valueType = itemParamsList.any { it.valueType.isPercentage() } ? FeeNegotiationValueType.PERCENTAGE : FeeNegotiationValueType.FIXED
        if (valueType.isFixed()) {
            search."productFeeType[in]" = ["percentageFee", "minimumFee", "maximumFee"]
        } else {
            search.productFeeType = "fixedFee"
        }

        List<Long> oldFeeConfigItemIdList = CustomerDealInfoFeeConfigItem.query(search).list()
        for (Long oldFeeConfigItemId : oldFeeConfigItemIdList) {
            CustomerDealInfoFeeConfigItem feeConfigItem = CustomerDealInfoFeeConfigItem.get(oldFeeConfigItemId)

            feeConfigItem.deleted = true
            feeConfigItem.save(failOnError: true)
        }
    }

    private List<Map> buildItemParamsList(FeeNegotiationValueType valueType, Map params) {
        List<String> fixedProductFeeTypeList = ["fixedFee"]
        List<String> percentageProductFeeTypeList = ["percentageFee", "minimumFee", "maximumFee"]

        List<String> productFeeTypeList = valueType.isFixed() ? fixedProductFeeTypeList : percentageProductFeeTypeList

        List<Map> itemParamsList = []
        for (String productFeeType : productFeeTypeList) {
            Map itemParams = [:]
            itemParams.valueType = productFeeType == "percentageFee" ? FeeNegotiationValueType.PERCENTAGE : FeeNegotiationValueType.FIXED
            itemParams.productFeeType = productFeeType
            itemParams.value = Utils.toBigDecimal(params[productFeeType])
            itemParamsList += itemParams
        }

        return itemParamsList
    }

    private Map buildPixCreditFeeConfigToApply(FeeNegotiationValueType valueType, List<Map> itemParamsList) {
        Map itemParams = itemParamsList.groupBy { it.productFeeType }

        Map pixCreditFeeConfig = [:]
        if (valueType.isFixed()) {
            pixCreditFeeConfig.fixedFee = itemParams.fixedFee.value.first()
            pixCreditFeeConfig.fixedFeeWithDiscount = null
            pixCreditFeeConfig.discountExpiration = null
        } else {
            pixCreditFeeConfig.percentageFee = itemParams.percentageFee.value.first()
            pixCreditFeeConfig.minimumFee = itemParams.minimumFee.value.first()
            pixCreditFeeConfig.maximumFee = itemParams.maximumFee.value.first()
        }

        return pixCreditFeeConfig
    }

    private void applyToCustomerIfPossible(Long customerId, Map pixCreditFeeConfig, PixCreditFeeType pixCreditFeeType, FeeNegotiationReplicationType replicationType) {
        if (!replicationType.shouldApplyToCustomer()) return

        feeAdminService.updatePixCreditFee(customerId, pixCreditFeeType, pixCreditFeeConfig, false)
    }

    private void applyToChildAccountIfPossible(Long accountOwnerId, Map pixCreditFeeConfig, PixCreditFeeType pixCreditFeeType, FeeNegotiationReplicationType replicationType) {
        if (!replicationType.shouldApplyToChildAccount()) return

        pixCreditFeeConfig.type = pixCreditFeeType
        List<String> fieldNameList = pixCreditFeeConfig.keySet() as List

        pixCreditFeeParameterService.saveParameter(accountOwnerId, pixCreditFeeConfig)
        childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, fieldNameList, PixCreditFee.simpleName)
    }

    private void validatePercentageFeeValues(List<Map> itemParamsList) {
        Map itemParams = itemParamsList.groupBy { it.productFeeType }
        BigDecimal percentageFeeValue = itemParams.percentageFee.value.first()
        BigDecimal minimumFeeValue = itemParams.minimumFee.value.first()
        BigDecimal maximumFeeValue = itemParams.maximumFee.value.first()

        PixCreditFee validatedPixCreditFee = pixCreditFeeService.validateSavePercentageFee(percentageFeeValue, minimumFeeValue, maximumFeeValue)
        if (validatedPixCreditFee.hasErrors()) throw new ValidationException("Não foi possível salvar as configurações de taxas de crédito de Pix", validatedPixCreditFee.errors)
    }
}
