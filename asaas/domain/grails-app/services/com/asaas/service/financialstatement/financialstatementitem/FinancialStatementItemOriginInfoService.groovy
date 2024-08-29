package com.asaas.service.financialstatement.financialstatementitem

import com.asaas.domain.customer.Customer
import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.domain.financialstatement.FinancialStatementItemOriginInfo
import com.asaas.domain.payment.Payment
import com.asaas.financialstatementitem.FinancialStatementItemBuilder
import com.asaas.financialstatementitempayment.FinancialStatementItemPaymentOrigin
import com.asaas.utils.DatabaseBatchUtils
import grails.transaction.Transactional

@Transactional
class FinancialStatementItemOriginInfoService {

    def dataSource

    public FinancialStatementItemOriginInfo buildFinancialStatementItemOriginInfo(FinancialStatementItem financialStatementItem, Payment payment, Customer customer) {
        FinancialStatementItemOriginInfo financialStatementItemOriginInfo = new FinancialStatementItemOriginInfo()
        financialStatementItemOriginInfo.financialStatementItem = financialStatementItem
        financialStatementItemOriginInfo.payment = payment
        financialStatementItemOriginInfo.customer = customer

        return financialStatementItemOriginInfo
    }

    public Map buildValuesToInsertOriginInfo(FinancialStatementItem financialStatementItem) {
        if (!financialStatementItem) return

        Map valuesToReturn = [payment: null, customer: null, financialStatementItemPaymentOrigin: null]

        if (financialStatementItem.payment) {
            valuesToReturn.payment = financialStatementItem.payment
            valuesToReturn.customer = financialStatementItem.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.FINANCIAL_STATEMENT_ITEM

            return valuesToReturn
        }

        if (financialStatementItem.financialTransaction?.payment) {
            valuesToReturn.payment = financialStatementItem.financialTransaction.payment
            valuesToReturn.customer = financialStatementItem.financialTransaction.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.FINANCIAL_TRANSACTION

            return valuesToReturn
        }

        if (financialStatementItem.receivableAnticipation?.payment) {
            valuesToReturn.payment = financialStatementItem.receivableAnticipation.payment
            valuesToReturn.customer = financialStatementItem.receivableAnticipation.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.RECEIVABLE_ANTICIPATION

            return valuesToReturn
        }

        if (financialStatementItem.creditCardAcquirerOperation?.payment) {
            valuesToReturn.payment = financialStatementItem.creditCardAcquirerOperation.payment
            valuesToReturn.customer = financialStatementItem.creditCardAcquirerOperation.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.CREDIT_CARD_ACQUIRER_OPERATION

            return valuesToReturn
        }

        if (financialStatementItem.chargeback?.payment) {
            valuesToReturn.payment = financialStatementItem.chargeback.payment
            valuesToReturn.customer = financialStatementItem.chargeback.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.CHARGEBACK

            return valuesToReturn
        }

        if (financialStatementItem.debit?.payment) {
            valuesToReturn.payment = financialStatementItem.debit.payment
            valuesToReturn.customer = financialStatementItem.debit.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.DEBIT

            return valuesToReturn
        }

        if (financialStatementItem.refundRequest?.payment) {
            valuesToReturn.payment = financialStatementItem.refundRequest.payment
            valuesToReturn.customer = financialStatementItem.refundRequest.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.REFUND_REQUEST

            return valuesToReturn
        }

        if (financialStatementItem.promotionalCodeUse?.payment) {
            valuesToReturn.payment = financialStatementItem.promotionalCodeUse.payment
            valuesToReturn.customer = financialStatementItem.promotionalCodeUse.payment.provider
            valuesToReturn.financialStatementItemPaymentOrigin = FinancialStatementItemPaymentOrigin.PROMOTIONAL_CODE_USE

            return valuesToReturn
        }

        Customer customer = FinancialStatementItemBuilder.getRelatedCustomer(financialStatementItem)
        valuesToReturn.customer = customer

        return valuesToReturn
    }

    public void update(FinancialStatementItemOriginInfo financialStatementItemOriginInfo, Payment payment, Customer customer) {
        financialStatementItemOriginInfo.payment = payment
        financialStatementItemOriginInfo.customer = customer
        financialStatementItemOriginInfo.save(failOnError: true)
    }

    public void insertOriginInfoInBatch(List<FinancialStatementItemOriginInfo> items) {
        List<Map> originInfoToInsert = items.collect { FinancialStatementItemOriginInfo originInfo ->
            originInfo.discard()
            return [
                "version": "0",
                "customer_id": originInfo.customerId,
                "date_created": new Date(),
                "deleted": 0,
                "financial_statement_item_id": originInfo.financialStatementItemId,
                "last_updated": new Date(),
                "payment_id": originInfo.paymentId
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "financial_statement_item_origin_info", originInfoToInsert)
    }
}
