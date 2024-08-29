package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionPaymentDunningRefundDataBuilder
import com.asaas.customercommission.repository.CustomerCommissionItemRepository
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customercommission.CustomerCommissionItem
import com.asaas.log.AsaasLogger
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionPaymentDunningRefundService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void createCommissions() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_PAYMENT_DUNNING_REFUND_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionAsyncActionList, 50, { Map asyncActionData ->
            Long asyncActionId = Utils.toLong(asyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionedAccountId = Utils.toLong(asyncActionData.commissionedAccountId)

                Boolean hasPendingCreditCustomerCommissionToBeCreated = customerCommissionAsyncActionService.hasPendingCreditCustomerCommission(commissionedAccountId, CustomerCommissionAsyncActionType.CREATE_PAYMENT_DUNNING_FEE_CUSTOMER_COMMISSION)
                if (hasPendingCreditCustomerCommissionToBeCreated) return

                Long commissionConfigId = Utils.toLong(asyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionPaymentDunningRefundService.createCommissions >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionPaymentDunningRefundService.createCommissions >> Erro ao criar comissão AsyncActionData: ${asyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PAYMENT_DUNNING_REFUND)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PAYMENT_DUNNING_REFUND)

            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionPaymentDunningRefundDataBuilder customerCommissionPaymentDunningRefundDataBuilder = new CustomerCommissionPaymentDunningRefundDataBuilder(ownedAccount, transactionDate)

        List<Map> refundFinancialTransactionMapList = customerCommissionPaymentDunningRefundDataBuilder.buildItems()

        return  refundFinancialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap in financialTransactionMapList) {
            CustomerCommissionItem commissionedItem = CustomerCommissionItemRepository.query([financialTransactionId: financialTransactionMap."reversedTransaction.id"]).get()
            if (!commissionedItem?.value) continue

            BigDecimal asaasFee = commissionedItem.asaasFee * -1
            BigDecimal value = commissionedItem.value * -1
            if (asaasFee) totalAsaasFee += asaasFee
            totalValue += value

            Map customerCommissionItemMap = [
                version: 0,
                date_created: new Date(),
                last_updated: new Date(),
                deleted: false,
                public_id: UUID.randomUUID().toString(),
                financial_transaction_id: financialTransactionMap.id,
                customer_commission_id: customerCommissionId,
                type: CustomerCommissionType.PAYMENT_DUNNING_REFUND.toString(),
                asaas_fee: asaasFee,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }
}
