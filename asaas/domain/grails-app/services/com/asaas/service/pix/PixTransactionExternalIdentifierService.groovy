package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionExternalIdentifier
import com.asaas.exception.BusinessException
import com.asaas.utils.StringUtils

import grails.transaction.Transactional

@Transactional
class PixTransactionExternalIdentifierService {

    public void save(PixTransaction pixTransaction, String externalIdentifier) {
        validate(pixTransaction, externalIdentifier)

        PixTransactionExternalIdentifier pixTransactionExternalIdentifier = new PixTransactionExternalIdentifier()
        pixTransactionExternalIdentifier.customer = pixTransaction.customer
        pixTransactionExternalIdentifier.pixTransaction = pixTransaction
        pixTransactionExternalIdentifier.externalIdentifier = externalIdentifier
        pixTransactionExternalIdentifier.save(failOnError: true)
    }

    private void validate(PixTransaction pixTransaction, String externalIdentifier) {
        if (!pixTransaction.type.isCreditRefund()) throw new BusinessException("Apenas transações de devolução podem ser associadas a um Id externo.")
        if (!externalIdentifier) throw new BusinessException("Necessário informar o Id da devolução.")

        Boolean isAlphaNumeric = StringUtils.isAlphaNumeric(externalIdentifier)
        if (!isAlphaNumeric) throw new BusinessException("O Id da devolução só pode conter letras e números.")

        Integer externalIdentifierMaxSize = PixTransactionExternalIdentifier.constraints.externalIdentifier.maxSize
        Integer externalIdentifierMinSize = PixTransactionExternalIdentifier.constraints.externalIdentifier.minSize
        Integer externalIdentifierSize = externalIdentifier.size()
        Boolean isValidSize = externalIdentifierSize <= externalIdentifierMaxSize && externalIdentifierSize >= externalIdentifierMinSize
        if (!isValidSize) throw new BusinessException("O Id da devolução pode conter no máximo ${externalIdentifierMaxSize} caracteres e no mínimo ${externalIdentifierMinSize} caractere.")

        Boolean alreadyExists = PixTransactionExternalIdentifier.query([exists: true, customerId: pixTransaction.customer.id, externalIdentifier: externalIdentifier]).get()
        if (alreadyExists) throw new BusinessException("O Id da devolução já foi utilizado.")
    }
}
