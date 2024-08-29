package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionDestinationKey
import com.asaas.pix.PixAddressKeyType

import grails.transaction.Transactional

@Transactional
class PixTransactionDestinationKeyService {

    public PixTransactionDestinationKey save(PixTransaction pixTransaction, String pixKey, PixAddressKeyType pixKeyType) {
        PixTransactionDestinationKey pixTransactionDestinationKey = new PixTransactionDestinationKey()
        pixTransactionDestinationKey.pixTransaction = pixTransaction
        pixTransactionDestinationKey.pixKey = pixKey
        pixTransactionDestinationKey.pixAddressKeyType = pixKeyType
        pixTransactionDestinationKey.save(failOnError: true)

        return pixTransactionDestinationKey
    }
}
