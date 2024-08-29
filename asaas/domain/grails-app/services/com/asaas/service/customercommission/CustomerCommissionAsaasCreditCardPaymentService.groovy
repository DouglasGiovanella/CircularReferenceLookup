package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.creditcard.repository.CreditCardTransactionInfoRepository
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.itemlist.CustomerCommissionAsaasCreditCardDataBuilder
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
class CustomerCommissionAsaasCreditCardPaymentService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService

    public void create() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommisionAsaasCreditCardPaymentAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_CREDIT_CARD_FEE_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommisionAsaasCreditCardPaymentAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommisionAsaasCreditCardPaymentAsyncActionList, 50, { Map customerCommissionAsaasCreditCardPaymentAsyncActionData ->
            Long asyncActionId = Utils.toLong(customerCommissionAsaasCreditCardPaymentAsyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(customerCommissionAsaasCreditCardPaymentAsyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)

                if (!config.creditCardFeePercentage) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(customerCommissionAsaasCreditCardPaymentAsyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(customerCommissionAsaasCreditCardPaymentAsyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionAsaasCreditCardPaymentService.create >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionAsaasCreditCardPaymentService.create >> Erro ao criar as comissões de cartão de crédito Asaas. AsyncActionData: ${customerCommissionAsaasCreditCardPaymentAsyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_FEE)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<Map> financialTransactionMapList = buildFinancialTransactionMapList(ownedAccount, transactionDate)
        if (financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CREDIT_CARD_FEE)
            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(financialTransactionMapList, customerCommission.id, config)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(financialTransactionMapList, customerCommission, asyncActionId)
    }

    private List<Map> buildFinancialTransactionMapList(Customer ownedAccount, Date transactionDate) {
        CustomerCommissionAsaasCreditCardDataBuilder customerCommissionAsaasCreditCardDataBuilder = new CustomerCommissionAsaasCreditCardDataBuilder(ownedAccount, transactionDate)

        List<Map> financialTransactionMapList = customerCommissionAsaasCreditCardDataBuilder.buildItems()

        return financialTransactionMapList
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(List<Map> financialTransactionMapList, Long customerCommissionId, CustomerCommissionConfig config) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap : financialTransactionMapList) {
            Long paymentId = Utils.toLong(financialTransactionMap."payment.id")
            BigDecimal paymentValue = Utils.toBigDecimal(financialTransactionMap."payment.value")
            BigDecimal acquirerFee = calculateAcquirerFee(paymentId, paymentValue)
            BigDecimal financialTransactionValue = Utils.toBigDecimal(financialTransactionMap.value)
            BigDecimal transactionValue = BigDecimalUtils.abs(financialTransactionValue) - acquirerFee
            BigDecimal value = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(transactionValue, config.creditCardFeePercentage)
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
                type: CustomerCommissionType.CREDIT_CARD_FEE.toString(),
                customer_commission_id: customerCommissionId,
                percentage: config.creditCardFeePercentage,
                asaas_fee: transactionValue,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateAcquirerFee(Long paymentId, BigDecimal paymentValue) {
        final BigDecimal averageAcquirerFeePercentage = 2.40

        BigDecimal acquirerNetValue = CreditCardTransactionInfoRepository.query([paymentId: paymentId]).column("acquirerNetValue").get()
        if (acquirerNetValue) return paymentValue - acquirerNetValue

        return BigDecimalUtils.calculateValueFromPercentage(paymentValue, averageAcquirerFeePercentage)
    }
}
