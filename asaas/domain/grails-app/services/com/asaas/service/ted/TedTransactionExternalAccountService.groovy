package com.asaas.service.ted

import com.asaas.domain.ted.TedTransaction
import com.asaas.domain.ted.TedTransactionExternalAccount

import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class TedTransactionExternalAccountService {

    public TedTransactionExternalAccount save(TedTransaction tedTransaction, TedPayerAccountAdapter accountAdapter) {
        TedTransactionExternalAccount validatedDomain = validateSave(accountAdapter)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar a TED", validatedDomain.errors)

        TedTransactionExternalAccount externalAccount = new TedTransactionExternalAccount()
        externalAccount.financialInstitution = accountAdapter.financialInstitution
        externalAccount.agency = accountAdapter.agency
        externalAccount.account = accountAdapter.account
        externalAccount.accountType = accountAdapter.accountType
        externalAccount.personType = accountAdapter.personType
        externalAccount.cpfCnpj = accountAdapter.cpfCnpj
        externalAccount.name = accountAdapter.name
        externalAccount.tedTransaction = tedTransaction
        return externalAccount.save(failOnError: true)
    }

    private TedTransactionExternalAccount validateSave(TedPayerAccountAdapter accountAdapter) {
        TedTransactionExternalAccount tedTransactionExternalAccount = new TedTransactionExternalAccount()

        if (!accountAdapter.financialInstitution) return DomainUtils.addError(tedTransactionExternalAccount, "Instituição Financeira não encontrada!")

        if (!accountAdapter.cpfCnpj) return DomainUtils.addError(tedTransactionExternalAccount, "CPF/CNPJ do pagador não informado!")

        if (accountAdapter.cpfCnpj && !CpfCnpjUtils.validate(accountAdapter.cpfCnpj)) return DomainUtils.addError(tedTransactionExternalAccount, "CPF/CNPJ do pagador é inválido!")

        if (!accountAdapter.name) return DomainUtils.addError(tedTransactionExternalAccount, "Nome do pagador não informado!")

        return tedTransactionExternalAccount
    }
}
