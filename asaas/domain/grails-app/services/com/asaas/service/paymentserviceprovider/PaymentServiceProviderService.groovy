package com.asaas.service.paymentserviceprovider

import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider
import com.asaas.paymentserviceprovider.adapter.PaymentServiceProviderAdapter

import grails.transaction.Transactional

@Transactional
class PaymentServiceProviderService {

    public PaymentServiceProvider save(PaymentServiceProviderAdapter adapter) {
        PaymentServiceProvider paymentServiceProvider = new PaymentServiceProvider()
        paymentServiceProvider.ispb = adapter.ispb
        paymentServiceProvider.corporateName = adapter.corporateName
        paymentServiceProvider.shortName = adapter.shortName
        paymentServiceProvider.modality = adapter.modality
        paymentServiceProvider.type = adapter.type
        paymentServiceProvider.status = adapter.status
        paymentServiceProvider.save(failOnError: true)

        return paymentServiceProvider
    }

    public PaymentServiceProvider update(PaymentServiceProvider paymentServiceProvider, PaymentServiceProviderAdapter adapter) {
        paymentServiceProvider.corporateName = adapter.corporateName
        paymentServiceProvider.shortName = adapter.shortName
        paymentServiceProvider.modality = adapter.modality
        paymentServiceProvider.type = adapter.type
        paymentServiceProvider.status = adapter.status
        paymentServiceProvider.save(failOnError: true)

        return paymentServiceProvider
    }
}
