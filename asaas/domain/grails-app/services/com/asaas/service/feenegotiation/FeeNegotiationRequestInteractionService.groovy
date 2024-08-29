package com.asaas.service.feenegotiation

import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.feenegotiation.FeeNegotiationRequestGroup
import com.asaas.domain.feenegotiation.FeeNegotiationRequestInteraction
import com.asaas.domain.feenegotiation.FeeNegotiationRequestItem
import com.asaas.domain.user.User
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FeeNegotiationRequestInteractionService {

    public void saveGroupItemInteraction(FeeNegotiationRequestGroup requestGroup, List<FeeNegotiationRequestItem> feeNegotiationItemList) {
        StringBuilder description = new StringBuilder()
        description.append(Utils.getMessageProperty("customerDealInfoInteraction.product.${requestGroup.product}.${requestGroup.replicationType}.label"))

        description.append(buildItemDescription(requestGroup.product, feeNegotiationItemList))

        save(requestGroup.feeNegotiationRequest, requestGroup.feeNegotiationRequest.createdBy, description.toString())
    }

    public void save(FeeNegotiationRequest request, User createdBy, String description) {
        FeeNegotiationRequestInteraction interaction = new FeeNegotiationRequestInteraction()

        interaction.feeNegotiationRequest = request
        interaction.createdBy = createdBy
        interaction.description = description
        interaction.save(failOnError: true)
    }

    private StringBuilder buildItemDescription(FeeNegotiationProduct product, List<FeeNegotiationRequestItem> feeNegotiationItemList) {
        StringBuilder description = new StringBuilder()

        for (FeeNegotiationRequestItem item : feeNegotiationItemList) {
            String formattedValue = item.valueType.isFixed() ? FormUtils.formatCurrencyWithMonetarySymbol(item.value) : FormUtils.formatWithPercentageSymbol(item.value, product.getPercentageDecimalScale(item.productFeeType))
            description.append("\n")
            description.append(Utils.getMessageProperty("customerDealInfoFeeConfig.${product}.${item.productFeeType}.label"))
            description.append(": ${formattedValue}")
        }

        return description
    }
}
