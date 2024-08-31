package com.asaas.service.customerdealinfo

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigMccRepository
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigItem
import com.asaas.domain.customerdealinfo.CustomerDealInfoInteraction
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerDealInfoInteractionService {

    public void saveFeeNegotiationInteraction(CustomerDealInfoFeeConfigGroup feeConfigGroup, List<CustomerDealInfoFeeConfigItem> feeConfigItemList) {
        if (feeConfigGroup.replicationType.isOnlyChildAccountWithDynamicMcc()) return

        StringBuilder description = new StringBuilder()
        description.append(Utils.getMessageProperty("customerDealInfoInteraction.product.${feeConfigGroup.product}.${feeConfigGroup.replicationType}.label"))

        description.append(buildFeeConfigItemDescription(feeConfigGroup, feeConfigItemList))

        save(feeConfigGroup.customerDealInfo, description.toString())
    }

    public void saveDynamicMccFeeNegotiationInteraction(CustomerDealInfoFeeConfigGroup feeConfigGroup) {
        if (!feeConfigGroup.replicationType.isOnlyChildAccountWithDynamicMcc()) return

        StringBuilder description = new StringBuilder()
        description.append(Utils.getMessageProperty("customerDealInfoInteraction.product.${feeConfigGroup.product}.${feeConfigGroup.replicationType}.label"))
        description.append("\n")

        String mccListString = CustomerDealInfoFeeConfigMccRepository.listFeeConfigGroupMccAsString(feeConfigGroup.id)
        description.append("MCCs negociados: ${mccListString}")
        description.append("\n")

        List<CustomerDealInfoFeeConfigItem> feeConfigItemList = CustomerDealInfoFeeConfigItem.query([group: feeConfigGroup, sort: "id", order: "asc"]).list()
        description.append(buildFeeConfigItemDescription(feeConfigGroup, feeConfigItemList))

        save(feeConfigGroup.customerDealInfo, description.toString())
    }

    public void saveDeleteFeeNegotiationInteraction(CustomerDealInfoFeeConfigGroup feeConfigGroup) {
        String description = Utils.getMessageProperty("customerDealInfoInteraction.delete.product.${feeConfigGroup.product}.${feeConfigGroup.replicationType}.label")
        save(feeConfigGroup.customerDealInfo, description)
    }

    public void saveDeleteDynamicMccFeeNegotiationInteraction(CustomerDealInfo customerDealInfo) {
        String description = Utils.getMessageProperty("customerDealInfoInteraction.delete.product.CREDIT_CARD.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC.label")
        save(customerDealInfo, description.toString())
    }

    public void save(CustomerDealInfo customerDealInfo, String description) {
        CustomerDealInfoInteraction interaction = new CustomerDealInfoInteraction()

        interaction.customerDealInfo = customerDealInfo
        interaction.description = description
        interaction.save(failOnError: true)
    }

    private StringBuilder buildFeeConfigItemDescription(CustomerDealInfoFeeConfigGroup feeConfigGroup, List<CustomerDealInfoFeeConfigItem> feeConfigItemList) {
        StringBuilder description = new StringBuilder()

        for (CustomerDealInfoFeeConfigItem feeConfigItem : feeConfigItemList) {
            String formattedValue = feeConfigItem.valueType.isFixed() ? FormUtils.formatCurrencyWithMonetarySymbol(feeConfigItem.value) : FormUtils.formatWithPercentageSymbol(feeConfigItem.value, feeConfigGroup.product.getPercentageDecimalScale(feeConfigItem.productFeeType))
            description.append("\n")
            description.append(Utils.getMessageProperty("customerDealInfoFeeConfig.${feeConfigGroup.product}.${feeConfigItem.productFeeType}.label"))
            description.append(": ${formattedValue}")
        }

        return description
    }
}
