package com.asaas.service.pix.account

import com.asaas.accountnumber.AccountNumberHermesSyncOperationType
import com.asaas.accountnumber.AccountNumberHermesSyncStatus
import com.asaas.customer.CustomerStatus
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.accountnumber.hermessync.AccountNumberHermesSync
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import org.hibernate.impl.SQLQueryImpl

@Transactional
class SynchronizeHermesAccountNumberService {

    def hermesAccountManagerService
    def sessionFactory

    public void process() {
        List<Long> accountNumberHermesSyncIdList = AccountNumberHermesSync.oldestAwaitingSynchronization([column: "id"]).list(max: 100)

        Utils.forEachWithFlushSession(accountNumberHermesSyncIdList, 50, { Long accountNumberHermesSyncId ->
            Utils.withNewTransactionAndRollbackOnError({
                synchronizeAccountNumber(AccountNumberHermesSync.get(accountNumberHermesSyncId), null)
            }, [logErrorMessage: "SynchronizeHermesAccountNumberService.process() -> Erro interno ao processar a sincronização [accountNumberHermesSyncId: ${accountNumberHermesSyncId}]"])
        })
    }

    public void syncSavedAccountNumbers() {
        try {
            StringBuilder sql = new StringBuilder()
                    .append("select c.id ")
                    .append("from account_number an ")
                    .append("inner join customer c on an.customer_id = c.id ")
                    .append("inner join customer_proof_of_life cpol on cpol.customer_id = an.customer_id ")
                    .append("left join account_number_hermes_sync anhs on anhs.account_number_id = an.id and anhs.deleted = false ")
                    .append("where an.deleted = false and an.hermes_synchronized = false ")
                    .append("and anhs.id is null ")
                    .append("and cpol.approved = true ")
                    .append("and c.status = '${CustomerStatus.ACTIVE.toString()}' ")
                    .append("limit 100 ")

            SQLQueryImpl query = sessionFactory.currentSession.createSQLQuery(sql.toString())
            List<Long> customerIdList = query.list().collect { Utils.toLong(it) }

            if (customerIdList) AsaasLogger.info("SynchronizeHermesAccountNumberService.syncSavedAccountNumbers() -> Foram encontrados registros para sincronizar com o Hermes via rotina obsoleta. [customerIdList: ${customerIdList}]")

            Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
                Utils.withNewTransactionAndRollbackOnError({
                    AccountNumberHermesSync accountNumberHermesSync = create(Customer.get(customerId))
                    if (accountNumberHermesSync.hasErrors()) {
                        AsaasLogger.error("SynchronizeHermesAccountNumberService.create() -> ${accountNumberHermesSync.getErrors().allErrors.first().defaultMessage}")
                    }
                }, [logErrorMessage: "SynchronizeHermesAccountNumberService.create() -> Erro desconhecido [customerId: ${customerId}]."])
            })
        } catch (Exception exception) {
            AsaasLogger.error("Falha na execução [SynchronizeHermesAccountNumberService.syncSavedAccountNumbers]", exception)
        }
    }

    public AccountNumberHermesSync create(Customer customer) {
        return save(customer, AccountNumberHermesSyncOperationType.CREATE)
    }

    public AccountNumberHermesSync update(Customer customer) {
        Boolean hasBeenSynchronized = AccountNumberHermesSync.query([accountNumber: customer.accountNumber, exists: true, operationType: AccountNumberHermesSyncOperationType.CREATE, status: AccountNumberHermesSyncStatus.SYNCHRONIZED]).get().asBoolean()

        if (hasBeenSynchronized) {
            return save(customer, AccountNumberHermesSyncOperationType.UPDATE)
        } else {
            return create(customer)
        }
    }

    public void synchronizeAccountNumber(AccountNumberHermesSync accountNumberHermesSync, Integer createTimeout) {
        Customer customer = accountNumberHermesSync.accountNumber.customer

        BusinessValidation businessValidation = validateSyncAccountNumber(customer)
        if (!businessValidation.isValid()) {
            setAsError(accountNumberHermesSync, businessValidation.getAsaasErrors()[0].code)
            return
        }

        Map saveAccountNumberResult
        if (accountNumberHermesSync.operationType.isCreate()) {
            saveAccountNumberResult = hermesAccountManagerService.synchronizeAccountNumber(customer, createTimeout)
        } else {
            saveAccountNumberResult = hermesAccountManagerService.updateAccountNumber(customer)
        }

        if (saveAccountNumberResult.success) {
            setAsSynchronized(accountNumberHermesSync)
        } else if (saveAccountNumberResult.withoutExternalResponse) {
            AsaasLogger.warn("SynchronizeHermesAccountNumberService.process() -> Timeout para sincronização de número de conta entre Asaas e Hermes. [customer.id: ${customer.id}, accountNumber.id: ${accountNumberHermesSync.accountNumber.id}]")
        } else {
            setAsError(accountNumberHermesSync, saveAccountNumberResult.errorMessage)
        }
    }

    private AccountNumberHermesSync save(Customer customer, AccountNumberHermesSyncOperationType operationType) {
        AccountNumberHermesSync accountNumberHermesSync = new AccountNumberHermesSync()
        AccountNumber accountNumber = customer.getAccountNumber()

        BusinessValidation validateAccountNumberHermesSync = validateSaveAccountNumberHermesSync(accountNumber)
        if (!validateAccountNumberHermesSync.isValid()) {
            DomainUtils.addError(accountNumberHermesSync, validateAccountNumberHermesSync.getAsaasErrors()[0].code)
            return accountNumberHermesSync
        }

        accountNumberHermesSync.accountNumber = accountNumber
        accountNumberHermesSync.operationType = operationType
        accountNumberHermesSync.status = AccountNumberHermesSyncStatus.AWAITING_SYNCHRONIZATION
        accountNumberHermesSync.save(failOnError: true)
        return accountNumberHermesSync
    }

    private BusinessValidation validateSaveAccountNumberHermesSync(AccountNumber accountNumber) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!accountNumber) {
            businessValidation.addError("Cliente não possui AccountNumber")
            return businessValidation
        }

        if (!accountNumber.customer.hasApprovedProofOfLife()) {
            businessValidation.addError("Cliente [${accountNumber.customer.id}] não possui prova de vida")
            return businessValidation
        }

        Boolean alreadyExists = AccountNumberHermesSync.query([accountNumber: accountNumber, exists: true, status: AccountNumberHermesSyncStatus.AWAITING_SYNCHRONIZATION]).get().asBoolean()
        if (alreadyExists) businessValidation.addError("Já existe uma sincronização pendente para essa Account Number [${accountNumber.id}]")

        return businessValidation
    }

    private BusinessValidation validateSyncAccountNumber(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!customer.cpfCnpj) {
            businessValidation.addError("Attribute cpfCnpj is mandatory")
            return businessValidation
        }
        if (!customer.personType) {
            businessValidation.addError("Attribute personType is mandatory")
            return businessValidation
        }
        if (!customer.getAccountNumber()) {
            businessValidation.addError("Attribute accountNumber is mandatory")
            return businessValidation
        }

        if (customer.personType.isJuridica()) {
            if (!customer.company) {
                businessValidation.addError("Attribute company is mandatory for PJ")
                return businessValidation
            }
        } else {
            if (!customer.name) {
                businessValidation.addError("Attribute name is mandatory for PF")
                return businessValidation
            }
        }

        return businessValidation
    }

    private void setAsSynchronized(AccountNumberHermesSync accountNumberHermesSync) {
        accountNumberHermesSync.status = AccountNumberHermesSyncStatus.SYNCHRONIZED
        accountNumberHermesSync.save(failOnError: true)

        accountNumberHermesSync.accountNumber.hermesSynchronized = true
        accountNumberHermesSync.accountNumber.save(failOnError: true)
    }

    private void setAsError(AccountNumberHermesSync accountNumberHermesSync, String message) {
        accountNumberHermesSync.status = AccountNumberHermesSyncStatus.ERROR
        accountNumberHermesSync.errorMessage = message
        accountNumberHermesSync.save(failOnError: true)

        accountNumberHermesSync.accountNumber.hermesSynchronized = false
        accountNumberHermesSync.accountNumber.save(failOnError: true)
    }
}
