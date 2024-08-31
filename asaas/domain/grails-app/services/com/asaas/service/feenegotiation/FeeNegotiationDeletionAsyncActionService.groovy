package com.asaas.service.feenegotiation

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.pix.PixCreditFeeType
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang3.NotImplementedException

@Transactional
class FeeNegotiationDeletionAsyncActionService {

    def asyncActionService
    def creditCardFeeConfigService
    def feeAdminService

    public void process() {
        final Integer maxItemsPerCycle = 50
        List<Map> asyncActionInfoList = asyncActionService.listPending(AsyncActionType.APPLY_DEFAULT_FEE_CONFIG_FOR_NEGOTIATION_DELETION, maxItemsPerCycle)

        for (Map asyncActionInfo : asyncActionInfoList) {
            Utils.withNewTransactionAndRollbackOnError({
                CustomerDealInfoFeeConfigGroup customerDealInfoFeeConfigGroup = CustomerDealInfoFeeConfigGroupRepository.query([id: asyncActionInfo.customerDealInfoFeeConfigGroupId, deletedOnly: true]).readOnly().get()
                Long customerId = customerDealInfoFeeConfigGroup.customerDealInfo.customer.id
                FeeNegotiationReplicationType replicationType = customerDealInfoFeeConfigGroup.replicationType

                if (replicationType.shouldApplyToCustomer()) {
                    apply(customerId, customerDealInfoFeeConfigGroup.product)
                }

                if (replicationType.shouldApplyToChildAccount()) {
                    asyncActionService.save(AsyncActionType.APPLY_DEFAULT_FEE_CONFIG_TO_CHILD_ACCOUNTS_FOR_NEGOTIATION_DELETION, [product: customerDealInfoFeeConfigGroup.product, accountOwnerId: customerId])
                }

                asyncActionService.delete(asyncActionInfo.asyncActionId)
            }, [logErrorMessage: "FeeNegotiationDeletionAsyncActionService.process >> Erro ao remover negociação de taxa. AsyncActionId: [${asyncActionInfo.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionInfo.asyncActionId) }])
        }
    }

    public void apply(Long customerId, FeeNegotiationProduct product) {
        switch (product) {
            case FeeNegotiationProduct.BANK_SLIP:
                feeAdminService.updateBankSlipFee(customerId, BankSlipFee.DEFAULT_BANK_SLIP_FEE, null, null, false)
                break
            case FeeNegotiationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER:
                feeAdminService.updateFee(customerId, [childAccountKnownYourCustomerFee: CustomerFee.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_FEE], false)
                break
            case FeeNegotiationProduct.CREDIT_BUREAU_REPORT:
                feeAdminService.updateFee(customerId, [creditBureauReportNaturalPersonFee: CustomerFee.CREDIT_BUREAU_REPORT_NATURAL_PERSON_FEE, creditBureauReportLegalPersonFee: CustomerFee.CREDIT_BUREAU_REPORT_LEGAL_PERSON_FEE], false)
                break
            case FeeNegotiationProduct.CREDIT_CARD:
                feeAdminService.updateCreditCardFee(customerId, creditCardFeeConfigService.buildFeeConfigWithoutDiscount(), false)
                break
            case FeeNegotiationProduct.DUNNING_CREDIT_BUREAU:
                feeAdminService.updateFee(customerId, [dunningCreditBureauFeeValue: CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE], false)
                break
            case FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION:
                feeAdminService.updateFee(customerId, [paymentMessagingNotificationFeeValue: CustomerFee.PAYMENT_MESSAGING_NOTIFICATION_FEE_VALUE], false)
                break
            case FeeNegotiationProduct.PHONE_CALL_NOTIFICATION:
                feeAdminService.updateFee(customerId, [phoneCallNotificationFee: CustomerFee.PHONE_CALL_NOTIFICATION_FEE], false)
                break
            case FeeNegotiationProduct.PIX_DEBIT:
                feeAdminService.updateFee(customerId, [pixDebitFee: CustomerFee.PIX_DEBIT_FEE], false)
                break
            case FeeNegotiationProduct.PIX_CREDIT:
                feeAdminService.updatePixCreditFee(customerId, PixCreditFeeType.FIXED, [fixedFee: PixCreditFee.DEFAULT_PIX_FEE], false)
                break
            case FeeNegotiationProduct.RECEIVABLE_ANTICIPATION:
                feeAdminService.updateReceivableAnticipationConfigFee(customerId, null, null, null, CustomerReceivableAnticipationConfigChangeOrigin.MANUAL_CHANGE_TO_DEFAULT_FEE)
                break
            case FeeNegotiationProduct.SERVICE_INVOICE:
                feeAdminService.updateFee(customerId, [invoiceValue: CustomerFee.SERVICE_INVOICE_FEE], false)
                break
            case FeeNegotiationProduct.TRANSFER:
                feeAdminService.updateFee(customerId, [transferValue: CustomerFee.CREDIT_TRANSFER_FEE_VALUE], false)
                break
            case FeeNegotiationProduct.WHATSAPP_NOTIFICATION:
                feeAdminService.updateFee(customerId, [whatsappNotificationFee: CustomerFee.WHATSAPP_FEE], false)
                break
            default:
                throw new NotImplementedException("Produto não implementado para deleção de negociação de taxas: ${product}")
        }
    }
}
