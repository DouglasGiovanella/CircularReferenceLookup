package com.asaas.service.criticalaction

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.pix.PixTransaction
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CriticalActionNotificationService {

    def smsSenderService

    public void sendAuthorizationTokenSMS(Customer customer, CriticalActionGroup group, String authorizationMessage) {
        sendAuthorizationTokenSMS(customer, group, authorizationMessage, null)
    }

    public void sendAuthorizationTokenSMS(Customer customer, CriticalActionGroup group, String authorizationMessage, Map params) {
        if (!authorizationMessage) {
            authorizationMessage = buildAuthorizationMessage(customer, group, params)
        }

        String smsText = "${authorizationMessage} ${group.getDecryptedAuthorizationToken()}"

        if (AsaasEnvironment.isDevelopment()) AsaasLogger.info("CriticalActionAuthorizationService.sendAuthorizationTokenSMS >> ${smsText}")

        smsSenderService.send(smsText, group.authorizationDevice.phoneNumber, true, [isSecret: true])
    }

    public void resendAuthorizationTokenSMS(Customer customer, Long groupId) {
        resendAuthorizationTokenSMS(customer, groupId, null)
    }

    public void resendAuthorizationTokenSMS(Customer customer, Long groupId, Map params) {
        CriticalActionGroup group = CriticalActionGroup.find(customer, groupId)
        sendAuthorizationTokenSMS(customer, group, null, params)
    }

    public BigDecimal buildCheckoutValue(List<CriticalAction> checkoutAsynchronousCriticalActionList) {
        BigDecimal asaasCardRechargeTotalValue = checkoutAsynchronousCriticalActionList.findAll { it.asaasCardRecharge }.collect { BigDecimalUtils.abs(it.asaasCardRecharge.value) }.sum() ?: 0
        BigDecimal billTotalValue = checkoutAsynchronousCriticalActionList.findAll { it.bill }.collect { BigDecimalUtils.abs(it.bill.value) }.sum() ?: 0
        BigDecimal internalTransferTotalValue = checkoutAsynchronousCriticalActionList.findAll { it.internalTransfer }.collect { BigDecimalUtils.abs(it.internalTransfer.value) }.sum() ?: 0
        BigDecimal mobilePhoneRechargeTotalValue = checkoutAsynchronousCriticalActionList.findAll { it.mobilePhoneRecharge }.collect { BigDecimalUtils.abs(it.mobilePhoneRecharge.value) }.sum() ?: 0
        BigDecimal pixTransactionTotalValue = checkoutAsynchronousCriticalActionList.findAll { it.pixTransaction }.collect { BigDecimalUtils.abs(it.pixTransaction.value) }.sum() ?: 0
        BigDecimal transferTotalValue = checkoutAsynchronousCriticalActionList.findAll { it.transfer }.collect { BigDecimalUtils.abs(it.transfer.value) }.sum() ?: 0

        return asaasCardRechargeTotalValue + billTotalValue + internalTransferTotalValue + mobilePhoneRechargeTotalValue + pixTransactionTotalValue + transferTotalValue
    }

    public String buildSynchronousCriticalActionAuthorizationMessage(CriticalActionType type, Map params) {
        List<String> authorizationMessageParams = []
        if (type.isUserInsert() || type.isUserUpdate()) {
            authorizationMessageParams.add(params.username)
            authorizationMessageParams.add(Utils.getMessageProperty("Role." + params.authority))
        }

        return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.${type}", authorizationMessageParams)
    }

    private String buildAuthorizationMessage(Customer customer, CriticalActionGroup group, Map params) {
        List<CriticalAction> criticalActionList = CriticalAction.query([customer: customer, group: group, includeSynchronous: true]).list(readOnly: true)

        if (criticalActionList.size() == 1) {
            return buildDetailedAuthorizationMessage(criticalActionList.first(), params)
        }

        List<CriticalAction> checkoutAsynchronousCriticalActionList = criticalActionList.findAll { it.type.isCheckoutTransaction() && !it.synchronous }
        if (checkoutAsynchronousCriticalActionList) {
            return buildGroupedCheckoutAsynchronousAuthorizationMessage(checkoutAsynchronousCriticalActionList, criticalActionList.size())
        }

        return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.default", [criticalActionList.size(), CustomDateUtils.formatDateTime(group.dateCreated)])
    }

    private String buildGroupedCheckoutAsynchronousAuthorizationMessage(List<CriticalAction> checkoutAsynchronousCriticalActionList, Integer criticalActionCount) {
        BigDecimal totalValue = buildCheckoutValue(checkoutAsynchronousCriticalActionList)

        if (criticalActionCount != checkoutAsynchronousCriticalActionList.size()) {
            return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.groupedCheckoutWithCriticalActionCount", [criticalActionCount, checkoutAsynchronousCriticalActionList.size(), totalValue])
        }

        return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.groupedCheckout", [checkoutAsynchronousCriticalActionList.size(), totalValue])
    }

    private String buildDetailedAuthorizationMessage(CriticalAction criticalAction, Map params) {
        if (criticalAction.pixTransaction) {
            return buildPixTransactionDetailedAuthorizationMessage(criticalAction.pixTransaction)
        }

        if (criticalAction.transfer) {
            return buildCreditTransferRequestDetailedAuthorizationMessage(criticalAction.transfer)
        }

        if (criticalAction.internalTransfer) {
            return buildInternalTransferDetailedAuthorizationMessage(criticalAction.internalTransfer)
        }

        if (criticalAction.synchronous && params) {
            return buildSynchronousCriticalActionAuthorizationMessage(criticalAction.type, params)
        }

        return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.${criticalAction.type.toString()}", [CustomDateUtils.formatDateTime(criticalAction.dateCreated)])
    }

    private String buildPixTransactionDetailedAuthorizationMessage(PixTransaction pixTransaction) {
        if (pixTransaction.type.isDebit()) {
            String receiverName = (pixTransaction.externalAccount.name as String)?.take(20) ?: ""
            return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.checkoutTransaction", [FormUtils.formatCurrencyWithMonetarySymbol(BigDecimalUtils.abs(pixTransaction.value)), receiverName])
        } else {
            return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.PIX_REFUND_CREDIT")
        }
    }

    private String buildCreditTransferRequestDetailedAuthorizationMessage(CreditTransferRequest creditTransferRequest) {
        String receiverName = creditTransferRequest.bankAccountInfo.buildAccountDescription(false)?.take(20) ?: ""
        return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.checkoutTransaction", [FormUtils.formatCurrencyWithMonetarySymbol(BigDecimalUtils.abs(creditTransferRequest.value)), receiverName])
    }

    private String buildInternalTransferDetailedAuthorizationMessage(InternalTransfer internalTransfer) {
        String receiverName = "conta Asaas: ${internalTransfer.destinationCustomer.getProviderName().take(20) ?: ""}"
        return Utils.getMessageProperty("criticalActionDefaultAuthorizationMessage.checkoutTransaction", [FormUtils.formatCurrencyWithMonetarySymbol(BigDecimalUtils.abs(internalTransfer.value)), receiverName])
    }
}
