package com.asaas.service.customercommission

import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.adapter.CreateCustomerCommissionRefundItemAdapter
import com.asaas.customercommission.repository.CustomerCommissionItemRepository
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import groovy.transform.CompileDynamic

@GrailsCompileStatic
@Transactional
class CustomerCommissionPaymentRefundService {

    public CustomerCommissionItemInsertDataListAdapter buildCustomerCommissionItemBatchData(CreateCustomerCommissionRefundItemAdapter commissionRefundAdapter, Long customerCommissionId) {
        BigDecimal totalAsaasFee = 0.0
        BigDecimal totalValue = 0.0

        Map paymentFeeFinancialTransactionSearch = [
            column: "id",
            paymentBillingTypeList: commissionRefundAdapter.billingTypeList,
            provider: commissionRefundAdapter.ownedAccount,
            transactionType: FinancialTransactionType.PAYMENT_FEE
        ]
        List<Map> customerCommissionItemDataList = []
        for (Map financialTransactionMap in commissionRefundAdapter.financialTransactionMapList) {
            paymentFeeFinancialTransactionSearch.paymentId = financialTransactionMap."payment.id"
            Long paymentFeeFinancialTransactionId = getPaymentFeeFinancialTransactionId(paymentFeeFinancialTransactionSearch)

            Map refundedCommissionItemMapSearch = [
                financialTransactionId: paymentFeeFinancialTransactionId,
                type: commissionRefundAdapter.refundedCommissionItemType
            ]

            Map refundedCommissionItemMap = CustomerCommissionItemRepository.query(refundedCommissionItemMapSearch).column(["percentage", "asaasFee", "value"]).get()
            if (!refundedCommissionItemMap?.value) continue

            BigDecimal asaasFee = Utils.toBigDecimal(refundedCommissionItemMap.asaasFee) * -1
            BigDecimal value = Utils.toBigDecimal(refundedCommissionItemMap.value) * -1
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
                type: commissionRefundAdapter.customerCommissionType.toString(),
                percentage: refundedCommissionItemMap.percentage,
                asaas_fee: asaasFee,
                value: value
            ]

            customerCommissionItemDataList.add(customerCommissionItemMap)
        }

        return new CustomerCommissionItemInsertDataListAdapter(customerCommissionItemDataList, totalAsaasFee, totalValue)
    }

    @CompileDynamic
    public Long getPaymentFeeFinancialTransactionId(Map paymentFeeFinancialTransactionSearch) {
        return FinancialTransaction.query(paymentFeeFinancialTransactionSearch).get()
    }
}
