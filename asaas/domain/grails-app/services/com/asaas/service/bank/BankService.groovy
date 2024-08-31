package com.asaas.service.bank

import com.asaas.domain.bank.Bank
import com.asaas.integration.bacen.bank.adapter.BankAdapter
import com.asaas.integration.bacen.bank.adapter.ListBankAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BankService {

    def bacenBankSynchronizationManagerService

    public void synchronizeWithBacen() {
        ListBankAdapter listBankAdapter = bacenBankSynchronizationManagerService.list()
        if (!listBankAdapter) {
            AsaasLogger.warn("BankService.synchronizeWithBacen() -> Listagem para sincronização de bancos vazia.")
            return
        }

        for (BankAdapter bankAdapter : listBankAdapter.banks) {
            Utils.withNewTransactionAndRollbackOnError({
                Bank bank = Bank.query([code: bankAdapter.code]).get()
                if (!bank) {
                    save(bankAdapter)
                } else {
                    setIspbIfNecessary(bank, bankAdapter)
                }
            }, [logErrorMessage: "BankService.synchronizeWithBacen() -> Falha ao salvar sincronização de banco. [bankAdapter: ${bankAdapter.properties}]"])
        }
    }

    private Bank save(BankAdapter bankAdapter) {
        Bank validatedBank = validateSave(bankAdapter)
        if (validatedBank.hasErrors()) throw new RuntimeException(DomainUtils.getFirstValidationMessage(validatedBank))

        Bank bank = new Bank()
        bank.ispb = bankAdapter.ispb
        bank.name = bankAdapter.shortName ?: bankAdapter.name
        bank.code = bankAdapter.code
        bank.nossoNumeroDigitsCount = 0
        bank.boletoValue = ""
        AsaasLogger.info("BankService.save() -> Adicionando novo banco. [bank: ${bank.properties}]")

        return bank.save(failOnError: true)
    }

    private Bank setIspbIfNecessary(Bank bank, BankAdapter bankAdapter) {
        Bank validatedBank = validateUpdate(bankAdapter)
        if (validatedBank.hasErrors()) {
            AsaasLogger.warn("BankService.setIspbIfNecessary -> ${DomainUtils.getFirstValidationMessage(validatedBank)}. [bankAdapter: ${bankAdapter.properties}]")
            return bank
        }

        if (bank.ispb) return bank

        bank.ispb = bankAdapter.ispb
        AsaasLogger.info("BankService.setIspbIfNecessary() -> Atualizacão de ISPB. [bank: ${bank.properties}, bankAdapter: ${bankAdapter.properties}]")

        return bank.save(failOnError: true)
    }

    private Bank validateSave(BankAdapter bankAdapter) {
        Bank validateBank = new Bank()

        if (Bank.query([code: bankAdapter.code, exists: true]).get()) DomainUtils.addError(validateBank, "Esse código bancário já existe")

        return validateBank
    }

    private Bank validateUpdate(BankAdapter bankAdapter) {
        Bank validateBank = new Bank()

        if (Bank.query([code: bankAdapter.code]).count() > 1) DomainUtils.addError(validateBank, "Código bancário compartilhado. Deve ser tratado manualmente.")

        return validateBank
    }
}
