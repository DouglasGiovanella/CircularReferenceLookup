package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionCreditCardPaymentDataBuilder
import com.asaas.customercommission.itemlist.CustomerCommissionDataBuilder
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
class CustomerCommissionCreditCardPaymentService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void create() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommisionCreditCardPaymentAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_CREDIT_CARD_PAYMENT_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommisionCreditCardPaymentAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommisionCreditCardPaymentAsyncActionList, 50, { Map customerCommissionCreditCardAsyncActionData ->
            Long asyncActionId = Utils.toLong(customerCommissionCreditCardAsyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(customerCommissionCreditCardAsyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)

                if (!config.creditCardPaymentFeePercentageWithOverprice && !config.creditCardPaymentFeeUpToSixPercentageWithOverprice && !config.creditCardPaymentFeeUpToTwelvePercentageWithOverprice) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(customerCommissionCreditCardAsyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(customerCommissionCreditCardAsyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionCreditCardPaymentService.create >> Lock ao processar a AsyncAction de comissão [${customerCommissionCreditCardAsyncActionData.asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionCreditCardPaymentService.create >> Erro ao criar as comissões de cartão de crédito. AsyncActionData: ${customerCommissionCreditCardAsyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_PAYMENT_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        CustomerCommissionCreditCardPaymentDataBuilder customerCommissionCreditCardPaymentDataBuilder = new CustomerCommissionCreditCardPaymentDataBuilder(ownedAccount, transactionDate)
        List<Map> financialTransactionMapList = customerCommissionCreditCardPaymentDataBuilder.buildItems()
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_PAYMENT_FEE)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        Boolean isCreateItemsDone = !financialTransactionMapList || financialTransactionMapList?.size() < CustomerCommissionDataBuilder.MAX_ITEMS
        if (customerCommission && isCreateItemsDone) customerCommissionService.changeCreationCommissionItemsToDone(customerCommission)

        if (isCreateItemsDone) customerCommissionAsyncActionService.delete(asyncActionId)
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap : financialTransactionMapList) {
            Integer installmentCount = Utils.toInteger(financialTransactionMap."paymentInstallment.installmentCount")
            BigDecimal commissionFeePercentage = calculateFeePercentage(config, installmentCount)
            if (!commissionFeePercentage) continue

            BigDecimal paymentValue = Utils.toBigDecimal(financialTransactionMap."payment.value")
            BigDecimal value = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(paymentValue, commissionFeePercentage)
            if (value < 0.00) value = 0.00

            totalValue += value

            Map customerCommissionItemMap = [
                version: 0,
                date_created: new Date(),
                last_updated: new Date(),
                deleted: false,
                public_id: UUID.randomUUID().toString(),
                financial_transaction_id: financialTransactionMap.id,
                type: CustomerCommissionType.CREDIT_CARD_PAYMENT_FEE.toString(),
                customer_commission_id: customerCommissionId,
                percentage: commissionFeePercentage,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateFeePercentage(CustomerCommissionConfig config, Integer installmentCount) {
        if (!installmentCount || installmentCount == 1) {
            return config.creditCardPaymentFeePercentageWithOverprice
        } else if (installmentCount <= 6) {
            return config.creditCardPaymentFeeUpToSixPercentageWithOverprice
        } else if (installmentCount <= 12) {
            return  config.creditCardPaymentFeeUpToTwelvePercentageWithOverprice
        } else {
            throw new RuntimeException("Comissão de cartão para [${installmentCount}] inexistente. [configId: ${config.id}]")
        }
    }
}
