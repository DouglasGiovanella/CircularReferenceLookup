package com.asaas.service.pushnotification

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.domain.pushnotification.PushNotificationRequestPaymentEvent
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.http.HttpRequestStatusFilter
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.springframework.http.HttpStatus

@Transactional
class PushNotificationRequestAttemptService {

    public SequencedResultList<PushNotificationRequestAttempt> list(Customer customer, Map params, Integer limit, Integer offset) {
        Map search = parseParams(customer, params)

        if (params.containsKey("paymentId")) {
            Payment payment = Payment.find(params.paymentId, customer.id)

            List<Long> pushNotificationRequestIdList = PushNotificationRequestPaymentEvent.query([payment: payment, column: "pushNotificationRequest.id"]).list()

            if (pushNotificationRequestIdList) {
                search."pushNotificationRequestId[in]" = pushNotificationRequestIdList
            } else {
                search.paymentId = payment.id
            }
        }

        SequencedResultList<PushNotificationRequestAttempt> pushNotificationRequestAttemptList = SequencedResultList.build(
            PushNotificationRequestAttempt.query(search), limit, offset
        )

        return pushNotificationRequestAttemptList
    }

    public PushNotificationRequestAttempt save(Long pushNotificationRequestId, Long responseTime, Integer statusCode, String receivedData, String url, String errorDetail) {
        try {
            PushNotificationRequest pushNotificationRequest = PushNotificationRequest.read(pushNotificationRequestId)
            if (!pushNotificationRequest) return null

            Integer receivedDataMaxSize = PushNotificationRequestAttempt.constraints.receivedData.getMaxSize()
            receivedData = receivedData?.take(receivedDataMaxSize)

            PushNotificationRequestAttempt attempt = new PushNotificationRequestAttempt()
            attempt.pushNotificationRequest = pushNotificationRequest
            attempt.pushNotificationConfigId = pushNotificationRequest.config.id
            attempt.event = pushNotificationRequest.event
            attempt.provider = pushNotificationRequest.provider
            attempt.type = pushNotificationRequest.config.type
            attempt.application = pushNotificationRequest.config.application
            attempt.receivedStatus = statusCode
            attempt.responseTime = responseTime
            attempt.receivedData = StringUtils.replaceEmojis(receivedData, "")?.take(receivedDataMaxSize)
            attempt.url = url

            if (statusCode != HttpStatus.OK.value()) {
                attempt.errorDetail = errorDetail
            }

            return attempt.save(failOnError: true)
        } catch (Exception e) {
            AsaasLogger.error("PushNotificationRequestAttemptService.save >> Erro ao salvar tentativa de request no pushNotificationRequestId: ${pushNotificationRequestId} receivedData: ${receivedData}", e)
            return null
        }
    }

    private Map parseParams(Customer customer, Map params) {
        Map search = [:]

        if (params.containsKey("pushNotificationConfigId")) search.pushNotificationConfigId = Utils.toLong(params.pushNotificationConfigId)
        if (params.containsKey("dateCreated")) search.dateCreated = params.dateCreated
        if (params.containsKey("endDate")) search.endDate = params.endDate
        if (params.containsKey("invoiceId")) search.invoiceId = params.invoiceId
        if (params.containsKey("webhooksStatus")) {
            HttpRequestStatusFilter statusFilter = HttpRequestStatusFilter.convert(params.webhooksStatus)
            if (statusFilter.isAnyError()) {
                search."receivedStatus[notIn]" = HttpRequestStatusFilter.listSuccess().collect { it.value }
            } else {
                search.receivedStatus = statusFilter.value
            }
        }

        if (params.containsKey("childAccountPublicId")) {
            search.customerPublicId = params.childAccountPublicId
            search.accountOwnerId = customer.id
        } else {
            search.customer = customer
        }

        return search
    }
}
