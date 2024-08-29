package com.asaas.service.invoice

import grails.transaction.Transactional
import com.asaas.domain.invoice.InvoiceTaxInfo
import com.asaas.domain.invoice.Invoice
import com.asaas.invoice.InvoiceTaxInfoVO
import com.asaas.utils.Utils

@Transactional
class InvoiceTaxInfoService {

    public InvoiceTaxInfo save(Invoice invoice, InvoiceTaxInfoVO taxInfoVo) {
        InvoiceTaxInfo invoiceTaxInfo = build(taxInfoVo)
        invoiceTaxInfo.invoice = invoice
        invoiceTaxInfo.save(failOnError: true)
        invoice.taxInfo = invoiceTaxInfo

        return invoiceTaxInfo
    }

    public InvoiceTaxInfo update(Long invoiceTaxInfoId, Map invoiceParams) {
        if (!invoiceParams.taxInfo) return

        InvoiceTaxInfo taxInfo = InvoiceTaxInfo.get(invoiceTaxInfoId)
        if (invoiceParams.taxInfo.retainIss != null) taxInfo.retainIss = Boolean.valueOf(invoiceParams.taxInfo.retainIss)
        if (invoiceParams.taxInfo.cofinsPercent != null) taxInfo.cofinsPercentage = Utils.toBigDecimal(invoiceParams.taxInfo.cofinsPercent)
        if (invoiceParams.taxInfo.csllPercent != null) taxInfo.csllPercentage = Utils.toBigDecimal(invoiceParams.taxInfo.csllPercent)
        if (invoiceParams.taxInfo.inssPercent != null) taxInfo.inssPercentage = Utils.toBigDecimal(invoiceParams.taxInfo.inssPercent)
        if (invoiceParams.taxInfo.irPercent != null) taxInfo.irPercentage = Utils.toBigDecimal(invoiceParams.taxInfo.irPercent)
        if (invoiceParams.taxInfo.pisPercent != null) taxInfo.pisPercentage = Utils.toBigDecimal(invoiceParams.taxInfo.pisPercent)
        if (invoiceParams.taxInfo.issTax != null) taxInfo.issTax = Utils.toBigDecimal(invoiceParams.taxInfo.issTax)

        taxInfo.save(failOnError: true)

        return taxInfo
    }

    private InvoiceTaxInfo build(InvoiceTaxInfoVO taxInfoVo) {
        InvoiceTaxInfo invoiceTaxInfo = new InvoiceTaxInfo()

        invoiceTaxInfo.retainIss = taxInfoVo.retainIss
        invoiceTaxInfo.issTax = taxInfoVo.issTax

        invoiceTaxInfo.cofinsPercentage = taxInfoVo.cofinsPercentage
        invoiceTaxInfo.csllPercentage = taxInfoVo.csllPercentage
        invoiceTaxInfo.inssPercentage = taxInfoVo.inssPercentage
        invoiceTaxInfo.irPercentage = taxInfoVo.irPercentage
        invoiceTaxInfo.pisPercentage = taxInfoVo.pisPercentage

        invoiceTaxInfo.cofinsValue = taxInfoVo.cofinsValue
        invoiceTaxInfo.csllValue = taxInfoVo.csllValue
        invoiceTaxInfo.inssValue = taxInfoVo.inssValue
        invoiceTaxInfo.irValue = taxInfoVo.irValue
        invoiceTaxInfo.pisValue = taxInfoVo.pisValue

        return invoiceTaxInfo
    }

    public void updateIssTax(Invoice invoice, BigDecimal issTax) {
        invoice.taxInfo.issTax = issTax
        invoice.taxInfo.save(failOnError: true)
    }
}
