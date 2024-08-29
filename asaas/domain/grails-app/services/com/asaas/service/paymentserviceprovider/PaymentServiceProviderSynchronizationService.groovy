package com.asaas.service.paymentserviceprovider

import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider
import com.asaas.paymentserviceprovider.adapter.PaymentServiceProviderAdapter
import com.asaas.pix.adapter.paymentserviceprovider.PaymentServiceProviderListAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentServiceProviderSynchronizationService {

    def financialInstitutionService
    def paymentServiceProviderService
    def hermesPaymentServiceProviderManagerService

    public void syncWithHermes() {
        PaymentServiceProviderListAdapter paymentServiceProviderListAdapter = hermesPaymentServiceProviderManagerService.list()

        for (PaymentServiceProviderAdapter paymentServiceProviderAdapter : paymentServiceProviderListAdapter.data) {
            Utils.withNewTransactionAndRollbackOnError({
                PaymentServiceProvider paymentServiceProvider = PaymentServiceProvider.query([ispb: paymentServiceProviderAdapter.ispb]).get()
                if (paymentServiceProvider) {
                    paymentServiceProvider = paymentServiceProviderService.update(paymentServiceProvider, paymentServiceProviderAdapter)
                    financialInstitutionService.updateNameIfNecessary(paymentServiceProvider)
                } else {
                    paymentServiceProviderService.save(paymentServiceProviderAdapter)
                }
            }, [logErrorMessage: "PaymentServiceProviderSynchronizationService.syncWithHermes() -> Erro ao sincronizar PaymentServiceProvider [ispb: ${paymentServiceProviderAdapter.ispb}]"])
        }
    }
}
