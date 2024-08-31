package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionPixDataBuilder
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.pix.PixTransaction
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionPixService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void create() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionPixAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_PIX_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionPixAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionPixAsyncActionList, 50, { Map customerCommissionPixAsyncActionData ->
            Long asyncActionId = Utils.toLong(customerCommissionPixAsyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(customerCommissionPixAsyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)

                if (!config.pixFeeFixedValue && !config.pixFeePercentageWithOverprice && !config.pixFeeFixedValueWithOverprice) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(customerCommissionPixAsyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(customerCommissionPixAsyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError: { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionPixService.create >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionPixService.create >> Erro ao criar as comissões de Pix. AsyncActionData: ${customerCommissionPixAsyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PIX_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PIX_FEE)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionPixDataBuilder customerCommissionPixDataBuilder = new CustomerCommissionPixDataBuilder(ownedAccount, transactionDate)

        List<Map> financialTransactionMapList = customerCommissionPixDataBuilder.buildItems()

        return financialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0
        BigDecimal totalValue = 0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap : financialTransactionMapList) {
            BigDecimal value = calculateCommissionItemValue(financialTransactionMap, config)
            if (value < 0.00) value = 0.00

            BigDecimal convertedFinancialTransactionValue = new BigDecimal(financialTransactionMap.value.toString())
            BigDecimal transactionValue = BigDecimalUtils.abs(convertedFinancialTransactionValue)
            if (transactionValue) totalAsaasFee += transactionValue
            totalValue += value

            Map customerCommissionItemMap = [
                version: 0,
                date_created: new Date(),
                last_updated: new Date(),
                deleted: false,
                public_id: UUID.randomUUID().toString(),
                financial_transaction_id: financialTransactionMap.id,
                asaas_fee: transactionValue,
                type: CustomerCommissionType.PIX_FEE.toString(),
                customer_commission_id: customerCommissionId,
                percentage: config.pixFeeFixedValue ? null : config.pixFeePercentageWithOverprice,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateCommissionItemValue(Map financialTransactionMap, CustomerCommissionConfig config) {
        if (config.pixFeeFixedValueWithOverprice) return config.pixFeeFixedValueWithOverprice
        if (config.pixFeeFixedValue) return config.pixFeeFixedValue

        BigDecimal transactionTotalValue = getTransactionTotalValue(financialTransactionMap)

        BigDecimal feeValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(transactionTotalValue, config.pixFeePercentageWithOverprice)

        return calculatePixFeePercentageValue(config, feeValue)
    }

    private BigDecimal getTransactionTotalValue(Map financialTransactionMap) {
        FinancialTransactionType transactionType = FinancialTransactionType.convert(financialTransactionMap."transactionType")

        if (transactionType.isPixTransactionCreditFee()) {
            ChargedFee chargedFee = financialTransactionMap.chargedFee as ChargedFee
            PixTransaction pixTransaction = chargedFee.pixTransaction

            return Utils.toBigDecimal(pixTransaction.value)
        }

        return Utils.toBigDecimal(financialTransactionMap."payment.value")
    }

    private BigDecimal calculatePixFeePercentageValue(CustomerCommissionConfig config, BigDecimal feeValue) {
        if (feeValue > config.pixMaximumFee) return config.pixMaximumFee
        if (feeValue < config.pixMinimumFee) return config.pixMinimumFee

        return feeValue
    }
}
