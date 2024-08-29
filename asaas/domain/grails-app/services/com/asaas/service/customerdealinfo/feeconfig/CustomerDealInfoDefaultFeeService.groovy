package com.asaas.service.customerdealinfo.feeconfig

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationRequestStatus
import com.asaas.feenegotiation.FeeNegotiationValueType
import com.asaas.feenegotiation.adapter.FeeConfigGroupAdapter
import com.asaas.feenegotiation.adapter.FeeConfigGroupItemAdapter
import com.asaas.feenegotiation.adapter.SaveFeeNegotiationRequestAdapter
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerDealInfoDefaultFeeService {

    def feeNegotiationRequestService

    public void applyDefaultFeeToNotNegotiatedProducts(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        BusinessValidation businessValidation = validateApplyDefaultFee(customerDealInfoId, replicationType)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        List<FeeNegotiationProduct> notNegotiatedProductList = listNotNegotiatedProduct(customerDealInfoId, replicationType)
        applyDefaultFee(notNegotiatedProductList, customerDealInfoId, replicationType)
    }

    private void applyDefaultFee(List<FeeNegotiationProduct> productList, Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        for (FeeNegotiationProduct product : productList) {
            switch (product) {
                case FeeNegotiationProduct.BANK_SLIP:
                    applyBankSlipFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.RECEIVABLE_ANTICIPATION:
                    applyReceivableAnticipationFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.PIX_CREDIT:
                    applyPixCreditFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.CREDIT_CARD:
                    applyCreditCardFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION:
                    applyPaymentMessagingNotificationFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.WHATSAPP_NOTIFICATION:
                    applyWhatsappNotificationFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.PIX_DEBIT:
                    applyPixDebitFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER:
                    applyChildAccountKnownYourCustomerFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.SERVICE_INVOICE:
                    applyServiceInvoiceFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.CREDIT_BUREAU_REPORT:
                    applyCreditBureauReportFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.TRANSFER:
                    applyTransferFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.DUNNING_CREDIT_BUREAU:
                    applyDunningCreditBureauFeeConfig(customerDealInfoId, replicationType)
                    break
                case FeeNegotiationProduct.PHONE_CALL_NOTIFICATION:
                    applyPhoneCallNotificationFeeConfig(customerDealInfoId, replicationType)
                    break
                default:
                    throw new NotImplementedException("Aplicação da taxa padrão para o produto [${product}] não implementada.")
            }
        }

        FeeNegotiationRequest request = FeeNegotiationRequest.query([customerDealInfoId: customerDealInfoId, status: FeeNegotiationRequestStatus.AWAITING_APPROVAL]).get()
        if (!request) return

        feeNegotiationRequestService.approveAutomaticallyForDefaultFeeApplication(request)
    }

    private BusinessValidation validateApplyDefaultFee(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, id: customerDealInfoId]).get().asBoolean()
        if (!hasCustomerDealInfo) {
            businessValidation.addError("customerDealInfo.notFound")
            return businessValidation
        }

        if (!replicationType) {
            businessValidation.addError("customerDealInfo.validateApplyDefaultFee.replicationTypeRequired")
            return businessValidation
        }

        if (replicationType.isAccountOwnerAndChildAccount()) {
            businessValidation.addError("customerDealInfo.validateApplyDefaultFee.isAccountOwnerAndChildAccount")
            return businessValidation
        }

        Boolean hasNegotiatedProduct = CustomerDealInfoFeeConfigGroupRepository.query([customerDealInfoId: customerDealInfoId]).get().asBoolean()
        if (!hasNegotiatedProduct) {
            businessValidation.addError("customerDealInfo.validateApplyDefaultFee.noNegotiatedProduct")
            return businessValidation
        }

        Boolean hasFeeNegotiationRequestAwaitingApproval = FeeNegotiationRequest.query([exists: true, customerDealInfoId: customerDealInfoId, status: FeeNegotiationRequestStatus.AWAITING_APPROVAL]).get().asBoolean()
        if (hasFeeNegotiationRequestAwaitingApproval) {
            businessValidation.addError("customerDealInfo.validateApplyDefaultFee.hasFeeNegotiationRequestAwaitingApproval")
            return businessValidation
        }

        List<FeeNegotiationProduct> notNegotiatedProductList = listNotNegotiatedProduct(customerDealInfoId, replicationType)
        if (replicationType.shouldApplyToChildAccount()) notNegotiatedProductList.removeAll(FeeNegotiationProduct.listOnlyReplicableToCustomer())

        if (!notNegotiatedProductList) {
            businessValidation.addError("customerDealInfo.validateApplyDefaultFee.allProductsAreNegotiated")
            return businessValidation
        }

        return businessValidation
    }

    private List<FeeNegotiationProduct> listNotNegotiatedProduct(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        Map search = [:]
        search.customerDealInfoId = customerDealInfoId

        if (replicationType.shouldApplyToChildAccount()) {
            search."replicationType[in]" = [FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT, FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC, FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT]
        } else {
            search."replicationType[in]" = [FeeNegotiationReplicationType.CUSTOMER, FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT]
        }

        List<FeeNegotiationProduct> negotiatedProductList = CustomerDealInfoFeeConfigGroupRepository.query(search).column("product").list()

        return FeeNegotiationProduct.values() - negotiatedProductList
    }

    private void applyBankSlipFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "defaultValue", BankSlipFee.DEFAULT_BANK_SLIP_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.BANK_SLIP, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyReceivableAnticipationFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.PERCENTAGE, "creditCardDetachedDailyFee", CustomerReceivableAnticipationConfig.CREDIT_CARD_DETACHED_DAILY_FEE)
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.PERCENTAGE, "creditCardInstallmentDailyFee", CustomerReceivableAnticipationConfig.CREDIT_CARD_INSTALLMENT_DAILY_FEE)
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.PERCENTAGE, "bankSlipDailyFee", CustomerReceivableAnticipationConfig.BANK_SLIP_DAILY_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.RECEIVABLE_ANTICIPATION, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyPixCreditFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", PixCreditFee.DEFAULT_PIX_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.PIX_CREDIT, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyCreditCardFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FIXED_FEE)
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.PERCENTAGE, "upfrontFee", CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FEE)
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.PERCENTAGE, "upToSixInstallmentsFee", CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE)
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.PERCENTAGE, "upToTwelveInstallmentsFee", CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.CREDIT_CARD, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyPaymentMessagingNotificationFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.PAYMENT_MESSAGING_NOTIFICATION_FEE_VALUE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.PAYMENT_MESSAGING_NOTIFICATION, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyWhatsappNotificationFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.WHATSAPP_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.WHATSAPP_NOTIFICATION, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyPixDebitFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.PIX_DEBIT_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.PIX_DEBIT, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyChildAccountKnownYourCustomerFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        if (replicationType.shouldApplyToChildAccount()) return

        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyServiceInvoiceFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.SERVICE_INVOICE_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.SERVICE_INVOICE, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyCreditBureauReportFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "naturalPersonFee", CustomerFee.CREDIT_BUREAU_REPORT_NATURAL_PERSON_FEE)
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "legalPersonFee", CustomerFee.CREDIT_BUREAU_REPORT_LEGAL_PERSON_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.CREDIT_BUREAU_REPORT, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyTransferFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.CREDIT_TRANSFER_FEE_VALUE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.TRANSFER, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyDunningCreditBureauFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.DUNNING_CREDIT_BUREAU, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }

    private void applyPhoneCallNotificationFeeConfig(Long customerDealInfoId, FeeNegotiationReplicationType replicationType) {
        List<FeeConfigGroupItemAdapter> itemAdapterList = []
        itemAdapterList += new FeeConfigGroupItemAdapter(FeeNegotiationValueType.FIXED, "fixedFee", CustomerFee.PHONE_CALL_NOTIFICATION_FEE)

        FeeConfigGroupAdapter groupAdapter = new FeeConfigGroupAdapter(customerDealInfoId, FeeNegotiationProduct.PHONE_CALL_NOTIFICATION, replicationType)
        SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter = new SaveFeeNegotiationRequestAdapter(groupAdapter, itemAdapterList)

        feeNegotiationRequestService.saveNegotiationRequest(feeNegotiationRequestAdapter)
    }
}
