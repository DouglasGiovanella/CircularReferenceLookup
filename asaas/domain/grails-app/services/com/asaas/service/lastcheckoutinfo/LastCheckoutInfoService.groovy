package com.asaas.service.lastcheckoutinfo

import com.asaas.domain.customer.Customer
import com.asaas.domain.lastcheckoutinfo.LastCheckoutInfo
import com.asaas.exception.CheckoutException
import com.asaas.log.AsaasLogger
import datadog.trace.api.Trace
import grails.transaction.Transactional

@Transactional
class LastCheckoutInfoService {

    @Trace(resourceName = "LastCheckoutInfoService.save")
	public LastCheckoutInfo save(Customer customer) {
        try {
            LastCheckoutInfo lastCheckoutInfo = LastCheckoutInfo.query([customer: customer]).get()
            if (!lastCheckoutInfo) lastCheckoutInfo = new LastCheckoutInfo(customer: customer)

            lastCheckoutInfo.lastUpdated = new Date()
            lastCheckoutInfo.save(flush: true, failOnError: true)

            return lastCheckoutInfo
        } catch (Exception e) {
            AsaasLogger.warn("LastCheckoutInfoService.save -> Falha ao atualizar lastCheckoutInfo do customer: ${customer.id}", e)
            throw new CheckoutException("Não foi possível concluir a transação. Tente novamente.")
        }
	}
}
