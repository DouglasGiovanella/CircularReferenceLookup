package com.asaas.service.integration.pix

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.paymentserviceprovider.HermesPaymentServiceProviderListDTO
import com.asaas.pix.adapter.paymentserviceprovider.PaymentServiceProviderListAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HermesPaymentServiceProviderManagerService {

    public PaymentServiceProviderListAdapter list() {
        if (AsaasEnvironment.isDevelopment()) return new PaymentServiceProviderListAdapter(new MockJsonUtils("pix/HermesPaymentServiceProviderManagerService/list.json").buildMock(HermesPaymentServiceProviderListDTO))

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/psps", [:])

        if (hermesManager.isSuccessful()) {
            HermesPaymentServiceProviderListDTO paymentServiceProviderListDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesPaymentServiceProviderListDTO)
            return new PaymentServiceProviderListAdapter(paymentServiceProviderListDTO)
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public void triggerSync() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.get("/psps/triggerSync", [:])

        if (hermesManager.isSuccessful()) return

        throw new BusinessException(hermesManager.getErrorMessage())
    }
}
