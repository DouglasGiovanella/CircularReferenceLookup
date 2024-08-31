package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionInvoiceDataBuilder
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.log.AsaasLogger
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionInvoiceService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void createCommissions() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_INVOICE_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionAsyncActionList, 50, { Map asyncActionData ->
            Long asyncActionId = Utils.toLong(asyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(asyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)
                if (!config.invoiceFeePercentage && !config.invoiceFeeFixedValue && !config.invoiceFeeFixedValueWithOverprice) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(asyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionInvoiceService.createCommissions >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionInvoiceService.createCommissions >> Erro ao criar comissão AsyncActionData: ${asyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.INVOICE_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.INVOICE_FEE)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionInvoiceDataBuilder customerCommissionInvoiceDataBuilder = new CustomerCommissionInvoiceDataBuilder(ownedAccount, transactionDate)

        List<Map> financialTransactionMapList = customerCommissionInvoiceDataBuilder.buildItems()

        return financialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap in financialTransactionMapList) {
            BigDecimal convertedFinancialTransactionValue = Utils.toBigDecimal(financialTransactionMap.value)
            BigDecimal asaasFee = BigDecimalUtils.abs(convertedFinancialTransactionValue)
            BigDecimal value = calculateCommissionItemValue(asaasFee, config)
            if (value < 0.00) value = 0.00

            if (asaasFee) totalAsaasFee += asaasFee
            totalValue += value

            Map customerCommissionItemMap = [
                version: 0,
                date_created: new Date(),
                last_updated: new Date(),
                deleted: false,
                public_id: UUID.randomUUID().toString(),
                financial_transaction_id: financialTransactionMap.id,
                type: CustomerCommissionType.INVOICE_FEE.toString(),
                customer_commission_id: customerCommissionId,
                asaas_fee: asaasFee,
                percentage: config.invoiceFeePercentage ?: null,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateCommissionItemValue(BigDecimal asaasFee, CustomerCommissionConfig config) {
        if (config.invoiceFeePercentage) return BigDecimalUtils.calculateValueFromPercentageWithRoundDown(asaasFee, config.invoiceFeePercentage)

        if (config.invoiceFeeFixedValueWithOverprice) return config.invoiceFeeFixedValueWithOverprice

        return config.invoiceFeeFixedValue
    }
}
