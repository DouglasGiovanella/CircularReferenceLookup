package com.asaas.service.invoice

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.invoice.InvoiceItem
import com.asaas.domain.planpayment.PlanPayment
import com.asaas.invoice.InvoiceItemType
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class InvoiceItemService {

    public InvoiceItem save(FinancialTransaction financialTransaction) {
        if (financialTransaction.provider?.isAsaasProvider()) return

        InvoiceItem invoiceItem = new InvoiceItem()
        invoiceItem.financialTransaction = financialTransaction
        invoiceItem.value = financialTransaction.value.abs()
        invoiceItem.type = invoiceItem.getInvoiceItemType(financialTransaction)
        invoiceItem.billedCustomer = financialTransaction.provider

        return invoiceItem.save(flush: true, failOnError: true)
    }

    public InvoiceItem saveForPlanPayment(PlanPayment planPayment) {
        if (!planPayment) return

        InvoiceItem invoiceItem = new InvoiceItem()
        invoiceItem.planPayment = planPayment
        invoiceItem.value = planPayment.payment.value.abs()
        invoiceItem.type = InvoiceItemType.PLAN_FEE
        invoiceItem.billedCustomer = planPayment.payment.customerAccount.customer
        return invoiceItem.save(flush: true, failOnError: true)
    }

    public InvoiceItem delete(FinancialTransaction financialTransaction) {
        if (!financialTransaction) return
        if (financialTransaction.provider?.isAsaasProvider()) return

        InvoiceItem invoiceItem = InvoiceItem.query([financialTransaction: financialTransaction]).get()
        if (!invoiceItem) return

        if (CustomDateUtils.getFirstDayOfMonth(invoiceItem.dateCreated).clearTime() != CustomDateUtils.getFirstDayOfCurrentMonth().clearTime()) return

        invoiceItem.deleted = true
        invoiceItem.save(flush: true, failOnError: true)

        return invoiceItem
    }

}
