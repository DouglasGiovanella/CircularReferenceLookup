package com.asaas.service.customerdealinfo.feeconfig.product

import com.asaas.domain.payment.BankSlipFee
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoBankSlipFeeConfigService {

    def customerDealInfoFeeConfigService
    def feeAdminChildAccountReplicationService
    def feeAdminService
    def feeNegotiationRequestService

    public void save(Map params) {
        Map groupParams = customerDealInfoFeeConfigService.buildFeeConfigGroupParams(params, FeeNegotiationProduct.BANK_SLIP)
        Map itemParams = buildItemParams(params)

        feeNegotiationRequestService.saveNegotiationRequest(groupParams.customerDealInfo, groupParams, [itemParams])
    }

    public void applyBankSlipFee(Long customerId, FeeNegotiationReplicationType replicationType, BigDecimal value) {
        if (replicationType.shouldApplyToCustomer()) {
            BankSlipFee bankSlipFee = feeAdminService.updateBankSlipFee(customerId, value, null, null, false)
            if (bankSlipFee.hasErrors()) throw new ValidationException("Não foi possível alterar a taxa de boleto", bankSlipFee.errors)
        }

        if (replicationType.shouldApplyToChildAccount()) feeAdminChildAccountReplicationService.setBankSlipFeeManuallyForChildAccounts(customerId, value, null, null)
    }

    private Map buildItemParams(Map params) {
        Map itemParams = [:]
        itemParams.valueType = FeeNegotiationValueType.FIXED
        itemParams.productFeeType = "defaultValue"
        itemParams.value = Utils.toBigDecimal(params.defaultValue)

        return itemParams
    }
}
