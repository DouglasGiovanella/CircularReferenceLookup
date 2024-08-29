package com.asaas.service.subscription

import grails.transaction.Transactional
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.SubscriptionFiscalConfig
import com.asaas.invoice.InvoiceFiscalConfigVO

@Transactional
class SubscriptionFiscalConfigService {

	public SubscriptionFiscalConfig save(Subscription subscription, InvoiceFiscalConfigVO invoiceFiscalConfigVo) {
        SubscriptionFiscalConfig subscriptionFiscalConfig = build(invoiceFiscalConfigVo)
        subscriptionFiscalConfig.subscription = subscription
        subscriptionFiscalConfig.save(failOnError: true)

        return subscriptionFiscalConfig
    }

    private SubscriptionFiscalConfig build(InvoiceFiscalConfigVO invoiceFiscalConfigVo) {
        SubscriptionFiscalConfig subscriptionFiscalConfig = new SubscriptionFiscalConfig()
        
        subscriptionFiscalConfig.invoiceCreationPeriod = invoiceFiscalConfigVo.invoiceCreationPeriod
        subscriptionFiscalConfig.receivedOnly = invoiceFiscalConfigVo.receivedOnly
        subscriptionFiscalConfig.invoiceFirstPaymentOnCreation = invoiceFiscalConfigVo.invoiceFirstPaymentOnCreation
        subscriptionFiscalConfig.daysBeforeDueDate = invoiceFiscalConfigVo.daysBeforeDueDate
        subscriptionFiscalConfig.updateRecurrentPaymentValues = invoiceFiscalConfigVo.updateRecurrentPaymentValues
        subscriptionFiscalConfig.observations = invoiceFiscalConfigVo.observations
        
        return subscriptionFiscalConfig
    }

    public SubscriptionFiscalConfig update(Long subscriptionFiscalConfigId, InvoiceFiscalConfigVO invoiceFiscalConfigVo) {
        SubscriptionFiscalConfig subscriptionFiscalConfig = SubscriptionFiscalConfig.get(subscriptionFiscalConfigId)
        if (!invoiceFiscalConfigVo) return subscriptionFiscalConfig
        
        subscriptionFiscalConfig.invoiceCreationPeriod = invoiceFiscalConfigVo.invoiceCreationPeriod
        subscriptionFiscalConfig.receivedOnly = invoiceFiscalConfigVo.receivedOnly
        subscriptionFiscalConfig.invoiceFirstPaymentOnCreation = invoiceFiscalConfigVo.invoiceFirstPaymentOnCreation
        subscriptionFiscalConfig.daysBeforeDueDate = invoiceFiscalConfigVo.daysBeforeDueDate
        subscriptionFiscalConfig.updateRecurrentPaymentValues = invoiceFiscalConfigVo.updateRecurrentPaymentValues
        subscriptionFiscalConfig.observations = invoiceFiscalConfigVo.observations

        subscriptionFiscalConfig.save(failOnError: true)

        return subscriptionFiscalConfig
    }

}
