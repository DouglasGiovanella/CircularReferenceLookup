package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionDebitCardDataBuilder
import com.asaas.debitcard.repository.DebitCardTransactionInfoRepository
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
class CustomerCommissionDebitCardService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void create() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionDebitCardAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_DEBIT_CARD_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionDebitCardAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionDebitCardAsyncActionList, 50, { Map customerCommissionDebitCardAsyncActionData ->
            Long asyncActionId = Utils.toLong(customerCommissionDebitCardAsyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(customerCommissionDebitCardAsyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)
                if (!config.debitCardFeePercentage) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(customerCommissionDebitCardAsyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(customerCommissionDebitCardAsyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionDebitCardService.create >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionDebitCardService.create >> Erro ao criar as comissões de cartão de débito. AsyncActionData: ${customerCommissionDebitCardAsyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.DEBIT_CARD_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.DEBIT_CARD_FEE)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionDebitCardDataBuilder customerCommissionDebitCardDataBuilder = new CustomerCommissionDebitCardDataBuilder(ownedAccount, transactionDate)

        List<Map> financialTransactionMapList = customerCommissionDebitCardDataBuilder.buildItems()

        return financialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap in financialTransactionMapList) {
            Long paymentId = Utils.toLong(financialTransactionMap."payment.id")
            BigDecimal paymentValue = Utils.toBigDecimal(financialTransactionMap."payment.value")
            BigDecimal financialTransactionValue = Utils.toBigDecimal(financialTransactionMap.value)
            BigDecimal acquirerFee = calculateAcquirerFee(paymentId, paymentValue)
            BigDecimal transactionValue = BigDecimalUtils.abs(financialTransactionValue) - acquirerFee
            BigDecimal value = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(transactionValue, config.debitCardFeePercentage)
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
                type: CustomerCommissionType.DEBIT_CARD_FEE.toString(),
                customer_commission_id: customerCommissionId,
                asaas_fee: transactionValue,
                percentage: config.debitCardFeePercentage,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateAcquirerFee(Long paymentId, BigDecimal paymentValue) {
        final BigDecimal averageAcquirerFeePercentage = 1.41

        BigDecimal acquirerNetValue = DebitCardTransactionInfoRepository.query([paymentId: paymentId]).column("acquirerNetValue").get()
        if (acquirerNetValue) return paymentValue - acquirerNetValue

        return BigDecimalUtils.calculateValueFromPercentage(paymentValue, averageAcquirerFeePercentage)
    }
}
