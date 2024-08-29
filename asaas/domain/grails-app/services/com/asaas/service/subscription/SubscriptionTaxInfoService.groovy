package com.asaas.service.subscription

import grails.transaction.Transactional
import com.asaas.invoice.InvoiceTaxInfoVO
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.SubscriptionTaxInfo

@Transactional
class SubscriptionTaxInfoService {

    public SubscriptionTaxInfo save(Subscription subscription, InvoiceTaxInfoVO taxInfoVo) {
        SubscriptionTaxInfo subscriptionTaxInfo = build(taxInfoVo)
        subscriptionTaxInfo.subscription = subscription
        subscriptionTaxInfo.save(failOnError: true)

        return subscriptionTaxInfo
    }

    private SubscriptionTaxInfo build(InvoiceTaxInfoVO taxInfoVo) {
        SubscriptionTaxInfo subscriptionTaxInfo = new SubscriptionTaxInfo()

        subscriptionTaxInfo.retainIss = taxInfoVo.retainIss
        subscriptionTaxInfo.issTax = taxInfoVo.issTax
        subscriptionTaxInfo.cofinsPercentage = taxInfoVo.cofinsPercentage
        subscriptionTaxInfo.csllPercentage = taxInfoVo.csllPercentage
        subscriptionTaxInfo.inssPercentage = taxInfoVo.inssPercentage
        subscriptionTaxInfo.irPercentage = taxInfoVo.irPercentage
        subscriptionTaxInfo.pisPercentage = taxInfoVo.pisPercentage
        subscriptionTaxInfo.invoiceValue = taxInfoVo.invoiceValue
        subscriptionTaxInfo.deductions = taxInfoVo.deductions

        return subscriptionTaxInfo
    }

    public SubscriptionTaxInfo update(Long subscriptionTaxInfoId, InvoiceTaxInfoVO taxInfoVo) {
        SubscriptionTaxInfo subscriptionTaxInfo = SubscriptionTaxInfo.get(subscriptionTaxInfoId)
        if (!taxInfoVo) return subscriptionTaxInfo

        if (taxInfoVo.retainIss != null) subscriptionTaxInfo.retainIss = taxInfoVo.retainIss
        if (taxInfoVo.issTax != null) subscriptionTaxInfo.issTax = taxInfoVo.issTax
        if (taxInfoVo.cofinsPercentage != null) subscriptionTaxInfo.cofinsPercentage = taxInfoVo.cofinsPercentage
        if (taxInfoVo.csllPercentage != null) subscriptionTaxInfo.csllPercentage = taxInfoVo.csllPercentage
        if (taxInfoVo.inssPercentage != null) subscriptionTaxInfo.inssPercentage = taxInfoVo.inssPercentage
        if (taxInfoVo.irPercentage != null) subscriptionTaxInfo.irPercentage = taxInfoVo.irPercentage
        if (taxInfoVo.pisPercentage != null) subscriptionTaxInfo.pisPercentage = taxInfoVo.pisPercentage
        if (taxInfoVo.invoiceValue != null) subscriptionTaxInfo.invoiceValue = taxInfoVo.invoiceValue
        if (taxInfoVo.deductions != null) subscriptionTaxInfo.deductions = taxInfoVo.deductions
        
        subscriptionTaxInfo.save(failOnError: true)

        return subscriptionTaxInfo
    }

    public void updateIssTax(Subscription subscription, BigDecimal issTax) {
        SubscriptionTaxInfo subscriptionTaxInfo = subscription.getTaxInfo()
        if (!subscriptionTaxInfo) return

        subscriptionTaxInfo.issTax = issTax
        subscriptionTaxInfo.save(failOnError: true)
    }
}
