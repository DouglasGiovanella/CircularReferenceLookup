package com.asaas.service.openfinance

import com.asaas.domain.openfinance.OpenFinanceExternalIdempotencyKey
import com.asaas.exception.BusinessException
import com.asaas.openfinance.openFinanceExternalIdempotencyKey.enums.OpenFinanceExternalIdempotencyKeyScope

import grails.transaction.Transactional

@Transactional
class OpenFinanceExternalIdempotencyKeyService {

    public OpenFinanceExternalIdempotencyKey save(String idempotencyKey, String requestBody, OpenFinanceExternalIdempotencyKeyScope scope) {
        if (idempotencyKey.length() != OpenFinanceExternalIdempotencyKey.IDEMPOTENCY_KEY_SIZE) throw new BusinessException("Tamanho incorreto para chave de idempotência.")

        Map encrypted = OpenFinanceExternalIdempotencyKey.encrypt(requestBody)

        OpenFinanceExternalIdempotencyKey openFinanceIdempotencyKey = new OpenFinanceExternalIdempotencyKey()
        openFinanceIdempotencyKey.idempotencyKey = idempotencyKey
        openFinanceIdempotencyKey.encryptedRequest = encrypted.encryptedString
        openFinanceIdempotencyKey.iv = encrypted.iv
        openFinanceIdempotencyKey.scope = scope
        openFinanceIdempotencyKey.save(flush: true, failOnError: true)

        return openFinanceIdempotencyKey
    }

    public OpenFinanceExternalIdempotencyKey setResponse(OpenFinanceExternalIdempotencyKey openFinanceIdempotencyKey, String response, Integer responseStatusCode) {
        openFinanceIdempotencyKey.encryptedResponse = OpenFinanceExternalIdempotencyKey.encrypt(response, openFinanceIdempotencyKey.iv).encryptedString
        openFinanceIdempotencyKey.responseStatusCode = responseStatusCode
        openFinanceIdempotencyKey.save(failOnError: true)
        return openFinanceIdempotencyKey
    }

    public Map validate(String idempotencyKey, String requestBody, OpenFinanceExternalIdempotencyKeyScope scope) {
        OpenFinanceExternalIdempotencyKey idempotency = get(idempotencyKey, scope)

        if (!idempotency) return [firstRequestWithIdempotencyKey: true]
        if (!idempotency.hasEqualBody(requestBody)) return [firstRequestWithIdempotencyKey: false, hasEqualBody: false]
        if (!idempotency.responseStatusCode) return [timeout: true]
        return [firstRequestWithIdempotencyKey: false, hasEqualBody: true, response: idempotency.getDecryptedResponse(), responseStatusCode: idempotency.responseStatusCode]
    }

    public OpenFinanceExternalIdempotencyKey get(String idempotencyKey, OpenFinanceExternalIdempotencyKeyScope scope) {
        OpenFinanceExternalIdempotencyKey openFinanceIdempotencyKey = OpenFinanceExternalIdempotencyKey.query([idempotencyKey: idempotencyKey, scope: scope]).get()
        return openFinanceIdempotencyKey
    }

}
