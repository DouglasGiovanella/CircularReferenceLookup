package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.adapter.CreateCustomerCommissionRefundItemAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.log.AsaasLogger
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionCreditCardRefundService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionPaymentRefundService customerCommissionPaymentRefundService
    CustomerCommissionService customerCommissionService

    public void create() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreditCardRefundCustomerCommissionAsyncAction = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_CREDIT_CARD_REFUND_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreditCardRefundCustomerCommissionAsyncAction) return

        Utils.forEachWithFlushSession(pendingCreditCardRefundCustomerCommissionAsyncAction, 50, { Map customerCommissionCreditCardRefundAsyncActionData ->
            Long asyncActionId = Utils.toLong(customerCommissionCreditCardRefundAsyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionedAccountId = Utils.toLong(customerCommissionCreditCardRefundAsyncActionData.commissionedAccountId)
                Boolean hasPendingCreditCustomerCommission = customerCommissionAsyncActionService.hasPendingCreditCustomerCommission(commissionedAccountId, CustomerCommissionAsyncActionType.CREATE_CREDIT_CARD_FEE_CUSTOMER_COMMISSION)
                if (hasPendingCreditCustomerCommission) return

                Long commissionConfigId = Utils.toLong(customerCommissionCreditCardRefundAsyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(customerCommissionCreditCardRefundAsyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionCreditCardRefundService.create >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionCreditCardRefundService.create >> Erro ao criar as comissões de estorno de cartão de crédito. AsyncActionData: ${customerCommissionCreditCardRefundAsyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_REFUND)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        CreateCustomerCommissionRefundItemAdapter commissionRefundAdapter = new CreateCustomerCommissionRefundItemAdapter(ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_REFUND, [BillingType.MUNDIPAGG_CIELO], CustomerCommissionType.CREDIT_CARD_FEE)
        if (commissionRefundAdapter.financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_REFUND)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = customerCommissionPaymentRefundService.buildCustomerCommissionItemBatchData(commissionRefundAdapter, customerCommission.id)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(commissionRefundAdapter.financialTransactionMapList, customerCommission, asyncActionId)
    }
}
