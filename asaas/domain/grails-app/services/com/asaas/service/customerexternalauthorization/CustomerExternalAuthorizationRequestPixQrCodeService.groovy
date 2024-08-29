package com.asaas.service.customerexternalauthorization

import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestPixQrCode
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

import groovy.json.JsonSlurper

@Transactional
class CustomerExternalAuthorizationRequestPixQrCodeService {

    def pixDebitAuthorizationService

    public void approve(CustomerExternalAuthorizationRequestPixQrCode externalAuthorizationRequestPixQrCode) {
        pixDebitAuthorizationService.onExternalAuthorizationApproved(externalAuthorizationRequestPixQrCode.pixTransaction)
    }

    public void refuse(CustomerExternalAuthorizationRequestPixQrCode externalAuthorizationRequestPixQrCode) {
        pixDebitAuthorizationService.onExternalAuthorizationRefused(externalAuthorizationRequestPixQrCode.pixTransaction)
    }

    public BusinessValidation validateStatus(CustomerExternalAuthorizationRequestPixQrCode externalAuthorizationRequestPixQrCode) {
        if (externalAuthorizationRequestPixQrCode.pixTransaction) {
            return pixDebitAuthorizationService.validateExternalAuthorizationTransferStatus(externalAuthorizationRequestPixQrCode.pixTransaction)
        }

        throw new RuntimeException("o tipo da transação [${externalAuthorizationRequestPixQrCode.id}] é inválido")
    }

    public Map buildRequestData(Long externalAuthorizationRequestId) {
        String eventData = CustomerExternalAuthorizationRequestPixQrCode.query([column: "data", externalAuthorizationRequestId: externalAuthorizationRequestId]).get()

        return [pixQrCode: new JsonSlurper().parseText(eventData)]
    }
}
