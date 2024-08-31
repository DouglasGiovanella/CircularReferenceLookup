package com.asaas.service.customerdealinfo.feeconfig.product

import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoReceivableAnticipationFeeConfigService {

    def childAccountParameterBinderService
    def customerDealInfoFeeConfigService
    def customerReceivableAnticipationConfigParameterService
    def feeAdminService
    def feeNegotiationRequestService

    public void save(Map params) {
        Map groupParams = customerDealInfoFeeConfigService.buildFeeConfigGroupParams(params, FeeNegotiationProduct.RECEIVABLE_ANTICIPATION)
        List<Map> itemParamsList = buildItemParamsList(params)

        feeNegotiationRequestService.saveNegotiationRequest(groupParams.customerDealInfo, groupParams, itemParamsList)
    }

    public void applyReceivableAnticipationFee(Long customerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        applyToCustomerIfPossible(customerId, replicationType, itemParamsList)
        applyToChildAccountIfPossible(customerId, replicationType, itemParamsList)
    }

    private List<Map> buildItemParamsList(Map params) {
        List<Map> itemParamsList = []
        List<String> productFeeTypeList = ["creditCardDetachedDailyFee", "creditCardInstallmentDailyFee", "bankSlipDailyFee"]

        for (String productFeeType : productFeeTypeList) {
            Map itemParams = [:]
            itemParams.valueType = FeeNegotiationValueType.PERCENTAGE
            itemParams.productFeeType = productFeeType
            itemParams.value = Utils.toBigDecimal(params[productFeeType])
            itemParamsList += itemParams
        }

        return itemParamsList
    }

    private void applyToCustomerIfPossible(Long customerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        if (!replicationType.shouldApplyToCustomer()) return

        Map itemParams = itemParamsList.groupBy { it.productFeeType }
        BigDecimal creditCardDetachedDailyFee = itemParams.creditCardDetachedDailyFee.value.first()
        BigDecimal creditCardInstallmentDailyFee = itemParams.creditCardInstallmentDailyFee.value.first()
        BigDecimal bankSlipDailyFee = itemParams.bankSlipDailyFee.value.first()

        CustomerReceivableAnticipationConfig receivableAnticipationConfig = feeAdminService.updateReceivableAnticipationConfigFee(customerId, creditCardDetachedDailyFee, creditCardInstallmentDailyFee, bankSlipDailyFee, CustomerReceivableAnticipationConfigChangeOrigin.MANUAL_CHANGE_TO_DEFAULT_FEE)
        if (receivableAnticipationConfig.hasErrors()) throw new ValidationException("Não foi possível alterar as taxas de antecipação", receivableAnticipationConfig.errors)
    }

    private void applyToChildAccountIfPossible(Long accountOwnerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        if (!replicationType.shouldApplyToChildAccount()) return

        for (Map itemParams : itemParamsList) {
            ChildAccountParameter childAccountParameter = customerReceivableAnticipationConfigParameterService.saveParameter(accountOwnerId, itemParams.productFeeType, itemParams.value)
            if (childAccountParameter.hasErrors()) throw new ValidationException("Não foi possível aplicar as taxas de antecipação para contas filhas", childAccountParameter.errors)

            childAccountParameterBinderService.applyParameterForAllChildAccounts(accountOwnerId, itemParams.productFeeType, CustomerReceivableAnticipationConfig.simpleName)
        }
    }
}
