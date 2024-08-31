package com.asaas.service.pix

import com.asaas.domain.pix.PixExternalIdempotencyKey
import com.asaas.exception.BusinessException
import grails.transaction.Transactional

@Transactional
class PixExternalIdempotencyKeyService {

    public PixExternalIdempotencyKey save(String idempotencyKey, String requestBody) {
        if (idempotencyKey.length() != PixExternalIdempotencyKey.IDEMPOTENCY_KEY_SIZE) throw new BusinessException("Tamanho incorreto para chave de idempotência.")

        Map encrypted = PixExternalIdempotencyKey.encrypt(requestBody)
        PixExternalIdempotencyKey pixIdempotencyKey = new PixExternalIdempotencyKey()
        pixIdempotencyKey.idempotencyKey = idempotencyKey
        pixIdempotencyKey.encryptedRequest = encrypted.encryptedString
        pixIdempotencyKey.iv = encrypted.iv
        pixIdempotencyKey.save(flush: true, failOnError: true)

        return pixIdempotencyKey
    }

    public PixExternalIdempotencyKey setResponse(PixExternalIdempotencyKey pixIdempotencyKey, String response, Integer responseStatusCode) {
        pixIdempotencyKey.encryptedResponse = PixExternalIdempotencyKey.encrypt(response, pixIdempotencyKey.iv).encryptedString
        pixIdempotencyKey.responseStatusCode = responseStatusCode
        pixIdempotencyKey.save(failOnError: true)
        return pixIdempotencyKey
    }

    public Map validate(String idempotencyKey, String requestBody) {
        PixExternalIdempotencyKey idempotency = get(idempotencyKey)

        if (!idempotency) return [firstRequestWithIdempotencyKey: true]
        if (!idempotency.hasEqualBody(requestBody)) return [firstRequestWithIdempotencyKey: false, hasEqualBody: false]
        if (!idempotency.responseStatusCode) return [timeout: true]
        return [firstRequestWithIdempotencyKey: false, hasEqualBody: true, response: idempotency.getDecryptedResponse(), responseStatusCode: idempotency.responseStatusCode]
    }

    public PixExternalIdempotencyKey get(String idempotencyKey) {
        PixExternalIdempotencyKey pixIdempotencyKey = PixExternalIdempotencyKey.query([idempotencyKey: idempotencyKey]).get()
        return pixIdempotencyKey
    }
}
