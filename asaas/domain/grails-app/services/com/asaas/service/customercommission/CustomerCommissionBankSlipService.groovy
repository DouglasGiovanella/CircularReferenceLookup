package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionBankSlipDataBuilder
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
class CustomerCommissionBankSlipService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void createCommissions() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_BANK_SLIP_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)

        if (!pendingCreateCustomerCommissionAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionAsyncActionList, 50, { Map asyncActionData ->
            Long asyncActionId = Utils.toLong(asyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(asyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)

                if (!config.bankSlipFeePercentage && !config.bankSlipFeeFixedValue && !config.bankSlipFeeFixedValueWithOverprice) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(asyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionBankSlipService.createCommissions >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionBankSlipService.createCommissions >> Erro ao criar comissão AsyncActionData: ${asyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.BANK_SLIP_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.BANK_SLIP_FEE)

            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionBankSlipDataBuilder customerCommissionBankSlipDataBuilder = new CustomerCommissionBankSlipDataBuilder(ownedAccount, transactionDate)

        List<Map> financialTransactionMapList = customerCommissionBankSlipDataBuilder.buildItems()

        return financialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap : financialTransactionMapList) {
            BigDecimal convertedFinancialTransactionValue = Utils.toBigDecimal(financialTransactionMap.value)
            BigDecimal transactionValue = BigDecimalUtils.abs(convertedFinancialTransactionValue)
            BigDecimal value = calculateCommissionItemValue(transactionValue, config)
            if (value < 0.00) value = 0.00

            if (transactionValue) totalAsaasFee += transactionValue
            totalValue += value

            Map customerCommissionItemMap = [
                version: 0,
                date_created: new Date(),
                last_updated: new Date(),
                deleted: false,
                public_id: UUID.randomUUID().toString(),
                financial_transaction_id: financialTransactionMap.id,
                type: CustomerCommissionType.BANK_SLIP_FEE.toString(),
                customer_commission_id: customerCommissionId,
                asaas_fee: transactionValue,
                percentage: config.bankSlipFeePercentage,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateCommissionItemValue(BigDecimal transactionValue, CustomerCommissionConfig config) {
        if (config.bankSlipFeePercentage > 0) return BigDecimalUtils.calculateValueFromPercentageWithRoundDown(transactionValue, config.bankSlipFeePercentage)

        if (config.bankSlipFeeFixedValue > 0) return config.bankSlipFeeFixedValue

        return config.bankSlipFeeFixedValueWithOverprice
    }
}
