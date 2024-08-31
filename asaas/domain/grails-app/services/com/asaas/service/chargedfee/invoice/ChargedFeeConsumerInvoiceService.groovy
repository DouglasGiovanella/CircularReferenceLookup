package com.asaas.service.chargedfee.invoice

import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.chargedfee.invoice.ChargedFeeConsumerInvoice
import com.asaas.domain.invoice.ConsumerInvoice

import grails.transaction.Transactional

@Transactional
class ChargedFeeConsumerInvoiceService {

    public ChargedFeeConsumerInvoice save(ConsumerInvoice consumerInvoice, ChargedFee chargedFee) {
        ChargedFeeConsumerInvoice chargedFeeConsumerInvoice = new ChargedFeeConsumerInvoice()
        chargedFeeConsumerInvoice.consumerInvoice = consumerInvoice
        chargedFeeConsumerInvoice.chargedFee = chargedFee

        return chargedFeeConsumerInvoice.save(failOnError: true)
    }
}
