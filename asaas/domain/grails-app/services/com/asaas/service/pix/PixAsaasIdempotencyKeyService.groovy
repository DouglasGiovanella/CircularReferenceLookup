package com.asaas.service.pix

import com.asaas.domain.pix.PixAsaasIdempotencyKey
import com.asaas.domain.pix.PixTransaction
import com.asaas.pix.PixAsaasIdempotencyKeyType

import grails.transaction.Transactional

@Transactional
class PixAsaasIdempotencyKeyService {

    public Boolean hasIdempotencyKey(PixTransaction transaction, PixAsaasIdempotencyKeyType type) {
        return PixAsaasIdempotencyKey.query([exists: true, pixTransaction: transaction, type: type]).get().asBoolean()
    }

    public String getIdempotencyKey(PixTransaction transaction, PixAsaasIdempotencyKeyType type) {
        PixAsaasIdempotencyKey idempotency = PixAsaasIdempotencyKey.query([pixTransaction: transaction, type: type]).get()
        if (idempotency) return idempotency.idempotencyKey

        throw new RuntimeException("Chave de idempotência não encontrada para a Transação Pix. [pixTransaction.id ${transaction.id}, type: ${type}]")
    }

    public PixAsaasIdempotencyKey save(PixTransaction transaction, PixAsaasIdempotencyKeyType type) {
        PixAsaasIdempotencyKey idempotency = create()
        idempotency.pixTransaction = transaction
        idempotency.type = type
        idempotency.save(failOnError: true)
        return idempotency
    }

    private PixAsaasIdempotencyKey create() {
        PixAsaasIdempotencyKey idempotency = new PixAsaasIdempotencyKey()
        idempotency.idempotencyKey = UUID.randomUUID().toString()
        return idempotency
    }

}
