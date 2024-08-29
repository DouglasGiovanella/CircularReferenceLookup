package com.asaas.service.feenegotiation

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.feenegotiation.FeeNegotiationRequestGroup
import com.asaas.domain.feenegotiation.FeeNegotiationRequestItem
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class FeeNegotiationAsyncActionService {

    def asyncActionService
    def customerDealInfoApplyFeeConfigService
    def customerDealInfoBankSlipFeeConfigService
    def customerDealInfoCreditCardFeeConfigService
    def customerDealInfoFeeConfigService
    def customerDealInfoPixCreditFeeConfigService
    def customerDealInfoReceivableAnticipationFeeConfigService

    public void apply() {
        Map asyncActionData = asyncActionService.getPending(AsyncActionType.APPLY_FEE_NEGOTIATION)
        if (!asyncActionData) return

        Utils.withNewTransactionAndRollbackOnError({
            Long feeNegotiationRequestId = Long.valueOf(asyncActionData.feeNegotiationRequestId)
            FeeNegotiationRequest request = FeeNegotiationRequest.read(feeNegotiationRequestId)
            applyFeeConfig(request)

            asyncActionService.delete(asyncActionData.asyncActionId)
        }, [logErrorMessage: "FeeNegotiationAsyncActionService.apply >> Erro ao aplicar negociação de taxa. AsyncActionId: [${asyncActionData.asyncActionId}]",
            onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
    }

    private void applyFeeConfig(FeeNegotiationRequest request) {
        List<Map> groupParamsList = FeeNegotiationRequestGroup.query([columnList: ["id", "product", "replicationType"], feeNegotiationRequest: request]).list()
        for (Map groupParams : groupParamsList) {
            List<Map> itemParamsList = FeeNegotiationRequestItem.query([columnList: ["productFeeType", "value", "valueType"], feeNegotiationRequestGroupId: groupParams.id]).list()

            groupParams.customerDealInfo = request.customerDealInfo
            groupParams.remove("id")

            CustomerDealInfoFeeConfigGroup feeConfigGroup = customerDealInfoFeeConfigService.saveOrUpdateFeeConfigGroupItem(groupParams, itemParamsList)
            if (groupParams.product == FeeNegotiationProduct.PIX_CREDIT) {
                customerDealInfoPixCreditFeeConfigService.deleteOldConfigIfChangedValueType(feeConfigGroup, itemParamsList)
            }

            applyProductFeeConfig(groupParams.product, request.customerDealInfo.customer.id, groupParams.replicationType, itemParamsList)
        }
    }

    private void applyProductFeeConfig(FeeNegotiationProduct product, Long customerId, FeeNegotiationReplicationType replicationType, List<Map> itemParamsList) {
        switch (product) {
            case FeeNegotiationProduct.BANK_SLIP:
                customerDealInfoBankSlipFeeConfigService.applyBankSlipFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.CREDIT_CARD:
                customerDealInfoCreditCardFeeConfigService.applyCreditCardFeeConfig(customerId, replicationType, itemParamsList)
                break
            case FeeNegotiationProduct.PIX_CREDIT:
                customerDealInfoPixCreditFeeConfigService.applyPixCreditFee(customerId, replicationType, itemParamsList)
                break
            case FeeNegotiationProduct.RECEIVABLE_ANTICIPATION:
                customerDealInfoReceivableAnticipationFeeConfigService.applyReceivableAnticipationFee(customerId, replicationType, itemParamsList)
                break
            case FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION:
                customerDealInfoApplyFeeConfigService.applyPaymentMessagingNotificationFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.WHATSAPP_NOTIFICATION:
                customerDealInfoApplyFeeConfigService.applyWhatsappNotificationFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.PIX_DEBIT:
                customerDealInfoApplyFeeConfigService.applyPixDebitFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER:
                customerDealInfoApplyFeeConfigService.applyChildAccountKnownYourCustomerFee(customerId, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.SERVICE_INVOICE:
                customerDealInfoApplyFeeConfigService.applyServiceInvoiceFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.CREDIT_BUREAU_REPORT:
                customerDealInfoApplyFeeConfigService.applyCreditBureauReportFee(customerId, replicationType, itemParamsList)
                break
            case FeeNegotiationProduct.TRANSFER:
                customerDealInfoApplyFeeConfigService.applyTransferFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.DUNNING_CREDIT_BUREAU:
                customerDealInfoApplyFeeConfigService.applyDunningCreditBureauFee(customerId, replicationType, itemParamsList[0].value)
                break
            case FeeNegotiationProduct.PHONE_CALL_NOTIFICATION:
                customerDealInfoApplyFeeConfigService.applyPhoneCallNotificationFee(customerId, replicationType, itemParamsList[0].value)
                break
            default:
                throw new NotImplementedException("Produto não implementado")
        }
    }
}
