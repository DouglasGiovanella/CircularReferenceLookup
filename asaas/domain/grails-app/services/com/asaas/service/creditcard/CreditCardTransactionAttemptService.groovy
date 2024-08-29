package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCardTransactionAttemptType
import com.asaas.creditcard.HolderInfo
import com.asaas.domain.creditcard.CreditCardTransactionAttempt
import com.asaas.domain.creditcard.CreditCardTransactionLogRelatedWithAttempt
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class CreditCardTransactionAttemptService {

    public CreditCardTransactionAttempt save(Customer customer, CustomerAccount customerAccount, CreditCardTransactionAttemptType creditCardTransactionAttemptType, BigDecimal value, Boolean authorized, String creditCardHash, String creditCardFullInfoHash, Map params) {
        validateSave(params)

        CreditCardTransactionAttempt creditCardTransactionAttempt = new CreditCardTransactionAttempt()
        creditCardTransactionAttempt.value = value
        creditCardTransactionAttempt.customer = customer
        creditCardTransactionAttempt.authorized = authorized
        creditCardTransactionAttempt.creditCardHash = creditCardHash
        creditCardTransactionAttempt.creditCardFullInfoHash = creditCardFullInfoHash
        creditCardTransactionAttempt.customerAccountId = customerAccount.id
        creditCardTransactionAttempt.paymentId = params.paymentId
        creditCardTransactionAttempt.creditCardTransactionLogId = params.creditCardTransactionLogIdList ? params.creditCardTransactionLogIdList.last() : null
        creditCardTransactionAttempt.clearSaleResponseId = params.clearSaleResponseId
        creditCardTransactionAttempt.blocked = params.blocked
        creditCardTransactionAttempt.blockReason = params.blockReason
        creditCardTransactionAttempt.origin = params.origin
        creditCardTransactionAttempt.remoteIp = params.remoteIp
        if (params.payerRemoteIp) creditCardTransactionAttempt.payerRemoteIp = params.payerRemoteIp
        creditCardTransactionAttempt.platform = params.platform
        creditCardTransactionAttempt.criticalBlock = params.criticalBlock
        creditCardTransactionAttempt.attemptType = creditCardTransactionAttemptType
        creditCardTransactionAttempt.holderCpfCnpj = params.holderCpfCnpj
        creditCardTransactionAttempt.holderEmail = params.holderEmail
        creditCardTransactionAttempt.holderNameHash = params.holderName ? HolderInfo.buildHashForHolderInfo(params.holderName) : null
        creditCardTransactionAttempt.save(failOnError: true)

        if (params.creditCardTransactionLogIdList) saveCreditCardTransactionLogRelatedWithAttempt(creditCardTransactionAttempt, params.creditCardTransactionLogIdList)

        return creditCardTransactionAttempt
    }

    private void validateSave(Map params) {
        if (params.blocked) {
            if (!params.blockReason) throw new RuntimeException("Informe o motivo pelo qual a transação foi bloqueada.")
            if (!params.containsKey("criticalBlock")) throw new RuntimeException("Informe se este é um bloqueio crítico.")
        }
    }

    private void saveCreditCardTransactionLogRelatedWithAttempt(CreditCardTransactionAttempt creditCardTransactionAttempt, List<Long> creditCardTransactionLogIdList) {
        for (Long creditCardTransactionLogId : creditCardTransactionLogIdList) {
            if (!creditCardTransactionLogId) {
                AsaasLogger.warn("CreditCardTransactionAttemptService.saveCreditCardTransactionLogRelatedWithAttempt >>> ID do log com valor nulo [creditCardTransactionAttemptId: ${creditCardTransactionAttempt.id}]")
                continue
            }

            try {
                CreditCardTransactionLogRelatedWithAttempt creditCardTransactionLogRelatedWithAttempt = new CreditCardTransactionLogRelatedWithAttempt()
                creditCardTransactionLogRelatedWithAttempt.creditCardTransactionAttempt = creditCardTransactionAttempt
                creditCardTransactionLogRelatedWithAttempt.creditCardTransactionLogId = creditCardTransactionLogId
                creditCardTransactionLogRelatedWithAttempt.save(failOnError: true)
            } catch (Exception exception) {
                AsaasLogger.error("CreditCardTransactionAttemptService.saveCreditCardTransactionLogRelatedWithAttempt >>> Não foi possível salvar o vinculo do log de transação com a tentativa [creditCardTransactionAttemptId: ${creditCardTransactionAttempt.id} / creditCardTransactionLogId: ${creditCardTransactionLogId}]", exception)
            }
        }
    }
}
