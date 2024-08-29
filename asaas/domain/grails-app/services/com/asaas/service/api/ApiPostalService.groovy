package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.billinginfo.ChargeType
import com.asaas.domain.city.City
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.postalservice.PostalServiceSendingError
import com.asaas.correios.CorreiosDeliveryTime
import com.asaas.postalservice.PaymentPostalServiceFee
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.service.api.ApiResponseBuilder
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiPostalService extends ApiBaseService {

    def paymentService
    def subscriptionService
    def installmentService

    def validate(params) {
        Customer customer = getProviderInstance(params) ?: new Customer()

        if (!params.containsKey("cityId")) {
            throw new BusinessException("O campo cityId é obrigatório.")
        }

        City city = City.findById(params.cityId.toLong())

        Date dueDate = ApiBaseParser.parseDate(params.dueDate)

        Date estimatedDeliveryDate = CorreiosDeliveryTime.calculateEstimatedDeliveryDate(city, dueDate)

        List<PostalServiceSendingError> reasons = new PaymentPostalServiceValidator(customer).validate()

        if (estimatedDeliveryDate > dueDate) reasons.add(PostalServiceSendingError.DELIVERY_OVERDUE)

        Map responseMap = [:]

        responseMap.success = true

        if (reasons) {
            responseMap.success = false
            responseMap.failReasons = []

            for (PostalServiceSendingError reason : reasons) {
                List args = [CustomDateUtils.fromDate(estimatedDeliveryDate)]
                responseMap.failReasons.add([code: reason.toString(), description: Utils.getMessageProperty("postal.service.sending.error.${reason}.description", args)])
            }
        }

        responseMap.fee = PaymentPostalServiceFee.getFee(customer)
        responseMap.estimatedDeliveryDate = CustomDateUtils.fromDate(estimatedDeliveryDate)

        if (params.containsKey("chargeType") && params.containsKey("cycle") && ChargeType.convert(params.chargeType) == ChargeType.RECURRENT) {
            responseMap.nextDueDate = CustomDateUtils.fromDate(subscriptionService.calculateNextDueDateForSubscription(CustomDateUtils.fromDate(dueDate), params.cycle, CustomDateUtils.getDayOfDate(dueDate).toInteger()).nextDueDate)
        } else if (ChargeType.convert(params.chargeType) == ChargeType.INSTALLMENT) {
            responseMap.nextDueDate = CustomDateUtils.fromDate(installmentService.calculateNextDueDate(dueDate, 1))
        }

        return ApiResponseBuilder.buildSuccess(responseMap)
    }

    def send(params) {
        Payment payment = Payment.find(params.paymentId, getProvider(params))

        if (Boolean.valueOf(params.resend)) {
            payment = paymentService.resendByPostalService(payment.id, getProvider(params))
        } else {
            payment = paymentService.sendByPostalService(payment, [sendAllInstallments: params.sendAllInstallments?.toBoolean()])
        }

        if (payment.hasErrors()) return ApiResponseBuilder.buildBadRequest(payment)

        Date estimatedDeliveryDate = CorreiosDeliveryTime.calculateEstimatedDeliveryDate(payment.customerAccount.city, payment.dueDate)
        List args = [CustomDateUtils.fromDate(estimatedDeliveryDate)]
        return ApiResponseBuilder.buildSuccess([message: Utils.getMessageProperty("payment.postalService.scheduled.success", args)] << getPaymentPostalServiceInfo(payment))
    }

    def cancel(params) {
        Payment payment = Payment.find(params.paymentId, getProvider(params))
        payment = paymentService.cancelSendByPostalService(payment)

        if (payment.hasErrors()) return ApiResponseBuilder.buildBadRequest(payment)

        return ApiResponseBuilder.buildSuccess([message: Utils.getMessageProperty("postal.service.cancelled.success")] << getPaymentPostalServiceInfo(payment))
    }

    public String getFailReasonDescription(PostalServiceSendingError reason, CustomerAccount customerAccount) {
        if (reason == PostalServiceSendingError.PROVIDER_INFO_INCOMPLETE) {
            return "Seus dados de endereço são insuficientes para o envio. Sugerimos que informe os dados de endereço."
        }

        if (reason == PostalServiceSendingError.CUSTOMER_ACCOUNT_INFO_INCOMPLETE) {
            return "Os dados do cliente ${customerAccount.name} são insuficientes para o envio. Sugerimos que informe os dados de endereço do cliente."
        }

        if (reason == PostalServiceSendingError.DELIVERY_OVERDUE) {
            return "A estimativa de entrega via Correios para essa cobrança é superior a data de vencimento informada. Sugerimos que informe uma data de vencimento igual ou superior a ${CustomDateUtils.fromDate(CorreiosDeliveryTime.calculateEstimatedDeliveryDate(customerAccount.city))} para que sua cobrança seja entregue ao seu cliente até o vencimento."
        }

        if (reason == PostalServiceSendingError.INVALID_POSTAL_CODE) {
            return "O CEP do cliente ${customerAccount.name} é inválido. Faça a alteração para um CEP válido."
        }

        if (reason == PostalServiceSendingError.INSUFFICIENT_BALANCE) {
            return "O seu saldo é insuficiente para cobrir os custos de envio via Correios"
        }

        if (reason == PostalServiceSendingError.PROVIDER_AWAITING_ACTIVATION) {
            return Utils.getMessageProperty("postal.service.sending.error.PROVIDER_AWAITING_ACTIVATION.description")
        }
    }

    private Map getPaymentPostalServiceInfo(Payment payment) {
        Map info = [:]

        if (payment.postalServiceStatus) {
            info.status = payment.postalServiceStatus?.toString()
            info.statusDescription = payment.postalServiceStatus.getDescription()

            if (payment.postalServiceStatus in [PostalServiceStatus.AWAITING_TO_BE_PROCESSED, PostalServiceStatus.PROCESSED, PostalServiceStatus.PENDING]) {
                info.estimateDeliveryDate = CorreiosDeliveryTime.getEstimatedDeliveryDate(payment)
            }

            info.canBeResent = payment.canBeResentByPostalService()
            info.canCancelSend = payment.canCancelSendByPostalService()
        } else {
            info.canBeSent = payment.canBeSentByPostalService()
        }

        return info
    }
}
