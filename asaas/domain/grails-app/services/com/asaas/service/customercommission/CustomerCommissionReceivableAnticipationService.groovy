package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.adapter.CreateCustomerCommissionReceivableAnticipationItemAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.log.AsaasLogger
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.service.receivableanticipation.ReceivableAnticipationFinancialInfoService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionReceivableAnticipationService {

    CustomerCommissionService customerCommissionService
    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    ReceivableAnticipationFinancialInfoService receivableAnticipationFinancialInfoService

    public Boolean createCustomerCommissionAsaasBankSlipReceivableAnticipation() {
        return createCustomerCommissionReceivableAnticipation(
            CustomerCommissionAsyncActionType.CREATE_ASAAS_BANK_SLIP_RECEIVABLE_ANTICIPATION_CUSTOMER_COMMISSION, CustomerCommissionType.ASAAS_BANK_SLIP_RECEIVABLE_ANTICIPATION, BillingType.BOLETO
        )
    }

    public Boolean createCustomerCommissionAsaasPixReceivableAnticipation() {
        return createCustomerCommissionReceivableAnticipation(
            CustomerCommissionAsyncActionType.CREATE_ASAAS_PIX_RECEIVABLE_ANTICIPATION_CUSTOMER_COMMISSION, CustomerCommissionType.ASAAS_PIX_RECEIVABLE_ANTICIPATION, BillingType.PIX
        )
    }

    public Boolean createCustomerCommissionAsaasCreditCardReceivableAnticipation() {
        return createCustomerCommissionReceivableAnticipation(
            CustomerCommissionAsyncActionType.CREATE_ASAAS_CREDIT_CARD_RECEIVABLE_ANTICIPATION_CUSTOMER_COMMISSION, CustomerCommissionType.ASAAS_CREDIT_CARD_RECEIVABLE_ANTICIPATION, BillingType.MUNDIPAGG_CIELO
        )
    }

    public Boolean createCustomerCommissionFidcBankSlipReceivableAnticipation() {
        return createCustomerCommissionReceivableAnticipation(
            CustomerCommissionAsyncActionType.CREATE_FIDC_BANK_SLIP_RECEIVABLE_ANTICIPATION_CUSTOMER_COMMISSION, CustomerCommissionType.FIDC_BANK_SLIP_RECEIVABLE_ANTICIPATION, BillingType.BOLETO
        )
    }

    public Boolean createCustomerCommissionFidcPixReceivableAnticipation() {
        return createCustomerCommissionReceivableAnticipation(
            CustomerCommissionAsyncActionType.CREATE_FIDC_PIX_RECEIVABLE_ANTICIPATION_CUSTOMER_COMMISSION, CustomerCommissionType.FIDC_PIX_RECEIVABLE_ANTICIPATION, BillingType.PIX
        )
    }

    public Boolean createCustomerCommissionFidcCreditCardReceivableAnticipation() {
        return createCustomerCommissionReceivableAnticipation(
            CustomerCommissionAsyncActionType.CREATE_FIDC_CREDIT_CARD_RECEIVABLE_ANTICIPATION_CUSTOMER_COMMISSION, CustomerCommissionType.FIDC_CREDIT_CARD_RECEIVABLE_ANTICIPATION, BillingType.MUNDIPAGG_CIELO
        )
    }

    private void createCustomerCommissionReceivableAnticipation(CustomerCommissionAsyncActionType customerCommissionAsyncActionType, CustomerCommissionType customerCommissionType, BillingType billingType) {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateReceivableAnticipationCustomerCommissionAsyncActionInfoList = customerCommissionAsyncActionService.listPending(customerCommissionAsyncActionType, maxItemsPerCycle)
        if (!pendingCreateReceivableAnticipationCustomerCommissionAsyncActionInfoList) return

        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(pendingCreateReceivableAnticipationCustomerCommissionAsyncActionInfoList, flushEvery, { Map asyncActionData ->
            Long asyncActionId = Utils.toLong(asyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(asyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)

                if (!hasDailyPercentageByBillingType(config, billingType)) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(asyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate.toString())

                processCustomerCommissionAsyncAction(ownedAccount, transactionDate, config, asyncActionId, customerCommissionType, billingType)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionReceivableAnticipationService.createCustomerCommissionReceivableAnticipation >> Lock ao processar a AsyncAction de comissão [${asyncActionData.asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionReceivableAnticipationService.createCustomerCommissionReceivableAnticipation >> Erro ao criar as comissões de antecipação. AsyncActionData: ${asyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private void processCustomerCommissionAsyncAction(Customer ownedAccount, Date transactionDate, CustomerCommissionConfig config, Long asyncActionId, CustomerCommissionType customerCommissionType, BillingType billingType) {
        CustomerCommission customerCommission = customerCommissionService.findExistingCustomerCommissionWithSameParameters(config.customer, ownedAccount, transactionDate, customerCommissionType)
        if (customerCommission?.status?.isItemCreationDone()) {
            customerCommissionAsyncActionService.delete(asyncActionId)
            return
        }

        List<ReceivableAnticipationPartner> partnerList = customerCommissionType.isAsaasReceivableAnticipation() ? [ReceivableAnticipationPartner.ASAAS, ReceivableAnticipationPartner.OCEAN] : [ReceivableAnticipationPartner.VORTX]
        CreateCustomerCommissionReceivableAnticipationItemAdapter commissionAdapter = new CreateCustomerCommissionReceivableAnticipationItemAdapter(ownedAccount, transactionDate, customerCommissionType, config, billingType, partnerList)
        if (commissionAdapter.financialTransactionMapList) {
            if (!customerCommission) customerCommission = customerCommissionService.save(config.customer, ownedAccount, transactionDate, customerCommissionType)

            CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter = buildCustomerCommissionItemBatchData(commissionAdapter, customerCommission.id)
            customerCommissionService.saveInBatchIfHasItemsAndUpdateCustomerCommission(itemInsertDataListAdapter, customerCommission, false)
        }

        customerCommissionService.finishItemCreationIfNecessary(commissionAdapter.financialTransactionMapList, customerCommission, asyncActionId)
    }

    private CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(CreateCustomerCommissionReceivableAnticipationItemAdapter commissionAdapter, Long customerCommissionId) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0
        BigDecimal dailyPercentage

        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap : commissionAdapter.financialTransactionMapList) {
            ReceivableAnticipation receivableAnticipation = financialTransactionMap.receivableAnticipation as ReceivableAnticipation

            if (receivableAnticipation.billingType.isBoletoOrPix()) {
                dailyPercentage = commissionAdapter.bankSlipDailyPercentage
            } else {
                Boolean isInstallmentReceivableAnticipation = (receivableAnticipation.installment || receivableAnticipation.payment?.installment)
                dailyPercentage = isInstallmentReceivableAnticipation ? commissionAdapter.creditCardInstallmentDailyPercentage : commissionAdapter.creditCardDetachedDailyPercentage

                if (!dailyPercentage) continue
            }

            BigDecimal value = calculateCustomerCommissionValue(dailyPercentage, receivableAnticipation)
            if (value < 0.00) value = 0.00

            totalValue += value

            Map customerCommissionItemMap = [
                version: 0,
                date_created: new Date(),
                last_updated: new Date(),
                deleted: false,
                public_id: UUID.randomUUID().toString(),
                financial_transaction_id: financialTransactionMap.id,
                receivable_anticipation_id: receivableAnticipation.id,
                customer_commission_id: customerCommissionId,
                type: commissionAdapter.customerCommissionType.toString(),
                percentage: dailyPercentage,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    private BigDecimal calculateCustomerCommissionValue(BigDecimal dailyCommissionPercentage, ReceivableAnticipation receivableAnticipation) {
        BigDecimal customerCommissionValue = 0.0

        for (ReceivableAnticipationItem receivableAnticipationItem : receivableAnticipation.items) {
            Integer anticipationDays = ReceivableAnticipationCalculator.calculateAnticipationDays(receivableAnticipation.anticipationDate, receivableAnticipationItem.estimatedCreditDate)
            customerCommissionValue += receivableAnticipationFinancialInfoService.calculateFeeValue(dailyCommissionPercentage, receivableAnticipationItem.value, anticipationDays)
        }

        return customerCommissionValue
    }

    private Boolean hasDailyPercentageByBillingType(CustomerCommissionConfig config, BillingType billingType) {
        if (billingType.isCreditCard()) return config.creditCardDetachedAnticipationPercentageWithOverprice || config.creditCardInstallmentAnticipationPercentageWithOverprice

        if (config.bankSlipAnticipationPercentageWithOverprice) return true

        return false
    }
}
