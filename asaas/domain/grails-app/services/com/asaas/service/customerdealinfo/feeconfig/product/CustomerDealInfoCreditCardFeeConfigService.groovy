package com.asaas.service.customerdealinfo.feeconfig.product

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoCreditCardFeeConfigService {

    def customerDealInfoFeeConfigService
    def childAccountParameterBinderService
    def creditCardFeeConfigParameterService
    def creditCardFeeConfigService
    def feeAdminService
    def feeNegotiationRequestService

    public void save(Map params) {
        Map groupParams = customerDealInfoFeeConfigService.buildFeeConfigGroupParams(params, FeeNegotiationProduct.CREDIT_CARD)

        BusinessValidation businessValidation = validateSave(groupParams.customerDealInfo.id, groupParams.replicationType)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        List<Map> itemParamsList = buildItemParamsList(params)
        feeNegotiationRequestService.saveNegotiationRequest(groupParams.customerDealInfo, groupParams, itemParamsList)
    }

    public void delete(Long feeConfigGroupId) {
        CustomerDealInfoFeeConfigGroup feeConfigGroup = CustomerDealInfoFeeConfigGroup.get(feeConfigGroupId)
        if (!feeConfigGroup) throw new ResourceNotFoundException("A negociação de taxa não foi encontrada")
        if (!feeConfigGroup.product.isCreditCard()) throw new BusinessException("A negociação de taxa informada não é de Cartão de Crédito")

        Long customerId = feeConfigGroup.customerDealInfo.customer.id
        FeeNegotiationReplicationType replicationType = feeConfigGroup.replicationType

        customerDealInfoFeeConfigService.deleteFeeConfigGroupItem(feeConfigGroup)

        applyDefaultCreditCardFeeConfig(customerId, replicationType)
    }

    public Boolean hasDynamicMccCreditCardNegotiation(Long customerDealInfoId) {
        Map search = [:]
        search.customerDealInfoId = customerDealInfoId
        search.product = FeeNegotiationProduct.CREDIT_CARD
        search.replicationType = FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC

        return CustomerDealInfoFeeConfigGroupRepository.query(search).exists()
    }

    public void applyCreditCardFeeConfig(Long customerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        Map feeConfigInfo = buildFeeConfigInfo(itemParamsList)

        applyToCustomerIfPossible(customerId, replicationType, feeConfigInfo)
        applyToChildAccountIfPossible(customerId, replicationType, feeConfigInfo)
    }

    private BusinessValidation validateSave(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (replicationType.shouldApplyToChildAccount()) {
            Boolean hasDynamicMccNegotiation = hasDynamicMccCreditCardNegotiation(customerDealInfoId)
            if (hasDynamicMccNegotiation) {
                businessValidation.addError("customerDealInfoFeeConfig.notAllowedSaveChildAccountFeeConfig.hasDynamicMccNegotiation")
                return businessValidation
            }
        }

        return businessValidation
    }

    private Map buildFeeConfigInfo(List<Map> itemParamsList) {
        Map feeConfigInfo = [:]

        for (Map itemParams : itemParamsList) {
            feeConfigInfo[itemParams.productFeeType] = itemParams.value
        }

        feeConfigInfo.discountExpiration = null
        feeConfigInfo.discountUpfrontFee = null
        feeConfigInfo.discountUpToSixInstallmentsFee = null
        feeConfigInfo.discountUpToTwelveInstallmentsFee = null

        return feeConfigInfo
    }

    private List<Map> buildItemParamsList(Map params) {
        List<Map> itemParamsList = []
        List<String> productFeeTypeList = ["fixedFee", "upfrontFee", "upToSixInstallmentsFee", "upToTwelveInstallmentsFee"]

        for (String productFeeType : productFeeTypeList) {
            Map itemParams = [:]
            itemParams.valueType = productFeeType == "fixedFee" ? FeeNegotiationValueType.FIXED : FeeNegotiationValueType.PERCENTAGE
            itemParams.productFeeType = productFeeType
            itemParams.value = Utils.toBigDecimal(params[productFeeType])
            itemParamsList += itemParams
        }

        return itemParamsList
    }

    private void applyDefaultCreditCardFeeConfig(Long customerId, FeeNegotiationReplicationType replicationType) {
        Map feeConfig = creditCardFeeConfigService.buildDefaultCreditCardFeeConfig()

        applyToCustomerIfPossible(customerId, replicationType, feeConfig)
        applyToChildAccountIfPossible(customerId, replicationType, feeConfig)
    }

    private void applyToCustomerIfPossible(Long customerId, FeeNegotiationReplicationType replicationType, Map feeConfig) {
        if (!replicationType.shouldApplyToCustomer()) return

        CreditCardFeeConfig creditCardFeeConfig = feeAdminService.updateCreditCardFee(customerId, feeConfig, false)
        if (creditCardFeeConfig.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de cartão de crédito", creditCardFeeConfig.errors)
    }

    private void applyToChildAccountIfPossible(Long customerId, FeeNegotiationReplicationType replicationType, Map feeConfig) {
        if (!replicationType.shouldApplyToChildAccount()) return

        List<String> fieldNameList = feeConfig.keySet() as List
        creditCardFeeConfigParameterService.saveParameter(customerId, feeConfig)
        childAccountParameterBinderService.applyParameterListForAllChildAccounts(customerId, fieldNameList, CreditCardFeeConfig.simpleName)
    }
}
