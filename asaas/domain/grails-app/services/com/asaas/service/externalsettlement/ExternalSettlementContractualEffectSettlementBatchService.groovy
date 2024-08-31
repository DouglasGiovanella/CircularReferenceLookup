package com.asaas.service.externalsettlement

import com.asaas.domain.bank.Bank
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.externalsettlement.ExternalSettlement
import com.asaas.domain.externalsettlement.ExternalSettlementContractualEffectSettlementBatch
import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlementBatch
import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.externalsettlement.ExternalSettlementOrigin
import com.asaas.externalsettlement.adapter.BankAccountAdapter
import com.asaas.integration.cerc.enums.ReceivableRegistrationNonPaymentReason
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementBatchStatus
import com.asaas.integration.cerc.enums.contractualeffect.ContractualEffectExternalSettlementType
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ExternalSettlementContractualEffectSettlementBatchService {

    def bankAccountInfoUpdateRequestService
    def cercContractualEffectSettlementBatchService
    def externalSettlementService
    def financialTransactionExternalSettlementService
    def grailsApplication
    def revenueServiceRegisterService

    public Boolean saveForBatches() {
        Map search = [:]
        search.column = "id"
        search.status = CercContractualEffectSettlementBatchStatus.AWAITING_TRANSFER
        search."externalSettlement[notExists]" = true

        Date yesterday = CustomDateUtils.getYesterday()
        Date lastBusinessDay = yesterday.clone()
        CustomDateUtils.setDateForLastBusinessDay(lastBusinessDay)
        search."debitDate[ge]" = lastBusinessDay
        search."debitDate[le]" = CustomDateUtils.setTimeToEndOfDay(yesterday)
        search."type" = ContractualEffectExternalSettlementType.INTERNAL

        List<Long> batchIdList = CercContractualEffectSettlementBatch.query(search).list()

        Boolean needsAnotherExecution = false

        for (Long batchId : batchIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercContractualEffectSettlementBatch batch = CercContractualEffectSettlementBatch.get(batchId)
                save(batch)
            }, [
                logErrorMessage: "ExternalSettlementContractualEffectSettlementBatchService.saveForBatches >> Erro na geração de externalSettlement para o batch [${batchId}]",
                onError: { needsAnotherExecution = true }
            ])
        }

        return needsAnotherExecution
    }

    public void confirmOrDenyBatch(ExternalSettlement externalSettlement) {
        if (externalSettlement.transfer.status.isDone()) {
            confirmBatch(externalSettlement)
        } else {
            denyBatch(externalSettlement)
        }
    }

    public BankAccountInfo saveBankAccountInfoForBankAccountIfNecessary(BankAccountAdapter bankAccountAdapter, Customer asaasCustomer) {
        Bank bank = PaymentServiceProvider.query([column: "bank", ispb: bankAccountAdapter.ispb]).get()
        if (!bank) return null

        String accountNumberFiltered = Utils.removeNonNumeric(bankAccountAdapter.number)
        BankAccountInfo existingBankAccount = BankAccountInfo.query([customerId: asaasCustomer.id,
                                                                     account: accountNumberFiltered.take(accountNumberFiltered.length() - 1),
                                                                     agency: bankAccountAdapter.agency,
                                                                     cpfCnpj: bankAccountAdapter.cpfCnpj,
                                                                     bankId: bank.id]).get()
        if (existingBankAccount) return existingBankAccount

        Map parsedParams = parseParamsFromBankAccount(bankAccountAdapter, accountNumberFiltered, bank)
        BankAccountInfo bankAccountInfo = bankAccountInfoUpdateRequestService.save(asaasCustomer.id, parsedParams)

        if (bankAccountInfo.hasErrors()) throw new ValidationException("Ocorreu um erro na geração de conta bancária", bankAccountInfo.errors)

        return bankAccountInfo
    }

    private void save(CercContractualEffectSettlementBatch batch) {
        BankAccountAdapter bankAccountAdaper = new BankAccountAdapter(batch.bankAccount)
        Customer asaasCustomer = Customer.get(grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id)
        BankAccountInfo bankAccountInfo = saveBankAccountInfoForBankAccountIfNecessary(bankAccountAdaper, asaasCustomer)
        if (!bankAccountInfo) return

        ExternalSettlement externalSettlement = externalSettlementService.save(asaasCustomer, bankAccountInfo, batch.calculateValue(), ExternalSettlementOrigin.CONTRACTUAL_EFFECT_SETTLEMENT_BATCH)

        ExternalSettlementContractualEffectSettlementBatch settlementBatch = new ExternalSettlementContractualEffectSettlementBatch()
        settlementBatch.batch = batch
        settlementBatch.externalSettlement = externalSettlement

        settlementBatch.save(failOnError: true)
    }

    private void confirmBatch(ExternalSettlement externalSettlement) {
        CercContractualEffectSettlementBatch batch = ExternalSettlementContractualEffectSettlementBatch.query([column: "batch", externalSettlementId: externalSettlement.id]).get()
        cercContractualEffectSettlementBatchService.setAsTransferred(batch)
        externalSettlementService.setAsProcessed(externalSettlement)
    }

    private void denyBatch(ExternalSettlement externalSettlement) {
        financialTransactionExternalSettlementService.reverseCredit(externalSettlement)

        CercContractualEffectSettlementBatch batch = ExternalSettlementContractualEffectSettlementBatch.query([column: "batch", externalSettlementId: externalSettlement.id]).get()
        cercContractualEffectSettlementBatchService.setBatchAsAwaitingTransferIfPossible(batch)
        cercContractualEffectSettlementBatchService.deny(batch, ReceivableRegistrationNonPaymentReason.INVALID_BANK_ACCOUNT_DATA)

        externalSettlementService.setAsRefunded(externalSettlement)
    }

    private Map parseParamsFromBankAccount(BankAccountAdapter bankAccountAdapter, String accountNumber, Bank bank) {
        if (!bankAccountAdapter.holderName) {
            String holderName = queryHolderName(bankAccountAdapter.cpfCnpj)
            bankAccountAdapter.holderName = holderName

            if (bankAccountAdapter.cercBankAccount) {
                CercBankAccount cercBankAccount = bankAccountAdapter.cercBankAccount
                cercBankAccount.holderName = holderName
                cercBankAccount.save(failOnError: true)
            }
        }

        Map params = [
            account: accountNumber.take(accountNumber.length() - 1),
            accountDigit: accountNumber.reverse().take(1),
            agency: bankAccountAdapter.agency,
            name: bankAccountAdapter.holderName,
            bank: bank.id,
            bankAccountType: bankAccountAdapter.type,
            cpfCnpj: bankAccountAdapter.cpfCnpj
        ]

        return params
    }

    private String queryHolderName(String cpfCnpj) {
        Map search = [:]
        search.cpfCnpj = cpfCnpj
        search.column = "name"
        if (CpfCnpjUtils.isCnpj(cpfCnpj)) search.column = "corporateName"

        String holderName = RevenueServiceRegister.query(search).get()
        if (holderName) return holderName

        if (CpfCnpjUtils.isCnpj(cpfCnpj)) {
            return revenueServiceRegisterService.findLegalPerson(cpfCnpj).corporateName
        } else {
            return revenueServiceRegisterService.getNaturalData(cpfCnpj).name
        }
    }
}
