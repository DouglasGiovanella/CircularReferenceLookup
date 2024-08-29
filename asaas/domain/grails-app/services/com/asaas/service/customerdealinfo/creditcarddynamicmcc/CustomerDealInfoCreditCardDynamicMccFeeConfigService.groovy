package com.asaas.service.customerdealinfo.creditcarddynamicmcc

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerStatus
import com.asaas.customer.PersonType
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigMccRepository
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessActivity
import com.asaas.domain.customer.CustomerEconomicActivity
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigItem
import com.asaas.domain.feenegotiation.FeeNegotiationMcc
import com.asaas.domain.feenegotiation.FeeNegotiationRequestGroup
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationMccRepository
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationRequestStatus
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerDealInfoCreditCardDynamicMccFeeConfigService {

    def asyncActionService
    def customerDealInfoFeeConfigGroupService
    def customerDealInfoFeeConfigItemService
    def customerDealInfoFeeConfigMccService
    def customerDealInfoInteractionService

    public List<Map> buildFeeConfigInfoList(Long customerDealInfoId) {
        Map search = [:]
        search.customerDealInfoId = customerDealInfoId
        search.product = FeeNegotiationProduct.CREDIT_CARD
        search.replicationType = FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC
        List<Long> feeConfigGroupIdList = CustomerDealInfoFeeConfigGroupRepository.query(search).column("id").list()

        List<Map> feeConfigInfoList = []
        for (Long feeConfigGroupId : feeConfigGroupIdList) {
            Map feeConfigInfo  = [:]
            feeConfigInfo.feeConfigGroupId = feeConfigGroupId
            feeConfigInfo.mccList = CustomerDealInfoFeeConfigMccRepository.listFeeConfigGroupMccAsString(feeConfigGroupId)
            feeConfigInfo.feeConfigItemInfo = buildFeeConfigItemInfo(feeConfigGroupId, true)

            feeConfigInfoList.add(feeConfigInfo)
        }

        return feeConfigInfoList
    }

    public Map buildFeeConfigItemInfo(Long feeConfigGroupId, Boolean formatValue) {
        Map feeConfigItemInfo = [:]

        List<Map> feeConfigItemInfoList = CustomerDealInfoFeeConfigItem.query([columnList: ["productFeeType", "value", "valueType"], groupId: feeConfigGroupId]).list()
        for (Map itemInfo : feeConfigItemInfoList) {
            String formattedValue
            if (formatValue) formattedValue = itemInfo.valueType.isFixed() ? FormUtils.formatCurrencyWithMonetarySymbol(itemInfo.value) : FormUtils.formatWithPercentageSymbol(itemInfo.value, FeeNegotiationProduct.CREDIT_CARD.getPercentageDecimalScale(itemInfo.productFeeType))

            feeConfigItemInfo[itemInfo.productFeeType] = formattedValue ?: itemInfo.value
        }

        return feeConfigItemInfo
    }

    public void save(Map params) {
        List<Long> feeNegotiationMccIdList = params.feeNegotiationMccIdList?.split(",")?.collect { Utils.toLong(it) }

        Map groupParams = buildGroupParams(params)

        BusinessValidation businessValidation = validateSave(groupParams.customerDealInfo, feeNegotiationMccIdList, groupParams.id)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        CustomerDealInfoFeeConfigGroup feeConfigGroup = customerDealInfoFeeConfigGroupService.saveOrUpdate(groupParams)

        customerDealInfoFeeConfigMccService.saveOrUpdate(feeConfigGroup, feeNegotiationMccIdList)

        List<Map> itemParamsList = buildItemParamsList(params)
        for (Map itemParams : itemParamsList) {
            customerDealInfoFeeConfigItemService.saveOrUpdate(feeConfigGroup, itemParams)
        }

        customerDealInfoInteractionService.saveDynamicMccFeeNegotiationInteraction(feeConfigGroup)

        Map actionData = [accountOwnerId: feeConfigGroup.customerDealInfo.customer.id, feeConfigGroupId: feeConfigGroup.id]
        asyncActionService.save(AsyncActionType.CHILD_ACCOUNT_CREDIT_CARD_DYNAMIC_MCC_FEE_CONFIG_REPLICATION, actionData)
    }

    public Boolean hasChildAccountWithoutMcc(Long accountOwnerId) {
        if (hasChildAccountWithoutEconomicActivity(accountOwnerId)) return true

        if (hasChildAccountWithoutBusinessActivity(accountOwnerId)) return true

        return false
    }

    public BusinessValidation validateCustomerDealInfo(CustomerDealInfo customerDealInfo) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean hasChildAccountNegotiation = hasDefaultCreditCardChildAccountNegotiation(customerDealInfo.id)
        if (hasChildAccountNegotiation) {
            businessValidation.addError("customerDealInfoFeeConfigMcc.canSaveFeeConfigMcc.hasChildAccountNegotiation")
            return businessValidation
        }

        Boolean hasChildAccountFeeNegotiationRequestAwaitingApproval = hasCreditCardChildAccountFeeNegotiationRequestAwaitingApproval(customerDealInfo.id)
        if (hasChildAccountFeeNegotiationRequestAwaitingApproval) {
            businessValidation.addError("customerDealInfoFeeConfigMcc.canSaveFeeConfigMcc.hasChildAccountFeeNegotiationRequestAwaitingApproval")
            return businessValidation
        }

        if (customerDealInfo.customer.hasAccountAutoApprovalEnabled()) {
            businessValidation.addError("customerDealInfoFeeConfigMcc.canSaveFeeConfigMcc.customerHasAutoApprovalEnabled")
            return businessValidation
        }

        return businessValidation
    }

    private Map buildGroupParams(Map params) {
        Map groupParams = [:]
        groupParams.id = Utils.toLong(params.feeConfigGroupId)
        groupParams.customerDealInfo = CustomerDealInfo.read(Utils.toLong(params.customerDealInfoId))
        groupParams.product = FeeNegotiationProduct.CREDIT_CARD
        groupParams.replicationType = FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC

        return groupParams
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

    private BusinessValidation validateSave(CustomerDealInfo customerDealInfo, List<Long> feeNegotiationMccIdList, Long feeConfigGroupId) {
        BusinessValidation businessValidation = validateCustomerDealInfo(customerDealInfo)
        if (!businessValidation.isValid()) return businessValidation

        businessValidation = validateFeeNegotiationMccList(customerDealInfo, feeNegotiationMccIdList, feeConfigGroupId)
        if (!businessValidation.isValid()) return businessValidation

        return businessValidation
    }

    private BusinessValidation validateFeeNegotiationMccList(CustomerDealInfo customerDealInfo, List<Long> feeNegotiationMccIdList, Long feeConfigGroupId) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!feeNegotiationMccIdList) {
            businessValidation.addError("customerDealInfoFeeConfigMcc.canSaveFeeConfigMcc.noMccSelected")
            return businessValidation
        }

        for (Long feeNegotiationMccId : feeNegotiationMccIdList) {
            Boolean isValidFeeNegotiationMcc = FeeNegotiationMccRepository.query(["id": feeNegotiationMccId]).exists()
            if (!isValidFeeNegotiationMcc) {
                businessValidation.addError("customerDealInfoFeeConfigMcc.canSaveFeeConfigMcc.notExistentFeeNegotiationMcc", [feeNegotiationMccId])
            }
        }

        Map search = [:]
        search."feeNegotiationMccId[in]" = feeNegotiationMccIdList
        search.customerDealInfo = customerDealInfo
        if (feeConfigGroupId) search."feeConfigGroupId[ne]" = feeConfigGroupId

        List<FeeNegotiationMcc> alreadyNegotiatedMccList = CustomerDealInfoFeeConfigMccRepository.query(search).column("feeNegotiationMcc").list()
        if (alreadyNegotiatedMccList) {
            alreadyNegotiatedMccList = alreadyNegotiatedMccList.collect { it.buildLabel() }
            businessValidation.addError("customerDealInfoFeeConfigMcc.canSaveFeeConfigMcc.mccAlreadyNegotiated", [alreadyNegotiatedMccList])
            return businessValidation
        }

        return businessValidation
    }

    private Boolean hasDefaultCreditCardChildAccountNegotiation(Long customerDealInfoId) {
        Map search = [:]
        search.customerDealInfoId = customerDealInfoId
        search.product = FeeNegotiationProduct.CREDIT_CARD
        search."replicationType[in]" = [FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT, FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT]

        return CustomerDealInfoFeeConfigGroupRepository.query(search).exists()
    }

    private Boolean hasCreditCardChildAccountFeeNegotiationRequestAwaitingApproval(Long customerDealInfoId) {
        Map search = [:]
        search.exists = true
        search.feeNegotiationRequestCustomerDealInfoId = customerDealInfoId
        search.product = FeeNegotiationProduct.CREDIT_CARD
        search.replicationTypeList = FeeNegotiationReplicationType.applicableForChildAccount()
        search.feeNegotiationRequestStatus = FeeNegotiationRequestStatus.AWAITING_APPROVAL

        return FeeNegotiationRequestGroup.query(search).get().asBoolean()
    }

    private Boolean hasChildAccountWithoutEconomicActivity(Long accountOwnerId) {
        return Customer.createCriteria().list(max: 1) {
            projections {
                property "id"
            }

            eq("deleted", false)
            eq("accountOwner.id", accountOwnerId)
            eq("personType", PersonType.JURIDICA)
            ne("status", CustomerStatus.DISABLED)

            notExists CustomerEconomicActivity.where {
                setAlias("customerEconomicActivity")

                eqProperty("customerEconomicActivity.customer.id", "this.id")
            }.id()
        }.asBoolean()
    }

    private Boolean hasChildAccountWithoutBusinessActivity(Long accountOwnerId) {
        return Customer.createCriteria().list(max: 1) {
            projections {
                property "id"
            }

            eq("deleted", false)
            eq("accountOwner.id", accountOwnerId)
            eq("personType", PersonType.FISICA)
            ne("status", CustomerStatus.DISABLED)

            notExists CustomerBusinessActivity.where {
                setAlias("customerBusinessActivity")

                eqProperty("customerBusinessActivity.customer.id", "this.id")
            }.id()
        }.asBoolean()
    }
}
