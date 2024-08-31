package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.chargedfee.ChargedFeeStatus
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionPaymentDunningDataBuilder
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
class CustomerCommissionPaymentDunningService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void create() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionPaymentDunningAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_PAYMENT_DUNNING_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionPaymentDunningAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionPaymentDunningAsyncActionList, 50, { Map customerCommissionPaymentDunningAsyncActionData ->
            Long asyncActionId = Utils.toLong(customerCommissionPaymentDunningAsyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(customerCommissionPaymentDunningAsyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)

                if (!config.paymentDunningFeeFixedValue && !config.paymentDunningFeePercentage && !config.dunningCreditBureauFeeFixedValueWithOverprice) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(customerCommissionPaymentDunningAsyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(customerCommissionPaymentDunningAsyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionPaymentDunningService.create >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionPaymentDunningService.create >> Erro ao criar as comissões de Negativação. AsyncActionData: ${customerCommissionPaymentDunningAsyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PAYMENT_DUNNING_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.PAYMENT_DUNNING_FEE)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionPaymentDunningDataBuilder customerCommissionPaymentDunningDataBuilder = new CustomerCommissionPaymentDunningDataBuilder(ownedAccount, transactionDate)

        List<Map> financialTransactionMapList = customerCommissionPaymentDunningDataBuilder.buildItems()

        return financialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap in financialTransactionMapList) {
            ChargedFeeStatus chargedFeeStatus = ChargedFeeStatus.convert(financialTransactionMap."chargedFee.status".toString())
            if (!chargedFeeStatus.isDebited()) continue

            BigDecimal financialTransactionValue = Utils.toBigDecimal(financialTransactionMap.value)
            BigDecimal asaasFee = BigDecimalUtils.abs(financialTransactionValue)
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
                type: CustomerCommissionType.PAYMENT_DUNNING_FEE.toString(),
                customer_commission_id: customerCommissionId,
                asaas_fee: asaasFee,
                percentage: config.paymentDunningFeePercentage ?: null,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateCommissionItemValue(BigDecimal asaasFee, CustomerCommissionConfig config) {
        if (config.paymentDunningFeePercentage) return BigDecimalUtils.calculateValueFromPercentageWithRoundDown(asaasFee, config.paymentDunningFeePercentage)

        if (config.dunningCreditBureauFeeFixedValueWithOverprice) return config.dunningCreditBureauFeeFixedValueWithOverprice

        return config.paymentDunningFeeFixedValue
    }
}
