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
class CustomerCommissionPixRefundService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService
    CustomerCommissionPaymentRefundService customerCommissionPaymentRefundService

    public void createCommissions() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_PIX_FEE_REFUND_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionAsyncActionList, 50, { Map asyncActionData ->
            Long asyncActionId = Utils.toLong(asyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionedAccountId = Utils.toLong(asyncActionData.commissionedAccountId)
                Boolean hasPendingPixFeeCreditToBeProcessed = customerCommissionAsyncActionService.hasPendingCreditCustomerCommission(commissionedAccountId, CustomerCommissionAsyncActionType.CREATE_PIX_FEE_CUSTOMER_COMMISSION)
                if (hasPendingPixFeeCreditToBeProcessed) return

                Long commissionConfigId = Utils.toLong(asyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionPixRefundService.createCommissions >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionPixRefundService.createCommissions >> Erro ao criar estorno de comissão AsyncActionData: ${asyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PIX_FEE_REFUND)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        CreateCustomerCommissionRefundItemAdapter commissionRefundAdapter = new CreateCustomerCommissionRefundItemAdapter(ownedAccount, transactionDate, CustomerCommissionType.PIX_FEE_REFUND, [BillingType.PIX], CustomerCommissionType.PIX_FEE)
        if (commissionRefundAdapter.financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PIX_FEE_REFUND)

            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = customerCommissionPaymentRefundService.buildCustomerCommissionItemBatchData(commissionRefundAdapter, customerCommission.id)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(commissionRefundAdapter.financialTransactionMapList, customerCommission, asyncActionId)
    }
}
