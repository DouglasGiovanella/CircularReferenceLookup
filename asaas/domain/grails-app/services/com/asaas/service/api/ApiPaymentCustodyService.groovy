package com.asaas.service.api

import com.asaas.api.ApiPaymentParser
import com.asaas.api.ApiPaymentCustodyParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentcustody.PaymentCustody
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.paymentcustody.PaymentCustodyFinishReason
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ApiPaymentCustodyService extends ApiBaseService {

    def apiResponseBuilderService
    def paymentCustodyService

    public Map finish(Map params) {
        Customer customer = getProviderInstance(params)

        PaymentCustody paymentCustody = PaymentCustody.query([publicId: params.id, custodianCustomer: customer]).get()
        if (!paymentCustody) return apiResponseBuilderService.buildNotFoundItem()

        paymentCustodyService.finish(paymentCustody, PaymentCustodyFinishReason.REQUESTED_BY_CUSTOMER, params.walletId)

        return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(paymentCustody.payment, [:]))
    }

    public Map setDaysToExpirePaymentCustody(Map params) {
        Customer customer = getProviderInstance(params)
        if (customer.accountOwner) throw new BusinessException("Apenas contas Pai são habilitadas a configurar o período de custódia!")

        paymentCustodyService.saveDaysToExpirePaymentCustodyForCustomer(customer, Utils.toBigDecimal(params.daysToExpirePaymentCustody))

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map find(Map params) {
        Long customerId = getProvider(params)
        Long paymentId = Payment.validateOwnerAndRetrieveId(params.id, customerId)

        PaymentCustody paymentCustody = PaymentCustody.query([paymentId: paymentId, readOnly: true]).get()
        if (!paymentCustody) {
            throw new ResourceNotFoundException("Custódia Inexistente")
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentCustodyParser.buildResponseItem(paymentCustody))
    }
}
