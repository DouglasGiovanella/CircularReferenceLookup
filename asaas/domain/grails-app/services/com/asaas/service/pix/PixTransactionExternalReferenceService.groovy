package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionExternalReference

import grails.transaction.Transactional

@Transactional
class PixTransactionExternalReferenceService {

    public void save(PixTransaction pixTransaction, String externalReference) {
        PixTransactionExternalReference pixTransactionExternalReference = new PixTransactionExternalReference()
        pixTransactionExternalReference.pixTransaction = pixTransaction
        pixTransactionExternalReference.externalReference = externalReference
        pixTransactionExternalReference.save(failOnError: true)
    }
}
