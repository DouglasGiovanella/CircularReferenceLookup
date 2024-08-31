package com.asaas.service.customerdealinfo.feeconfig

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigItem
import com.asaas.feenegotiation.adapter.CreditBureauReportFeeConfigAdapter
import com.asaas.feenegotiation.adapter.FixedFeeConfigAdapter
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerDealInfoFeeConfigService {

    def customerDealInfoCreditCardFeeConfigService
    def customerDealInfoFeeConfigGroupService
    def customerDealInfoFeeConfigItemService
    def customerDealInfoInteractionService

    public Map buildFeeConfigInfo(CustomerDealInfo customerDealInfo, Boolean isChildAccountConfig) {
        Map builderInfo = [:]
        builderInfo.customerDealInfo = customerDealInfo
        builderInfo.isChildAccountConfig = isChildAccountConfig
        builderInfo.canManageCustomerDealInfo = AdminUserPermissionUtils.allowed(AdminUserPermissionName.MANAGE_CUSTOMER_DEAL_INFO)
        builderInfo.replicationTypeList = [FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT]
        builderInfo.replicationTypeList += isChildAccountConfig ? FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT : FeeNegotiationReplicationType.CUSTOMER

        return [
            bankSlipFeeConfig: buildProductFeeConfig(FeeNegotiationProduct.BANK_SLIP, builderInfo),
            receivableAnticipationFeeConfig: buildProductFeeConfig(FeeNegotiationProduct.RECEIVABLE_ANTICIPATION, builderInfo),
            pixCreditFeeConfig: buildProductFeeConfig(FeeNegotiationProduct.PIX_CREDIT, builderInfo),
            creditCardFeeConfig: buildProductFeeConfig(FeeNegotiationProduct.CREDIT_CARD, builderInfo),
            paymentMessagingNotificationFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION, customerDealInfo, isChildAccountConfig),
            whatsappNotificationFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.WHATSAPP_NOTIFICATION, customerDealInfo, isChildAccountConfig),
            pixDebitFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.PIX_DEBIT, customerDealInfo, isChildAccountConfig),
            childAccountKnownYourCustomerFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER, customerDealInfo, isChildAccountConfig),
            serviceInvoiceFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.SERVICE_INVOICE, customerDealInfo, isChildAccountConfig),
            creditBureauReportFeeConfigAdapter: new CreditBureauReportFeeConfigAdapter(FeeNegotiationProduct.CREDIT_BUREAU_REPORT, customerDealInfo, isChildAccountConfig),
            transferFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.TRANSFER, customerDealInfo, isChildAccountConfig),
            dunningCreditBureauFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.DUNNING_CREDIT_BUREAU, customerDealInfo, isChildAccountConfig),
            phoneCallNotificationFeeConfigAdapter: new FixedFeeConfigAdapter(FeeNegotiationProduct.PHONE_CALL_NOTIFICATION, customerDealInfo, isChildAccountConfig),
        ]
    }

    public CustomerDealInfoFeeConfigGroup saveOrUpdateFeeConfigGroupItem(Map groupParams, List<Map> itemParamsList) {
        CustomerDealInfoFeeConfigGroup feeConfigGroup = customerDealInfoFeeConfigGroupService.saveOrUpdate(groupParams)
        List<CustomerDealInfoFeeConfigItem> feeConfigItemList = []

        for (Map itemParams : itemParamsList) {
            feeConfigItemList += customerDealInfoFeeConfigItemService.saveOrUpdate(feeConfigGroup, itemParams)
        }

        customerDealInfoInteractionService.saveFeeNegotiationInteraction(feeConfigGroup, feeConfigItemList)

        return feeConfigGroup
    }

    public void deleteFeeConfigGroupItem(CustomerDealInfoFeeConfigGroup feeConfigGroup) {
        customerDealInfoFeeConfigGroupService.delete(feeConfigGroup.id)

        customerDealInfoInteractionService.saveDeleteFeeNegotiationInteraction(feeConfigGroup)
    }

    public Map buildFeeConfigGroupParams(Map params, FeeNegotiationProduct product) {
        Boolean isChildAccountConfig = Boolean.valueOf(params.isChildAccountConfig)
        Boolean applyForAccountOwnerAndChildAccount = Boolean.valueOf(params.applyForAccountOwnerAndChildAccount)

        Map groupParams = [:]
        groupParams.customerDealInfo = CustomerDealInfo.read(Utils.toLong(params.customerDealInfoId))
        groupParams.product = product
        groupParams.replicationType = FeeNegotiationReplicationType.getReplicationType(isChildAccountConfig, applyForAccountOwnerAndChildAccount)

        Long feeConfigGroupId = params.feeConfigGroupId ? Utils.toLong(params.feeConfigGroupId) : CustomerDealInfoFeeConfigGroupRepository.query(groupParams).column("id").get()
        groupParams.id = feeConfigGroupId

        return groupParams
    }

    private Map buildProductFeeConfig(FeeNegotiationProduct product, Map builderInfo) {
        Map search = [:]
        search.customerDealInfo = builderInfo.customerDealInfo
        search.product = product
        search."replicationType[in]" = builderInfo.replicationTypeList

        Map feeConfigGroupInfo = CustomerDealInfoFeeConfigGroupRepository.query(search).column(["id", "replicationType"]).get()

        Map productFeeConfigInfo = [feeConfig: [:]]
        productFeeConfigInfo.feeConfig.feeConfigGroupId = feeConfigGroupInfo?.id
        productFeeConfigInfo.feeConfig.replicationType = feeConfigGroupInfo?.replicationType
        productFeeConfigInfo.feeConfig.product = product
        productFeeConfigInfo.feeConfig.readOnly = isReadOnly(product, productFeeConfigInfo.feeConfig.replicationType, builderInfo)

        if (!feeConfigGroupInfo?.id) return productFeeConfigInfo

        List<Map> feeConfigItemInfoList = CustomerDealInfoFeeConfigItem.query([columnList: ["productFeeType", "value"], "groupId": feeConfigGroupInfo?.id]).list()
        for (Map feeConfigItemInfo: feeConfigItemInfoList) {
            productFeeConfigInfo.feeConfig[feeConfigItemInfo.productFeeType] = feeConfigItemInfo.value
        }

        return productFeeConfigInfo
    }

    private Boolean isReadOnly(FeeNegotiationProduct product, FeeNegotiationReplicationType replicationType, Map builderInfo) {
        if (builderInfo.customerDealInfo.deleted) return true

        if (!builderInfo.isChildAccountConfig) return !builderInfo.canManageCustomerDealInfo

        if (replicationType?.isAccountOwnerAndChildAccount()) return true

        if (product.isCreditCard()) {
            Boolean hasDynamicMccNegotiation = customerDealInfoCreditCardFeeConfigService.hasDynamicMccCreditCardNegotiation(builderInfo.customerDealInfo.id)
            if (hasDynamicMccNegotiation) return true
        }

        return false
    }
}
