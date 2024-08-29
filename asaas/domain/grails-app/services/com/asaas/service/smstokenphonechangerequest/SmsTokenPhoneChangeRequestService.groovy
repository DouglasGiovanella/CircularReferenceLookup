package com.asaas.service.smstokenphonechangerequest

import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.smstokenphonechangerequest.SmsTokenPhoneChangeRequest
import com.asaas.smstokenphonechangerequest.SmsTokenPhoneChangeRequestStatus
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class SmsTokenPhoneChangeRequestService {

    def customerAlertNotificationService
    def mobilePushNotificationService

    public SmsTokenPhoneChangeRequest save(String newPhoneNumber, AsaasFile asaasFile) {
        SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest = new SmsTokenPhoneChangeRequest()

        Boolean alreadyHaveRequestInAnalysis = SmsTokenPhoneChangeRequest.query([exists: true, customer: asaasFile.customer, status: SmsTokenPhoneChangeRequestStatus.AWAITING_APPROVAL]).get()
        if (alreadyHaveRequestInAnalysis) {
            DomainUtils.addError(smsTokenPhoneChangeRequest, "Você já possui uma solicitação em análise.")
            return smsTokenPhoneChangeRequest
        }

        AuthorizationDevice authorizationDevice = AuthorizationDevice.active([customer: asaasFile.customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()
        if (!authorizationDevice) {
            DomainUtils.addError(smsTokenPhoneChangeRequest, "Você não possui nenhum celular registrado para solicitar a troca.")
            return smsTokenPhoneChangeRequest
        }

        smsTokenPhoneChangeRequest.status = SmsTokenPhoneChangeRequestStatus.AWAITING_APPROVAL
        smsTokenPhoneChangeRequest.currentPhoneNumber = authorizationDevice.phoneNumber
        smsTokenPhoneChangeRequest.newPhoneNumber = newPhoneNumber
        smsTokenPhoneChangeRequest.customer = asaasFile.customer
        smsTokenPhoneChangeRequest.file = asaasFile
        smsTokenPhoneChangeRequest.save(failOnError: true)

        return smsTokenPhoneChangeRequest
    }

    public SmsTokenPhoneChangeRequest delete(Long id, Customer customer) {
        SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest = SmsTokenPhoneChangeRequest.find(id, customer)

        smsTokenPhoneChangeRequest.deleted = true
        smsTokenPhoneChangeRequest.save(failOnError: true)

        return smsTokenPhoneChangeRequest
    }

    public SmsTokenPhoneChangeRequest approve(Long id, Customer customer) {
        SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest = SmsTokenPhoneChangeRequest.find(id, customer)

        if (smsTokenPhoneChangeRequest.status != SmsTokenPhoneChangeRequestStatus.AWAITING_APPROVAL) {
            DomainUtils.addError(smsTokenPhoneChangeRequest, "Esta solicitação de troca de celular principal não está pendente.")
            return smsTokenPhoneChangeRequest
        }

        smsTokenPhoneChangeRequest.status = SmsTokenPhoneChangeRequestStatus.APPROVED
        smsTokenPhoneChangeRequest.save(failOnError: true)

        AuthorizationDevice authorizationDevice = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()
        authorizationDevice.phoneNumber = smsTokenPhoneChangeRequest.newPhoneNumber
        authorizationDevice.save(failOnError: true)

        mobilePushNotificationService.notifySmsTokenPhoneChangeRequestResult(smsTokenPhoneChangeRequest)
        customerAlertNotificationService.notifySmsTokenPhoneChangeRequestResult(smsTokenPhoneChangeRequest)

        return smsTokenPhoneChangeRequest
    }

    public SmsTokenPhoneChangeRequest reject(Long id, Customer customer, String rejectReason, String observations) {
        SmsTokenPhoneChangeRequest smsTokenPhoneChangeRequest = SmsTokenPhoneChangeRequest.find(id, customer)

        if (smsTokenPhoneChangeRequest.status != SmsTokenPhoneChangeRequestStatus.AWAITING_APPROVAL) {
            DomainUtils.addError(smsTokenPhoneChangeRequest, "Esta solicitação de troca de celular principal não está pendente.")
            return smsTokenPhoneChangeRequest
        }

        smsTokenPhoneChangeRequest.status = SmsTokenPhoneChangeRequestStatus.REJECTED
        smsTokenPhoneChangeRequest.rejectReason = rejectReason
        smsTokenPhoneChangeRequest.observations = observations
        smsTokenPhoneChangeRequest.save(failOnError: true)

        mobilePushNotificationService.notifySmsTokenPhoneChangeRequestResult(smsTokenPhoneChangeRequest)
        customerAlertNotificationService.notifySmsTokenPhoneChangeRequestResult(smsTokenPhoneChangeRequest)

        return smsTokenPhoneChangeRequest
    }
}