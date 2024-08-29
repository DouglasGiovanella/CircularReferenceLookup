package com.asaas.service.customerexternalauthorization

import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestTransfer
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

import groovy.json.JsonSlurper

@Transactional
class CustomerExternalAuthorizationRequestTransferService {

    def creditTransferRequestAuthorizationService
    def internalTransferService
    def pixDebitAuthorizationService

    public void approve(CustomerExternalAuthorizationRequestTransfer externalAuthorizationRequestTransfer) {
        if (externalAuthorizationRequestTransfer.transfer.pixTransaction) {
            pixDebitAuthorizationService.onExternalAuthorizationApproved(externalAuthorizationRequestTransfer.transfer.pixTransaction)
        }

        if (externalAuthorizationRequestTransfer.transfer.creditTransferRequest) {
            creditTransferRequestAuthorizationService.onExternalAuthorizationApproved(externalAuthorizationRequestTransfer.transfer.creditTransferRequest)
        }

        if (externalAuthorizationRequestTransfer.transfer.internalTransfer) {
            internalTransferService.onExternalAuthorizationApproved(externalAuthorizationRequestTransfer.transfer.internalTransfer)
        }
    }

    public void refuse(CustomerExternalAuthorizationRequestTransfer externalAuthorizationRequestTransfer) {
        if (externalAuthorizationRequestTransfer.transfer.pixTransaction) {
            pixDebitAuthorizationService.onExternalAuthorizationRefused(externalAuthorizationRequestTransfer.transfer.pixTransaction)
        }

        if (externalAuthorizationRequestTransfer.transfer.creditTransferRequest) {
            creditTransferRequestAuthorizationService.onExternalAuthorizationRefused(externalAuthorizationRequestTransfer.transfer.creditTransferRequest)
        }

        if (externalAuthorizationRequestTransfer.transfer.internalTransfer) {
            internalTransferService.onExternalAuthorizationRefused(externalAuthorizationRequestTransfer.transfer.internalTransfer)
        }
    }

    public BusinessValidation validateStatus(CustomerExternalAuthorizationRequestTransfer externalAuthorizationRequestTransfer) {
        if (externalAuthorizationRequestTransfer.transfer.pixTransaction) {
            return pixDebitAuthorizationService.validateExternalAuthorizationTransferStatus(externalAuthorizationRequestTransfer.transfer.pixTransaction)
        }

        if (externalAuthorizationRequestTransfer.transfer.creditTransferRequest) {
            return creditTransferRequestAuthorizationService.validateExternalAuthorizationTransferStatus(externalAuthorizationRequestTransfer.transfer.creditTransferRequest)
        }

        if (externalAuthorizationRequestTransfer.transfer.internalTransfer) {
            return internalTransferService.validateExternalAuthorizationTransferStatus(externalAuthorizationRequestTransfer.transfer.internalTransfer)
        }

        throw new RuntimeException("O tipo da transferência [${externalAuthorizationRequestTransfer.transfer.id}] é inválido")
    }

    public Map buildRequestData(Long externalAuthorizationRequestId) {
        String eventData = CustomerExternalAuthorizationRequestTransfer.query([column: "data", externalAuthorizationRequestId: externalAuthorizationRequestId]).get()

        return [transfer: new JsonSlurper().parseText(eventData)]
    }
}
