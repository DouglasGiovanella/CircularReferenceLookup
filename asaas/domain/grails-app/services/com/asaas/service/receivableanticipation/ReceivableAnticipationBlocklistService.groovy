package com.asaas.service.receivableanticipation

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationBlocklist
import com.asaas.payment.PaymentStatus
import com.asaas.receivableanticipation.ReceivableAnticipationBlocklistReason
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationBlocklistService {

    def asyncActionService
    def receivableAnticipationCancellationService
    def receivableAnticipationValidationService

    public void processAwaitingCancelAnticipation() {
        final Integer flushEvery = 100
        final Integer maxItems = 1000
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.CANCEL_RECEIVABLE_ANTICIPATION_CUSTOMER_ACCOUNT_BLOCK_LIST, maxItems)
        Utils.forEachWithFlushSession(asyncActionDataList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ( {
                List<Long> customerAccountIdListToCancel = []

                if (asyncActionData.customerAccountCpfCnpj) {
                    customerAccountIdListToCancel += getCustomerAccountIdListByCpfOrRootCnpj(asyncActionData.customerAccountCpfCnpj)
                } else {
                    customerAccountIdListToCancel += [Long.valueOf(asyncActionData.customerAccountId)]
                }

                final Integer maxCustomerAccountsPerUpdate = 3000
                for (List<Long> customerAccountIdList : customerAccountIdListToCancel.collate(maxCustomerAccountsPerUpdate)) {
                    receivableAnticipationCancellationService.cancelAllPossibleByCustomerAccountIdList(customerAccountIdList, ReceivableAnticipationCancelReason.CUSTOMER_ACCOUNT_BLOCK_LIST)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            },
                [
                    logErrorMessage: "ReceivableAnticipationBlocklistService.processAwaitingCancelAnticipation >> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]",
                    onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
                ]
            )
        })
    }

    public void processSaveCustomerAccountBlocklistAnticipationOverdue() {
        final Integer maxAsyncActions = 100
        final Integer flushEvery = 20

        List<Map> asyncActionList = asyncActionService.listPending(AsyncActionType.SAVE_CUSTOMER_ACCOUNT_BLOCK_LIST_ANTICIPATION_OVERDUE, maxAsyncActions)
        if (!asyncActionList) return

        Utils.forEachWithFlushSession(asyncActionList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                String cpfCnpj = asyncActionData.cpfCnpj
                Map searchParams = ["customerAccount.cpfCnpj": cpfCnpj, exists: true]

                Boolean hasCustomerAccountOverdueAnticipation = ReceivableAnticipation.paymentOverdueWithPartner(searchParams).get().asBoolean()
                if (hasCustomerAccountOverdueAnticipation) {
                    saveCpfCnpjIfNecessary(cpfCnpj, ReceivableAnticipationBlocklistReason.ANTICIPATION_OVERDUE)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "ReceivableAnticipationBlocklistService.processSaveCustomerAccountBlocklistAnticipationOverdue >> Erro ao processar AsyncAction [asyncActionId: ${asyncActionData.asyncActionId}]",
                onError: {
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                }
            ])
        })
    }

    public void processAwaitingReanalysis() {
        final Integer flushEvery = 10
        final Integer batchSize = 50

        List<Long> receivableAnticipationBlocklistIdList = ReceivableAnticipationBlocklist.query([column: "id", "reason[in]": ReceivableAnticipationBlocklistReason.getCanBeReanalyzedBlocklistReasonList(), sentToReanalysisDate: new Date().clearTime(), disableSort: true]).list(max: 2000)

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(receivableAnticipationBlocklistIdList, batchSize, flushEvery, { Long receivableAnticipationBlocklistItemId ->
            ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem = ReceivableAnticipationBlocklist.get(receivableAnticipationBlocklistItemId)

            setReanalysisDate(receivableAnticipationBlocklistItem, null)

            if (receivableAnticipationBlocklistItem.reason.isBankSlipPaymentOverdue()) {
                reanalyzeOverdueBankSlipPayment(receivableAnticipationBlocklistItem)
            } else if (receivableAnticipationBlocklistItem.reason.isAnticipationOverdue()) {
                reanalyzeAnticipationOverdue(receivableAnticipationBlocklistItem)
            } else {
                throw new RuntimeException("Reason não suportada")
            }
        }, [logErrorMessage: "ReceivableAnticipationBlocklistService.processAwaitingReanalysis >> Erro ao reprocessar items", appendBatchToLogErrorMessage: true])
    }

    public void setOverdueBankSlipPaymentToBeReanalyzeTomorrow(CustomerAccount customerAccount) {
        ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem = ReceivableAnticipationBlocklist.bankSlipPaymentOverdue([customerAccount: customerAccount, "sentToReanalysisDate[isNull]": true]).get()
        if (!receivableAnticipationBlocklistItem) return

        receivableAnticipationBlocklistItem.lock()

        setReanalysisDate(receivableAnticipationBlocklistItem, CustomDateUtils.tomorrow())
    }

    public void setAnticipationOverdueToBeReanalyzeToday(String cpfCnpj) {
        ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem = ReceivableAnticipationBlocklist.anticipationOverdue([customerAccountCpfCnpj: cpfCnpj, "sentToReanalysisDate[isNull]": true]).get()
        if (!receivableAnticipationBlocklistItem) return

        setReanalysisDate(receivableAnticipationBlocklistItem, new Date())
    }

    public void savePaymentOverdueIfNecessary(CustomerAccount customerAccount, BillingType paymentBillingType) {
        if (!paymentBillingType.isBoletoOrPix()) return
        if (paymentBillingType.isPix() && !AbTestUtils.hasPixAnticipation(customerAccount.provider)) return

        Boolean isCustomerAccountOnBlockList = ReceivableAnticipationBlocklist.query([customerAccountId: customerAccount.id]).get().asBoolean()
         if (isCustomerAccountOnBlockList) return

        save(customerAccount, null, ReceivableAnticipationBlocklistReason.BANK_SLIP_PAYMENT_OVERDUE)
    }

    private void saveCpfCnpjIfNecessary(String cpfCnpj, ReceivableAnticipationBlocklistReason reason) {
        Boolean isCustomerAccountOnBlockList = ReceivableAnticipationBlocklist.query([exists: true, customerAccountCpfCnpj: cpfCnpj, reason: reason]).get().asBoolean()
        if (isCustomerAccountOnBlockList) return

        save(null, cpfCnpj, reason)
    }

    public void delete(ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem) {
        receivableAnticipationBlocklistItem.deleted = true
        receivableAnticipationBlocklistItem.save(failOnError: true)

        if (receivableAnticipationBlocklistItem.reason.isBankSlipPaymentOverdue()) {
            receivableAnticipationValidationService.onRemoveCustomerAccountFromBlockListReasonBankSlipOverdue(receivableAnticipationBlocklistItem.customerAccount)
        } else {
            receivableAnticipationValidationService.onRemoveCustomerAccountFromBlockListReasonAnticipationOverdue(receivableAnticipationBlocklistItem.customerAccountCpfRootCnpj)
        }
    }

    public void save(CustomerAccount customerAccount, String customerAccountCpfCnpj, ReceivableAnticipationBlocklistReason reason) {
        ReceivableAnticipationBlocklist customerAccountBlockList = new ReceivableAnticipationBlocklist()
        customerAccountBlockList.customerAccount = customerAccount
        customerAccountBlockList.customerAccountCpfCnpj = customerAccountCpfCnpj
        customerAccountBlockList.customerAccountCpfRootCnpj = CpfCnpjUtils.isCnpj(customerAccountCpfCnpj) ? CpfCnpjUtils.getRootFromCnpj(customerAccountCpfCnpj) : customerAccountCpfCnpj
        customerAccountBlockList.reason = reason
        customerAccountBlockList.save(failOnError: true)

        if (reason.isBankSlipPaymentOverdue()) {
            receivableAnticipationValidationService.onAddCustomerAccountInBlockList(customerAccount)
        } else {
            receivableAnticipationValidationService.onAddCustomerAccountCpfCnpjInBlockList(customerAccountBlockList.customerAccountCpfRootCnpj)
        }
        asyncActionService.save(AsyncActionType.CANCEL_RECEIVABLE_ANTICIPATION_CUSTOMER_ACCOUNT_BLOCK_LIST, [customerAccountId: customerAccount?.id, customerAccountCpfCnpj:  customerAccountCpfCnpj])
    }

    private ReceivableAnticipationBlocklist setReanalysisDate(ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem, Date reanalysisDate) {
        receivableAnticipationBlocklistItem.sentToReanalysisDate = reanalysisDate ? reanalysisDate.clearTime() : null
        receivableAnticipationBlocklistItem.save(failOnError: true)

        return receivableAnticipationBlocklistItem
    }

    private void reanalyzeOverdueBankSlipPayment(ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem) {
        List<BillingType> billingTypeList = [BillingType.BOLETO]
        if (AbTestUtils.hasPixAnticipation(receivableAnticipationBlocklistItem.customerAccount.provider)) billingTypeList.add(BillingType.PIX)

        Boolean hasCustomerAccountOverduePaymentYesterday = Payment.query([exists: true,
                                                                           dueDate: CustomDateUtils.getYesterday(),
                                                                           status: PaymentStatus.OVERDUE,
                                                                           customerAccount: receivableAnticipationBlocklistItem.customerAccount,
                                                                           billingTypeList: billingTypeList]).get().asBoolean()
        if (hasCustomerAccountOverduePaymentYesterday) return

        delete(receivableAnticipationBlocklistItem)
    }

    private void reanalyzeAnticipationOverdue(ReceivableAnticipationBlocklist receivableAnticipationBlocklistItem) {
        Boolean stillWaitingPayment = ReceivableAnticipation.paymentOverdueWithPartner([exists: true, "customerAccount.cpfCnpj": receivableAnticipationBlocklistItem.customerAccountCpfCnpj]).get()
        if (stillWaitingPayment) return

        delete(receivableAnticipationBlocklistItem)
    }

    private List<Long> getCustomerAccountIdListByCpfOrRootCnpj(String cpfCnpj) {
        Map searchParams = [column: "id", disableSort: true]

        if (CpfCnpjUtils.isCnpj(cpfCnpj)) {
            return CustomerAccount.economicGroup(CpfCnpjUtils.getRootFromCnpj(cpfCnpj), searchParams).list()
        }

        searchParams += ["cpfCnpj": cpfCnpj]
        return CustomerAccount.query(searchParams).list()
    }
}
