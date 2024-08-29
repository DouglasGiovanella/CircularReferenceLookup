package com.asaas.service.integration.cerc

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.integration.cerc.enums.CercAccountType
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CercBankAccountService {

    def cercContractualEffectService
    def grailsApplication
    def receivableRegistrationEventQueueService

    public void processPendingCreateCercBankAccount() {
        final Integer maxItemsPerExecution = 500
        List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.CREATE_CERC_BANK_ACCOUNT, null, null, maxItemsPerExecution)

        for (Map eventData : eventDataList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                CercBankAccount cercBankAccount = saveIfNecessary(eventData.bankAccountData.cpfCnpj, CercAccountType.convert(eventData.bankAccountData.type), eventData.bankAccountData.ispb, eventData.bankAccountData.accountNumber, eventData.bankAccountData.agency, eventData.bankAccountData.compe, eventData.bankAccountData.holderName)
                cercContractualEffectService.setBankAccount(eventData.contractualEffectId, cercBankAccount)

                receivableRegistrationEventQueueService.delete(eventData.eventQueueId)
            }, [logErrorMessage: "CercBankAccountService.processPendingCreateCercBankAccount --> Falha ao processar evento [${eventData.eventQueueId}]",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                }, [logErrorMessage: "CercBankAccountService.processPendingCreateCercBankAccount >> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
            }
        }
    }

    public CercBankAccount saveForAsaasAccount(Customer customer) {
        final String ispb = grailsApplication.config.asaas.ispb
        final String agency = CercBankAccount.ASAAS_AGENCY

        return saveIfNecessary(customer.cpfCnpj, CercAccountType.CONTA_PAGAMENTO, ispb, customer.cpfCnpj, agency, null, null)
    }

    public CercBankAccount saveIfNecessary(String cpfCnpj, CercAccountType type, String ispb, String accountNumber, String agency, String compe, String holderName) {
        CercBankAccount validatedDomain = validateSave(type, agency)
        if (validatedDomain.hasErrors()) throw new ValidationException("Falha ao salvar conta bancária para o CPF/CNPJ [$cpfCnpj]", validatedDomain.errors)

        String accountNumberValue = type.isContaPagamento() ? Utils.removeNonNumeric(accountNumber) : accountNumber
        String compeValue = Utils.removeNonNumeric(compe)
        String agencyValue = agency ?: CercBankAccount.DEFAULT_AGENCY

        Map search = [:]
        search.cpfCnpj = cpfCnpj
        search.type = type
        search.ispb = ispb
        search.accountNumber = accountNumberValue
        search.agency = agency
        search.compe = compeValue

        CercBankAccount cercBankAccount = CercBankAccount.query(search).get()

        if (cercBankAccount) return cercBankAccount

        cercBankAccount = new CercBankAccount()
        cercBankAccount.cpfCnpj = cpfCnpj
        cercBankAccount.type = type
        cercBankAccount.ispb = ispb
        cercBankAccount.accountNumber = accountNumberValue
        cercBankAccount.agency = agencyValue
        cercBankAccount.compe = compeValue
        cercBankAccount.holderName = buildHolderName(holderName, cpfCnpj)
        cercBankAccount.save(failOnError: true)

        return cercBankAccount
    }

    private String buildHolderName(String holderName, String cpfCnpj) {
        if (holderName) return holderName

        holderName = RevenueServiceRegister.query([column: "corporateName", cpfCnpj: cpfCnpj]).get()

        return holderName
    }

    private CercBankAccount validateSave(CercAccountType type, String agency) {
        CercBankAccount validatedDomain = new CercBankAccount()

        if (!type) return DomainUtils.addError(validatedDomain, "O tipo de conta é obrigatório")

        if (!type.isContaPagamento() && !agency) DomainUtils.addError(validatedDomain, "Este tipo de conta [${type}] precisa de um número de agência")

        return validatedDomain
    }
}
