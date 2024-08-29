package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.dimp.DimpFileType
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.integration.hubspot.enums.HubspotGrowthStatus
import com.asaas.integration.hubspot.enums.UpdateHubspotContactType
import com.asaas.lead.LeadType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.NewRelicUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class AsyncActionService {

    def sessionFactory
    def baseAsyncActionService

    public void saveApplyAnticipationCreditCardPercentageConfig(Long customerId) {
        Map asyncActionData = [customerId: customerId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.APPLY_ANTICIPATION_CREDIT_CARD_PERCENTAGE_CONFIG)
        if (!hasAsyncActionPending) save(AsyncActionType.APPLY_ANTICIPATION_CREDIT_CARD_PERCENTAGE_CONFIG, asyncActionData)
    }

    public List<Map> listPendingApplyAnticipationCreditCardPercentageConfig(Integer max) {
        return listPending(AsyncActionType.APPLY_ANTICIPATION_CREDIT_CARD_PERCENTAGE_CONFIG, max)
    }

    public void saveUpdateCreditCardAnticipableAsAwaitingAnalysis(Long customerId, Map asyncActionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.UPDATE_CREDIT_CARD_ANTICIPABLE_AS_AWAITING_ANALYSIS)
        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_CREDIT_CARD_ANTICIPABLE_AS_AWAITING_ANALYSIS, customerId.toString(), asyncActionData, [:])
    }

    public void saveUpdateBankSlipAnticipableAsAwaitingAnalysis(Long customerId, Map asyncActionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.UPDATE_BANK_SLIP_ANTICIPABLE_AS_AWAITING_ANALYSIS)
        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_BANK_SLIP_ANTICIPABLE_AS_AWAITING_ANALYSIS, customerId.toString(), asyncActionData, [:])
    }

    public void saveUpdatePixAnticipableAsAwaitingAnalysis(Long customerId, Map asyncActionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.UPDATE_PIX_ANTICIPABLE_AS_AWAITING_ANALYSIS)
        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_PIX_ANTICIPABLE_AS_AWAITING_ANALYSIS, customerId.toString(), asyncActionData, [:])
    }

    public void saveSettleReceivableUnitItem(Long receivableUnitItemId) {
        Map asyncActionData = [receivableUnitItemId: receivableUnitItemId]

        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SETTLE_RECEIVABLE_UNIT_ITEM)
        if (!hasAsyncActionPending) save(AsyncActionType.SETTLE_RECEIVABLE_UNIT_ITEM, asyncActionData)
    }

    public List<Map> listPendingSettleReceivableUnitItem(Integer max) {
        return listPending(AsyncActionType.SETTLE_RECEIVABLE_UNIT_ITEM, max)
    }

    public void saveRecalculateCreditCardAnticipationLimitWithPercentage(Long customerId, Boolean nextRecalculationDateWithPriority) {
        Map asyncActionData = [customerId: customerId, nextRecalculationDateWithPriority: nextRecalculationDateWithPriority]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.RECALCULATE_CREDIT_CARD_ANTICIPATION_LIMIT_WITH_PERCENTAGE)
        if (!hasAsyncActionPending) save(AsyncActionType.RECALCULATE_CREDIT_CARD_ANTICIPATION_LIMIT_WITH_PERCENTAGE, asyncActionData)
    }

    public List<Map> listPendingRecalculateCreditCardAnticipationLimitWithPercentage(Integer max) {
        return listPending(AsyncActionType.RECALCULATE_CREDIT_CARD_ANTICIPATION_LIMIT_WITH_PERCENTAGE, max)
    }

    public void saveCreateOrUpdateCercCompany(String cpfCnpj) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([cpfCnpj: cpfCnpj], AsyncActionType.CREATE_OR_UPDATE_CERC_COMPANY)
        if (!hasAsyncActionPending) save(AsyncActionType.CREATE_OR_UPDATE_CERC_COMPANY, [cpfCnpj: cpfCnpj])
    }

    public List<Map> listPendingCreateOrUpdateCercCompany(Integer max) {
        return listPending(AsyncActionType.CREATE_OR_UPDATE_CERC_COMPANY, max)
    }

    public void saveCreateReceivableAnticipationPartnerSettlementItem(ReceivableAnticipationPartnerSettlement partnerSettlement, Map actionData) {
        save(AsyncActionType.CREATE_RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT_ITEM, partnerSettlement.payment.provider.id.toString(), actionData, [:])
    }

    public List<Map> listPendingCreateReceivableAnticipationPartnerSettlementItem(List<String> groupIdList, Integer max) {
        return listPending(AsyncActionType.CREATE_RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT_ITEM, groupIdList, max)
    }

    public void saveProcessDisableBankSlipAnticipation(Long customerId) {
        Map asyncActionData = [customerId: customerId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.PROCESS_DISABLE_BANKSLIP_ANTICIPATION)
        if (!hasAsyncActionPending) save(AsyncActionType.PROCESS_DISABLE_BANKSLIP_ANTICIPATION, asyncActionData)
    }

    public void saveProcessEnableBankSlipAnticipation(Long customerId) {
        Map asyncActionData = [customerId: customerId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.PROCESS_ENABLE_BANKSLIP_ANTICIPATION)
        if (!hasAsyncActionPending) save(AsyncActionType.PROCESS_ENABLE_BANKSLIP_ANTICIPATION, asyncActionData)
    }

    public void saveProcessDisableCreditCardAnticipation(Long customerId) {
        Map asyncActionData = [customerId: customerId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.PROCESS_DISABLE_CREDIT_CARD_ANTICIPATION)
        if (!hasAsyncActionPending) save(AsyncActionType.PROCESS_DISABLE_CREDIT_CARD_ANTICIPATION, asyncActionData)
    }

    public void saveArchiveOldAndCreateNewLead(String newEmail, Long leadDataId) {
        Map asyncActionData = [newEmail: newEmail, leadDataId: leadDataId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.ARCHIVE_OLD_AND_CREATE_NEW_LEAD)
        if (!hasAsyncActionPending) save(AsyncActionType.ARCHIVE_OLD_AND_CREATE_NEW_LEAD, asyncActionData)
    }

    public List<Map> listPendingArchiveOldAndCreateNewLead(Integer max) {
        return listPending(AsyncActionType.ARCHIVE_OLD_AND_CREATE_NEW_LEAD, max)
    }

    public void saveProcessEnableCreditCardAnticipation(Long customerId) {
        Map asyncActionData = [customerId: customerId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.PROCESS_ENABLE_CREDIT_CARD_ANTICIPATION)
        if (!hasAsyncActionPending) save(AsyncActionType.PROCESS_ENABLE_CREDIT_CARD_ANTICIPATION, asyncActionData)
    }

    public void saveChildAccountSegmentAndAccountManagerMigration(Map actionData) {
        save(AsyncActionType.MIGRATE_CHILD_ACCOUNT_SEGMENT_AND_ACCOUNT_MANAGER, actionData)
    }

    public List<Map> listPendingChildAccountSegmentAndAccountManagerMigration() {
        return listPending(AsyncActionType.MIGRATE_CHILD_ACCOUNT_SEGMENT_AND_ACCOUNT_MANAGER, null)
    }

    public void saveAccountOwnerManagerInheritance(Map actionData) {
        save(AsyncActionType.ACCOUNT_OWNER_MANAGER_INHERITANCE, actionData)
    }

    public List<Map> listPendingAccountOwnerManagerInheritance() {
        return listPending(AsyncActionType.ACCOUNT_OWNER_MANAGER_INHERITANCE, null)
    }

    public void saveBusinessGroupAccountManagerReplication(Map actionData) {
        save(AsyncActionType.BUSINESS_GROUP_ACCOUNT_MANAGER_REPLICATION, actionData)
    }

    public List<Map> listPendingBusinessGroupAccountManagerToReplicate() {
        return listPending(AsyncActionType.BUSINESS_GROUP_ACCOUNT_MANAGER_REPLICATION, null)
    }

    public void saveBusinessGroupSegmentReplication(Map actionData) {
        save(AsyncActionType.BUSINESS_GROUP_SEGMENT_REPLICATION, actionData)
    }

    public List<Map> listPendingBusinessGroupSegmentToReplicate() {
        return listPending(AsyncActionType.BUSINESS_GROUP_SEGMENT_REPLICATION, null)
    }

    public void saveCancelFidcContractualEffectGuarantee(Long anticipationId) {
        save(AsyncActionType.CANCEL_FIDC_CONTRACTUAL_EFFECT_GUARANTEE, [anticipationId: anticipationId])
    }

    public List<Map> listPendingCancelFidcContractualEffectGuarantee(Integer max) {
        return listPending(AsyncActionType.CANCEL_FIDC_CONTRACTUAL_EFFECT_GUARANTEE, max)
    }

    public void saveFinishFidcContractualEffectGuarantee(Long anticipationId, Long anticipationItemId) {
        save(AsyncActionType.FINISH_FIDC_CONTRACTUAL_EFFECT_GUARANTEE, [anticipationId: anticipationId, anticipationItemId: anticipationItemId])
    }

    public List<Map> listPendingFinishFidcContractualEffectGuarantee(Integer max) {
        return listPending(AsyncActionType.FINISH_FIDC_CONTRACTUAL_EFFECT_GUARANTEE, max)
    }

    public void saveRefundCustomerCreditCardPaymentsInBatch(Map actionData) {
        save(AsyncActionType.REFUND_CUSTOMER_CREDIT_CARD_PAYMENTS_IN_BATCH, actionData)
    }

    public List<Map> listRefundCustomerCreditCardPaymentsInBatch() {
        return listPending(AsyncActionType.REFUND_CUSTOMER_CREDIT_CARD_PAYMENTS_IN_BATCH, 1)
    }

    public void saveChildAccountExternalAuthorizationRequestConfigReplication(Map actionData) {
        save(AsyncActionType.CHILD_ACCOUNT_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_REPLICATION, actionData)
    }

    public List<Map> listPendingChildAccountExternalAuthorizationRequestConfigReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_REPLICATION, null)
    }

    public List<Map> listPendingDisableHermesAccount() {
        return listPending(AsyncActionType.DISABLE_HERMES_ACCOUNT, 500)
    }

    public List<Map> listPendingActivateHermesAccount() {
        return listPending(AsyncActionType.ACTIVATE_HERMES_ACCOUNT, 500)
    }

    public void saveActiveHermesAccount(Customer customer) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([customerId: customer.id], AsyncActionType.ACTIVATE_HERMES_ACCOUNT)
        if (!hasAsyncActionPending) save(AsyncActionType.ACTIVATE_HERMES_ACCOUNT, [customerId: customer.id])
    }

    public void saveDisableHermesAccount(Customer customer) {
        save(AsyncActionType.DISABLE_HERMES_ACCOUNT, [customerId: customer.id])
    }

    public List<Map> listPendingBlockHermesAccount() {
        return listPending(AsyncActionType.BLOCK_HERMES_ACCOUNT, 500)
    }

    public void saveBlockHermesAccount(Customer customer) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([customerId: customer.id], AsyncActionType.BLOCK_HERMES_ACCOUNT)
        if (!hasAsyncActionPending) save(AsyncActionType.BLOCK_HERMES_ACCOUNT, [customerId: customer.id])
    }

    public void saveChildAccountCustomerFeeReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_FEE_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCustomerFeeReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_FEE_REPLICATION, null)
    }

    public void saveChildAccountCustomerReceivableAnticipationConfigReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_RECEIVABLE_ANTICIPATION_CONFIG_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCustomerReceivableAnticipationConfigReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_RECEIVABLE_ANTICIPATION_CONFIG_REPLICATION, null)
    }

    public void saveChildAccountBankSlipFeeReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_BANK_SLIP_FEE_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountBankSlipFeeReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_BANK_SLIP_FEE_REPLICATION, null)
    }

    public void saveChildAccountPixCreditFeeReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_PIX_CREDIT_FEE_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountPixCreditFeeReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_PIX_CREDIT_FEE_REPLICATION, null)
    }

    public void saveChildAccountCreditCardFeeReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CREDIT_CARD_FEE_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCreditCardFeeReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CREDIT_CARD_FEE_REPLICATION, null)
    }

    public void saveChildAccountPixTransactionCheckoutLimitReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_PIX_TRANSACTION_CHECKOUT_LIMIT_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountPixTransactionCheckoutLimitReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_PIX_TRANSACTION_CHECKOUT_LIMIT_REPLICATION, null)
    }

    public void saveChildAccountCriticalActionConfigReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_CRITICAL_ACTION_CONFIG_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCriticalActionConfigReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_CRITICAL_ACTION_CONFIG_REPLICATION, null)
    }

    public void saveChildAccountCustomerParameterReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_PARAMETER_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCustomerParameterReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_PARAMETER_REPLICATION, null)
    }

    public void saveChildAccountInternalLoanConfigReplication(Long accountOwnerId) {
        save(AsyncActionType.CHILD_ACCOUNT_INTERNAL_LOAN_CONFIG_REPLICATION, [accountOwnerId: accountOwnerId])
    }

    public List<Map> listPendingChildAccountInternalLoanConfigReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_INTERNAL_LOAN_CONFIG_REPLICATION, null)
    }

    public void saveChildAccountCustomerConfigReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_CONFIG_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCustomerConfigReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_CONFIG_REPLICATION, null)
    }

    public void saveChildAccountCustomerFeatureReplication(Long accountOwnerId, String name) {
        save(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_FEATURE_REPLICATION, [accountOwnerId: accountOwnerId, name: name])
    }

    public List<Map> listPendingChildAccountCustomerFeatureReplication() {
        return listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_FEATURE_REPLICATION, null)
    }

    public void saveSendCustomerToAsaasErp(Long customerId) {
        save(AsyncActionType.SEND_CUSTOMER_TO_ASAAS_ERP, [customerId: customerId])
    }

    public List<Map> listPendingSendCustomerToAsaasErp() {
        return listPending(AsyncActionType.SEND_CUSTOMER_TO_ASAAS_ERP, null)
    }

    public List<Map> listPendingAsaasMoneyPixStatusChange() {
        return listPending(AsyncActionType.ASAAS_MONEY_PIX_STATUS_CHANGE, null)
    }

    public List<Map> listPendingAsaasMoneyPaymentCreatedForCustomer() {
        return listPending(AsyncActionType.ASAAS_MONEY_PAYMENT_CREATED_FOR_CUSTOMER, null)
    }

    public List<Map> listPendingAsaasMoneyPaymentStatusChange() {
        return listPending(AsyncActionType.ASAAS_MONEY_PAYMENT_STATUS_CHANGE, null)
    }

    public void saveSendEventToHubspot(Map actionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.SEND_EVENT_TO_HUBSPOT)

        if (!hasAsyncActionPending) save(AsyncActionType.SEND_EVENT_TO_HUBSPOT, actionData)
    }

    public List<Map> listPendingSendEventToHubspot(Integer max) {
        return listPending(AsyncActionType.SEND_EVENT_TO_HUBSPOT, max)
    }

    public void saveBeamerUserCreation(Map actionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.CREATE_BEAMER_USER)

        if (!hasAsyncActionPending) save(AsyncActionType.CREATE_BEAMER_USER, actionData)
    }

    public List<Map> listPendingBeamerUserCreation(Integer max) {
        return listPending(AsyncActionType.CREATE_BEAMER_USER, max)
    }

    public void saveBeamerUserUpdate(Map actionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.UPDATE_BEAMER_USER)

        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_BEAMER_USER, actionData)
    }

    public List<Map> listPendingBeamerUserUpdate(Integer max) {
        return listPending(AsyncActionType.UPDATE_BEAMER_USER, max)
    }

    public void saveCreateHubspotContact(Long customerId, HubspotGrowthStatus growthStatus, LeadType leadType) {
        Map actionData = [:]
        actionData.customerId = customerId
        actionData.growthStatus = growthStatus
        if (leadType) actionData.leadType = leadType
        save(AsyncActionType.CREATE_HUBSPOT_CONTACT, actionData)
    }

    public void saveLeadCreateHubspotContact(String email, HubspotGrowthStatus growthStatus, LeadType leadType) {
        Map actionData = [:]
        actionData.email = email
        actionData.growthStatus = growthStatus
        if (leadType) actionData.leadType = leadType

        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.CREATE_HUBSPOT_CONTACT)
        if (!hasAsyncActionPending) save(AsyncActionType.CREATE_HUBSPOT_CONTACT, actionData)
    }

    public void saveCreateHubspotDealWithContactAssociation(Map params) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(params, AsyncActionType.CREATE_HUBSPOT_DEAL_WITH_CONTACT_ASSOCIATION)
        if (!hasAsyncActionPending) save(AsyncActionType.CREATE_HUBSPOT_DEAL_WITH_CONTACT_ASSOCIATION, params)
    }

    public List<Map> listPendingCreateHubspotDealWithContactAssociation(Integer max) {
        return listPending(AsyncActionType.CREATE_HUBSPOT_DEAL_WITH_CONTACT_ASSOCIATION, max)
    }

    public List<Map> listPendingCreateHubspotContact(Integer max) {
        return listPending(AsyncActionType.CREATE_HUBSPOT_CONTACT, max)
    }

    public void saveUpdateHubspotGrowthStatus(Long customerId, HubspotGrowthStatus growthStatus) {
        Map params = [customerId: customerId, type: UpdateHubspotContactType.GROWTH_STATUS, growthStatus: growthStatus]

        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(params, AsyncActionType.UPDATE_HUBSPOT_CONTACT)

        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_HUBSPOT_CONTACT, params)
    }

    public void saveUnsubscribeHubspotContact(String email) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([email: email], AsyncActionType.UNSUBSCRIBE_HUBSPOT_CONTACT)

        if (!hasAsyncActionPending) save(AsyncActionType.UNSUBSCRIBE_HUBSPOT_CONTACT, [email: email])
    }

    public List<Map> listPendingUnsubscribeHubspotContact(Integer max) {
        return listPending(AsyncActionType.UNSUBSCRIBE_HUBSPOT_CONTACT, max)
    }

    public void saveUpdateHubspotContactStatus(Long customerId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([customerId: customerId, type: UpdateHubspotContactType.STATUS], AsyncActionType.UPDATE_HUBSPOT_CONTACT)

        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_HUBSPOT_CONTACT, [customerId: customerId, type: UpdateHubspotContactType.STATUS])
    }

    public void saveUpdateHubspotCommercialInfo(Long customerId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([customerId: customerId, type: UpdateHubspotContactType.COMMERCIAL_INFO], AsyncActionType.UPDATE_HUBSPOT_CONTACT)

        if (!hasAsyncActionPending) save(AsyncActionType.UPDATE_HUBSPOT_CONTACT, [customerId: customerId, type: UpdateHubspotContactType.COMMERCIAL_INFO])
    }

    public List<Map> listPendingUpdateHubspotContact(Integer max) {
        return listPending(AsyncActionType.UPDATE_HUBSPOT_CONTACT, max)
    }

    public void saveReceivableAnticipationPartnerSettlementBatchAsyncAction(Long userId) {
        save(AsyncActionType.GENERATE_RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT_BATCH, [userId: userId])
    }

    public List<Map> listPendingReceivableAnticipationPartnerSettlementBatch() {
        return listPending(AsyncActionType.GENERATE_RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT_BATCH, null)
    }

    public void saveReprocessJudicialLockForCustomer(Long financialTransactionId, Long customerId, String cpfCnpj) {
        save(AsyncActionType.REPROCESS_JUDICIAL_LOCK_FOR_CUSTOMER, [customerId: customerId, cpfCnpj: cpfCnpj, financialTransactionId: financialTransactionId])
    }

    public List<Map> listPendingReprocessJudicialLockForCustomer() {
        return listPending(AsyncActionType.REPROCESS_JUDICIAL_LOCK_FOR_CUSTOMER, null)
    }

    public void saveCreatePixAddressKey(Customer customer) {
        Boolean hasPendingAsyncAction = hasAsyncActionPendingWithSameParameters([customerId: customer.id], AsyncActionType.CREATE_PIX_ADDRESS_KEY)
        if (!hasPendingAsyncAction) save(AsyncActionType.CREATE_PIX_ADDRESS_KEY, [customerId: customer.id])
    }

    public List<Map> listPendingCreatePixAddressKey() {
        return listPending(AsyncActionType.CREATE_PIX_ADDRESS_KEY, null)
    }

    public List<Map> listPendingHermesPixQrCodeUpdate() {
        return listPending(AsyncActionType.HERMES_PIX_QR_CODE_UPDATE, 500)
    }

    public List<Map> listPendingHermesPixQrCodeDelete() {
        return listPending(AsyncActionType.HERMES_PIX_QR_CODE_DELETE, 500)
    }

    public List<Map> listPendingHermesPixQrCodeRestore() {
        return listPending(AsyncActionType.HERMES_PIX_QR_CODE_RESTORE, 500)
    }

    public void saveHermesPixQrCodeUpdate(Long paymentId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([paymentId: paymentId], AsyncActionType.HERMES_PIX_QR_CODE_UPDATE)
        if (!hasAsyncActionPending) save(AsyncActionType.HERMES_PIX_QR_CODE_UPDATE, [paymentId: paymentId])
    }

    public void saveHermesPixQrCodeDelete(Long paymentId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([paymentId: paymentId], AsyncActionType.HERMES_PIX_QR_CODE_DELETE)
        if (!hasAsyncActionPending) save(AsyncActionType.HERMES_PIX_QR_CODE_DELETE, [paymentId: paymentId])
    }

    public void saveHermesPixQrCodeRestore(Long paymentId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([paymentId: paymentId], AsyncActionType.HERMES_PIX_QR_CODE_RESTORE)
        if (!hasAsyncActionPending) save(AsyncActionType.HERMES_PIX_QR_CODE_RESTORE, [paymentId: paymentId])
    }

    public List<Map> listPendingRefundCustomerCheckoutDailyLimit() {
        return listPending(AsyncActionType.REFUND_CUSTOMER_CHECKOUT_DAILY_LIMIT, 500)
    }

    public void saveRefundCustomerCheckoutDailyLimit(Long customerId, BigDecimal value, Date transactionDate, Long financialTransactionId) {
        save(AsyncActionType.REFUND_CUSTOMER_CHECKOUT_DAILY_LIMIT, [customerId: customerId, value: value, transactionDate: transactionDate.toString(), financialTransactionId: financialTransactionId])
    }

    public void saveRecalculateCustomerDailyBalanceConsolidation(Long balanceConsolidationId) {
        save(AsyncActionType.RECALCULATE_CUSTOMER_DAILY_BALANCE_CONSOLIDATION, [balanceConsolidationId: balanceConsolidationId])
    }

    public List<Map> listPendingRecalculateCustomerDailyBalanceConsolidation(Integer max) {
        return listPending(AsyncActionType.RECALCULATE_CUSTOMER_DAILY_BALANCE_CONSOLIDATION, max)
    }

    public void saveAsaasBankSlipWaitingRegisterConfirmation(Long boletoBatchFileItemId, String barCode, String digitableLine) {
        save(AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_CONFIRMATION, [boletoBatchFileItemId: boletoBatchFileItemId, barCode: barCode, digitableLine: digitableLine])
    }

    public void reprocessAsaasBankSlipCancelledStatusConfirmations(Integer max) {
        List<String> asyncActionTypeList = [
            AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_CONFIRMATION,
            AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_REMOVAL_CONFIRMATION,
            AsyncActionType.ASAAS_BANKSLIP_RECEIPT_CONFIRMATION,
            AsyncActionType.BENEFICIARY_PENDING_REGISTER,
            AsyncActionType.BENEFICIARY_REGISTER_WAITING_CONFIRMATION,
            AsyncActionType.BENEFICIARY_REMOVAL_WAITING_CONFIRMATION
        ].collect { it.toString() }

        String sql = "update async_action set status = :newStatus, last_updated = :newLastUpdated where type in (:typeList) and status = :oldStatus and last_updated >= :lastUpdated limit :limit"

        def query = sessionFactory.currentSession.createSQLQuery(sql)
        query.setString("newStatus", AsyncActionStatus.PENDING.toString())
        query.setTimestamp("newLastUpdated", new Date())
        query.setParameterList("typeList", asyncActionTypeList)
        query.setString("oldStatus", AsyncActionStatus.CANCELLED.toString())
        query.setTimestamp("lastUpdated", CustomDateUtils.sumHours(new Date(), -1))
        query.setInteger("limit", max)
        query.executeUpdate()
    }

    public void saveAsaasBankSlipWaitingRegisterRemovalConfirmation(Long boletoBatchFileItemId, String barCode, String externalIdentifier) {
        save(AsyncActionType.ASAAS_BANKSLIP_WAITING_REGISTER_REMOVAL_CONFIRMATION, [boletoBatchFileItemId: boletoBatchFileItemId, barCode: barCode, externalIdentifier: externalIdentifier])
    }

    @Deprecated
    public void saveAsaasBankSlipReceiptConfirmation(Long paymentId, String nossoNumero) {
        save(AsyncActionType.ASAAS_BANKSLIP_RECEIPT_CONFIRMATION, [paymentId: paymentId, nossoNumero: nossoNumero])
    }

    @Deprecated
    public List<Map> listPendingAsaasBankSlipReceiptConfirmation(Integer max) {
        return listPending(AsyncActionType.ASAAS_BANKSLIP_RECEIPT_CONFIRMATION, max)
    }

    public void saveFinancialTransactionExport(AsyncActionType actionType, Long customerId, String userEmail, Date startDate, Date finishDate) {
        save(actionType, [customerId: customerId, userEmail: userEmail, startDate: startDate.toString(), finishDate: finishDate.toString()])
    }

    public void savePixTransactionExport(Long customerId, String userEmail, Map filters) {
        save(AsyncActionType.PIX_TRANSACTION_EXPORT, [customerId: customerId, userEmail: userEmail, filters: filters])
    }

    public void saveScheduledBillNotification(List<Long> billIdList, Long customerId, String scheduleDate) {
        save(AsyncActionType.SEND_SCHEDULED_BILL_NOTIFICATION, [billIdList: billIdList, customerId: customerId, scheduleDate: scheduleDate])
    }

    public List<Map> listScheduledBillNotification(Integer max) {
        return listPending(AsyncActionType.SEND_SCHEDULED_BILL_NOTIFICATION, max)
    }

    public void saveBillFailuresNotificationForInsufficientBalance(List<Long> billIdList, Long customerId) {
        save(AsyncActionType.SEND_NOTIFICATION_FAILED_BILL_INSUFFICIENT_BALANCE, [billIdList: billIdList, customerId: customerId])
    }

    public List<Map> listBillFailuresNotificationForInsufficientBalance(Integer max) {
        return listPending(AsyncActionType.SEND_NOTIFICATION_FAILED_BILL_INSUFFICIENT_BALANCE, max)
    }

    public void saveDeleteTokenizedCreditCardKremlin(String token, String billingInfoPublicId) {
        save(AsyncActionType.DELETE_TOKENIZED_CREDIT_CARD_KREMLIN, [token: token, billingInfoPublicId: billingInfoPublicId])
    }

    public List<Map> listDeleteTokenizedCreditCardKremlin(Integer max) {
        return listPending(AsyncActionType.DELETE_TOKENIZED_CREDIT_CARD_KREMLIN, max)
    }

    public void savePendingBeneficiaryRegister(Long boletoBankId, Long customerId) {
        Map actionData = [boletoBankId: boletoBankId, customerId: customerId]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.BENEFICIARY_PENDING_REGISTER)
        if (!hasAsyncActionPending) save(AsyncActionType.BENEFICIARY_PENDING_REGISTER, actionData)
    }

    public List<Map> listPendingBeneficiaryRegister(Integer max) {
        return listPending(AsyncActionType.BENEFICIARY_PENDING_REGISTER, max)
    }

    public void saveBeneficiaryRegisterWaitingConfirmation(Map actionData) {
        save(AsyncActionType.BENEFICIARY_REGISTER_WAITING_CONFIRMATION, actionData)
    }

    public void saveBeneficiaryRemovalWaitingConfirmation(Map actionData) {
        save(AsyncActionType.BENEFICIARY_REMOVAL_WAITING_CONFIRMATION, actionData)
    }

    public List<Map> listPendingBeneficiaryRegisterWaitingConfirmation(Integer max) {
        return listPending(AsyncActionType.BENEFICIARY_REGISTER_WAITING_CONFIRMATION, max)
    }

    public List<Map> listPendingBeneficiaryRemovalWaitingConfirmation(Integer max) {
        return listPending(AsyncActionType.BENEFICIARY_REMOVAL_WAITING_CONFIRMATION, max)
    }

    public void saveSendReferralAwardInvitation(Long customerId, String customerEmail) {
        save(AsyncActionType.SEND_REFERRAL_AWARD_INVITATION, [customerId: customerId, customerEmail: customerEmail])
    }

    public List<Map> listSendReferralAwardInvitation(Integer max) {
        return listPending(AsyncActionType.SEND_REFERRAL_AWARD_INVITATION, max)
    }

    public void saveUnblockAsaasCreditCardIfNecessary(Map asyncActionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.UNBLOCK_ASAAS_CREDIT_CARD)
        if (!hasAsyncActionPending) save(AsyncActionType.UNBLOCK_ASAAS_CREDIT_CARD, asyncActionData)
    }

    public List<Map> listUnblockAsaasCreditCardIfNecessary(Integer max) {
        return listPending(AsyncActionType.UNBLOCK_ASAAS_CREDIT_CARD, max)
    }

    public void saveCancelAllAsaasCards(Customer customer) {
        save(AsyncActionType.CANCEL_ALL_ASAAS_CARDS, [customerId: customer.id])
    }

    public List<Map> listCancelAllAsaasCards() {
        return listPending(AsyncActionType.CANCEL_ALL_ASAAS_CARDS, 500)
    }

    public void savePayBill(Long billId) {
        save(AsyncActionType.PAY_BILL, [billId: billId])
    }

    public List<Map> listPayBill(Integer max) {
        return listPending(AsyncActionType.PAY_BILL, max)
    }

    public void saveCancelCustomerConfirmedFraudBill(Customer customer) {
        save(AsyncActionType.CANCEL_CUSTOMER_CONFIRMED_FRAUD_BILL, [customerId: customer.id])
    }

    public List<Map> listCancelCustomerConfirmedFraudBill(Integer max) {
        return listPending(AsyncActionType.CANCEL_CUSTOMER_CONFIRMED_FRAUD_BILL, max)
    }

    public void saveFacebookSendEvent(Map asyncActionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.FACEBOOK_SEND_EVENT)
        if (!hasAsyncActionPending) save(AsyncActionType.FACEBOOK_SEND_EVENT, asyncActionData)
    }

    public List<Map> listPendingFacebookSendEvent(Integer max) {
        return listPending(AsyncActionType.FACEBOOK_SEND_EVENT, max)
    }

    public void saveReprocessDimpBatchFile(Date processingDate, DimpFileType fileType, Long dimpBatchFileId) {
        save(AsyncActionType.REPROCESS_DIMP_BATCH_FILE, [processingDate: processingDate, fileType: fileType, dimpBatchFileId: dimpBatchFileId])
    }

    public List<Map> listReprocessDimpBatchFile(Integer max) {
        return listPending(AsyncActionType.REPROCESS_DIMP_BATCH_FILE, max)
    }

    public void saveSetPaymentsFromCustomerWithOverdueSettlementsAsNotAnticipable(Long customerId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([customerId: customerId], AsyncActionType.SET_PAYMENTS_FROM_CUSTOMER_WITH_OVERDUE_SETTLEMENTS_AS_NOT_ANTICIPABLE)
        if (!hasAsyncActionPending) save(AsyncActionType.SET_PAYMENTS_FROM_CUSTOMER_WITH_OVERDUE_SETTLEMENTS_AS_NOT_ANTICIPABLE, [customerId: customerId])
    }

    public void saveSetPaymentAnticipableWhenCustomerDebitOverdueSettlements(Long customerId) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters([customerId: customerId], AsyncActionType.SET_PAYMENTS_ANTICIPABLE_WHEN_CUSTOMER_DEBIT_OVERDUE_SETTLEMENTS)
        if (!hasAsyncActionPending) save(AsyncActionType.SET_PAYMENTS_ANTICIPABLE_WHEN_CUSTOMER_DEBIT_OVERDUE_SETTLEMENTS, [customerId: customerId])
    }

    public void saveSendMailBurstLimitExceeded(Long customerId, Integer concurrentRequestLimit, Date concurrentRequestExceededDate) {
        final Map asyncActionData = [customerId: customerId, concurrentRequestLimit: concurrentRequestLimit, concurrentRequestExceededDate: CustomDateUtils.fromDate(concurrentRequestExceededDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)]
        save(AsyncActionType.SEND_MAIL_API_BURST_LIMIT_EXCEEDED, customerId.toString(), asyncActionData, [allowDuplicatePendingWithSameParameters: true])
    }

    public void saveSetBillPaymentBatchFileAsPaid(Long billPaymentBatchFileId) {
        Map asyncActionData = [billPaymentBatchFileId: billPaymentBatchFileId]
        save(AsyncActionType.SET_BILL_PAYMENT_BATCH_FILE_AS_PAID, billPaymentBatchFileId.toString(), asyncActionData, [:])
    }


    public void saveCustomerAccountBlockListAnticipationOverdue(String cpfCnpj) {
        Map asyncActionData = [cpfCnpj: cpfCnpj]
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SAVE_CUSTOMER_ACCOUNT_BLOCK_LIST_ANTICIPATION_OVERDUE)
        if (!hasAsyncActionPending) save(AsyncActionType.SAVE_CUSTOMER_ACCOUNT_BLOCK_LIST_ANTICIPATION_OVERDUE, asyncActionData)
    }

    public void deleteAllPendingSendMailBurstLimitExceeded(String groupId) {
        final Map params = [groupId: groupId, type: AsyncActionType.SEND_MAIL_API_BURST_LIMIT_EXCEEDED, status: AsyncActionStatus.PENDING]
        AsyncAction.executeUpdate("delete from AsyncAction aa where aa.groupId = :groupId and type = :type and aa.status = :status", params)
    }

    public void delete(Long id) {
        AsyncAction.executeUpdate("delete AsyncAction aa where aa.id = ?", [id])
    }

    public void deleteList(List<Long> idList) {
        final Integer maxItemsPerDelete = 1000
        if (!idList) return

        for (List<Long> idToDeleteList : (idList.collate(maxItemsPerDelete))) {
            AsyncAction.executeUpdate("delete AsyncAction aa where aa.id in (:idList)", [idList: idToDeleteList])
        }
    }

    public void setAsDone(Long id) {
        AsyncAction asyncAction = AsyncAction.get(id)
        asyncAction.status = AsyncActionStatus.DONE
        asyncAction.save(failOnError: true)
    }

    public AsyncAction sendToReprocessIfPossible(Long id) {
        AsyncAction asyncAction = AsyncAction.get(id)

        Map actionData = asyncAction.getDataAsMap()
        actionData.attempt = actionData.attempt ? actionData.attempt + 1 : 1
        asyncAction.actionData = AsyncActionDataBuilder.parseToJsonString(actionData)

        Boolean exceededMaxAttempts = actionData.attempt >= asyncAction.type.getMaxAttempts()
        if (exceededMaxAttempts) {
            return setAsCancelled(asyncAction)
        }

        return setAsPending(asyncAction)
    }

    public AsyncAction setAsCancelled(Long id) {
        AsyncAction asyncAction = AsyncAction.get(id)
        return setAsCancelled(asyncAction)
    }

    public AsyncAction setAsCancelled(AsyncAction asyncAction) {
        asyncAction.status = AsyncActionStatus.CANCELLED
        return asyncAction.save(failOnError: true)
    }

    public void setAsError(Long id) {
        AsyncAction asyncAction = AsyncAction.get(id)
        asyncAction.status = AsyncActionStatus.ERROR
        asyncAction.save(failOnError: true, flush: true)
    }

    public void setAsErrorWithNewTransaction(Long id) {
        AsyncAction.withNewTransaction {
            AsyncAction asyncAction = AsyncAction.get(id)
            asyncAction.status = AsyncActionStatus.ERROR
            asyncAction.save(failOnError: true)
        }
    }

    public void setListAsError(List<Long> idList) {
        final Integer maxItemsPerUpdate = 1000
        if (!idList) return

        for (List<Long> idToUpdateList : (idList.collate(maxItemsPerUpdate))) {
            AsyncAction.executeUpdate("update AsyncAction set version = version + 1, lastUpdated = :now, status = 'ERROR' where id in (:idList)",
                [now: new Date(), idList: idToUpdateList])
        }
    }

    public AsyncAction setAsPending(AsyncAction asyncAction) {
        asyncAction.status = AsyncActionStatus.PENDING
        return asyncAction.save(failOnError: true)
    }

    public void save(AsyncActionType type, Map actionData) {
        save(type, null, actionData, [:])
    }

    public void save(AsyncActionType type, String groupId, Map actionData, Map options) {
        AsyncAction validatedAsyncAction = validateSave(actionData, type, options)
        if (validatedAsyncAction.hasErrors()) throw new ValidationException("Falha ao salvar processamento assíncrono do tipo [${type}] com os dados ${actionData}", validatedAsyncAction.errors)

        NewRelicUtils.recordMetric("Custom/AsyncActionService/save/${type}", 1)

        AsyncAction asyncAction = new AsyncAction()
        asyncAction.type = type
        asyncAction.groupId = groupId
        baseAsyncActionService.save(asyncAction, actionData)
    }

    public Boolean hasAsyncActionPendingWithSameParameters(Map actionData, AsyncActionType asyncActionType) {
        String actionDataJson = AsyncActionDataBuilder.parseToJsonString(actionData)
        String actionDataHash = AsyncActionDataBuilder.buildHash(actionDataJson)

        return AsyncAction.query([exists: true, actionDataHash: actionDataHash, status: AsyncActionStatus.PENDING, type: asyncActionType]).get().asBoolean()
    }

    public Map getPending(AsyncActionType type) {
        AsyncAction asyncAction = AsyncAction.oldestPending([type: type]).get()
        return asyncAction?.getDataAsMap()
    }

    public List<Map> listPending(AsyncActionType type, Integer max) {
        return listPending(type, null, max)
    }

    public List<Map> listPending(AsyncActionType type, List<String> groupIdList, Integer max) {
        Map queryParams = [:]
        queryParams.type = type

        if (groupIdList) queryParams."groupId[in]" = groupIdList

        List<AsyncAction> asyncActionList = AsyncAction.oldestPending(queryParams).list(max: max, readOnly: true)
        return asyncActionList.collect { it.getDataAsMap() }
    }

    public void saveBeamerAdvancedPlanSubscriptionEnabled(Map actionData) {
        Boolean hasAsyncActionPending = hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.CREATE_BEAMER_FOR_ADVANCED_PLAN_SUBSCRIPTION_ENABLED)
        if (!hasAsyncActionPending) save(AsyncActionType.CREATE_BEAMER_FOR_ADVANCED_PLAN_SUBSCRIPTION_ENABLED, actionData)
    }

    public List<Map> listPendingBeamerAdvancedPlanSubscriptionEnabled(Integer max) {
        return listPending(AsyncActionType.CREATE_BEAMER_FOR_ADVANCED_PLAN_SUBSCRIPTION_ENABLED, max)
    }

    public List<Map> listPendingSendInstantTextMessageInvalidMessageRecipientNotification(Integer max) {
        return listPending(AsyncActionType.SEND_INSTANT_TEXT_MESSAGE_INVALID_MESSAGE_RECIPIENT_NOTIFICATION, max)
    }

    private AsyncAction validateSave(Map actionData, AsyncActionType asyncActionType, Map options) {
        AsyncAction validatedAsyncAction = new AsyncAction()

        Boolean allowDuplicatePendingWithSameParameters = options.allowDuplicatePendingWithSameParameters ?: false

        if (!allowDuplicatePendingWithSameParameters && hasAsyncActionPendingWithSameParameters(actionData, asyncActionType)) {
            DomainUtils.addErrorWithErrorCode(validatedAsyncAction, "asyncAction.validation.pendingActionDuplicated",  "Já existe um registro de processamento assíncrono pendente com o mesmo parâmetro e mesmo tipo")
        }

        return validatedAsyncAction
    }
}
