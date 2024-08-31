package com.asaas.service.financialInstitution

import com.asaas.domain.bank.Bank
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialinstitution.FinancialInstitution
import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider
import com.asaas.log.AsaasLogger
import com.asaas.namedqueries.NamedQueries
import com.asaas.namedqueries.SqlOrder
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy
import org.hibernate.criterion.Projections
import org.hibernate.impl.CriteriaImpl

@Transactional
class FinancialInstitutionService {

    public Map listOrderedByBankPriority(Customer customer, Integer offset, Integer limit, Map search) {
        search.disableSort = true

        if (!BankAccountInfo.canSaveBankAccountWithoutBank(customer)) {
            search."bank[isNotNull]" = true
        }

        NamedCriteriaProxy criteriaProxy = FinancialInstitution.ignoreAsaasBank(search)

        String priorityBankNames = "'${FinancialInstitution.PRIORITY_BANK_NAME_LIST.join("','")}'"

        SqlOrder sqlOrder = new SqlOrder("(this_.name in (${priorityBankNames})) DESC,this_.name")
        CriteriaImpl criteria = NamedQueries.buildCriteriaFromNamedQuery(criteriaProxy)
        criteria.addOrder(sqlOrder)
            .setReadOnly(true)
            .setFirstResult(offset)
            .setMaxResults(limit)

        List<FinancialInstitution> financialInstitutionList = criteria.list()

        criteria.setFirstResult(0)
        Integer totalCount = criteria.setProjection(Projections.rowCount()).uniqueResult().intValue()

        return [items: financialInstitutionList, totalCount: totalCount]
    }

    public FinancialInstitution save(PaymentServiceProvider paymentServiceProvider) {
        FinancialInstitution financialInstitution = new FinancialInstitution()
        financialInstitution.paymentServiceProvider = paymentServiceProvider
        financialInstitution.bank = paymentServiceProvider.bank
        financialInstitution.name = paymentServiceProvider.corporateName
        financialInstitution.save(failOnError: true)

        return financialInstitution
    }

    public FinancialInstitution updateNameIfNecessary(PaymentServiceProvider paymentServiceProvider) {
        if (!paymentServiceProvider.isDirty("corporateName")) return null

        if (!canUpdateName(paymentServiceProvider)) {
            AsaasLogger.error("FinancialInstitutionService.updateNameIfNecessary() -> Não foi possível atualizar o nome desta FinancialInstitution. [paymentServiceProvider.id: ${paymentServiceProvider.id}]")
            return null
        }

        FinancialInstitution financialInstitution = FinancialInstitution.query([paymentServiceProvider: paymentServiceProvider]).get()
        if (!financialInstitution) {
            AsaasLogger.error("FinancialInstitutionService.updateNameIfNecessary() -> Nenhuma FinancialInstitution encontrada vinculada a este paymentServiceProvider. [paymentServiceProvider.id: ${paymentServiceProvider.id}]")
            return null
        }

        AsaasLogger.info("FinancialInstitutionService.updateNameIfNecessary() -> Alterando name da financialInstitution. [id: ${financialInstitution.id}, Antigo: ${financialInstitution.name}, Novo: ${paymentServiceProvider.corporateName}]")
        financialInstitution.name = paymentServiceProvider.corporateName
        financialInstitution.save(failOnError: true)

        return financialInstitution
    }

    public void synchronize() {
        updateBankWithPaymentServiceProviderAlreadyExists()
        updatePaymentServiceProviderWithBankAlreadyExists()

        saveBankHasNoPaymentServiceProvider()
        savePaymentServiceProvider()

        verifySyncIntegrity()
    }

    private Boolean canUpdateName(PaymentServiceProvider paymentServiceProvider) {
        Boolean isPaymentServiceProviderDuplicated = FinancialInstitution.query([paymentServiceProvider: paymentServiceProvider]).count() > 1
        if (isPaymentServiceProviderDuplicated) return false

        return true
    }

    private void updateBankWithPaymentServiceProviderAlreadyExists() {
        List<Long> bankIdWithPaymentServiceProviderLinkedList = Bank.query([column: "id", "paymentServiceProvider[exists]": true, "financialInstitution[notExists]": true]).list()

        for (Long bankId : bankIdWithPaymentServiceProviderLinkedList) {
            Utils.withNewTransactionAndRollbackOnError({
                Bank bank = Bank.get(bankId)
                PaymentServiceProvider paymentServiceProvider = PaymentServiceProvider.findByIspb(bank.ispb)
                FinancialInstitution financialInstitution = FinancialInstitution.query([paymentServiceProvider: paymentServiceProvider]).get()
                if (!financialInstitution) {
                    AsaasLogger.error("FinancialInstitutionService.updateBankWithPaymentServiceProviderAlreadyExists() -> Não existe FI para o PSP vinculado ao Bank. bankId: ${bankId}")
                    return
                }

                if (financialInstitution.bank) {
                    AsaasLogger.error("FinancialInstitutionService.updateBankWithPaymentServiceProviderAlreadyExists() -> Já existe um banco vinculado ao PSP. bankId: ${bankId}, financialInstitutionId: ${financialInstitution.id}")
                    return
                }

                financialInstitution.bank = bank
                financialInstitution.save(failOnError: true)
            }, [logErrorMessage: "FinancialInstitutionService.updateBankWithPaymentServiceProviderAlreadyExists() -> Falha ao vincular banco a uma FI. bankId: ${bankId}"])
        }
    }

    private void updatePaymentServiceProviderWithBankAlreadyExists() {
        List<Long> paymentServiceProviderIdWithBankLinkedList = PaymentServiceProvider.query([column: "id", "bank[exists]": true, "financialInstitution[notExists]": true]).list()

        for (Long paymentServiceProviderId : paymentServiceProviderIdWithBankLinkedList) {
            PaymentServiceProvider paymentServiceProvider = PaymentServiceProvider.get(paymentServiceProviderId)
            Bank bank = Bank.findByIspb(paymentServiceProvider.ispb)
            FinancialInstitution financialInstitution = FinancialInstitution.query([bankId: bank.id]).get()
            if (!financialInstitution) {
                AsaasLogger.error("FinancialInstitutionService.updatePaymentServiceProviderWithBankAlreadyExists() -> Não existe FI para o Bank vinculado ao PSP. paymentServiceProviderId: ${paymentServiceProviderId}")
                return
            }

            if (financialInstitution.paymentServiceProvider) {
                AsaasLogger.error("FinancialInstitutionService.updatePaymentServiceProviderWithBankAlreadyExists() -> Já existe um PSP vinculado ao bank. paymentServiceProviderId: ${paymentServiceProviderId}, financialInstitutionId: ${financialInstitution.id}")
                return
            }

            financialInstitution.paymentServiceProvider = paymentServiceProvider
            financialInstitution.name = paymentServiceProvider.corporateName
            financialInstitution.save(failOnError: true)
        }
    }

    private void saveBankHasNoPaymentServiceProvider() {
        List<Long> bankIdHasNoPaymentServiceProviderList = Bank.query([column: "id", "paymentServiceProvider[notExists]": true, "financialInstitution[notExists]": true, "ispb[isNotNull]": true]).list()

        for (Long bankId : bankIdHasNoPaymentServiceProviderList) {
            Utils.withNewTransactionAndRollbackOnError({
                Bank bank = Bank.get(bankId)

                FinancialInstitution financialInstitution = new FinancialInstitution()
                financialInstitution.bank = bank
                financialInstitution.name = bank.name
                financialInstitution.save(failOnError: true)
            }, [logErrorMessage: "FinancialInstitutionService.saveBankHasNoPaymentServiceProvider() -> Falha ao criar FI para o BankId: ${bankId}"])
        }
    }

    private void savePaymentServiceProvider() {
        List<Long> paymentServiceProviderIdList = PaymentServiceProvider.query([column: "id", "financialInstitution[notExists]": true]).list()

        for (Long paymentServiceProviderId : paymentServiceProviderIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PaymentServiceProvider paymentServiceProvider = PaymentServiceProvider.get(paymentServiceProviderId)
                Bank bank = Bank.query(["ispb": paymentServiceProvider.ispb]).get()
                if (bank) {
                    FinancialInstitution financialInstitution = FinancialInstitution.query([bankId: bank.id]).get()
                    if (financialInstitution) {
                        AsaasLogger.error("FinancialInstitutionService.savePaymentServiceProvider -> PSP tem banco vinculado que já tem um registro na FI. paymentServiceProviderId: ${paymentServiceProviderId}, bankId: ${bank.id}")
                        return
                    }
                }

                FinancialInstitution financialInstitution = new FinancialInstitution()
                financialInstitution.paymentServiceProvider = paymentServiceProvider
                financialInstitution.bank = bank
                financialInstitution.name = paymentServiceProvider.corporateName
                financialInstitution.save(failOnError: true)
            }, [logErrorMessage: "FinancialInstitutionService.savePaymentServiceProvider() -> Falha ao salvar a PaymentServiceProviderId: ${paymentServiceProviderId}"])
        }
    }

    private void verifySyncIntegrity() {
        final Long expectedNumberOfBanksId = 1
        final Long expectedNumberOfPaymentServiceProvidersId = 1

        List<Long> bankIdList = Bank.query(["column": "id"]).list()

        for (Long bankId : bankIdList) {
            List<FinancialInstitution> financialInstitutionList = FinancialInstitution.query([bankId: bankId]).list()
            if (financialInstitutionList && financialInstitutionList.size() == expectedNumberOfBanksId) continue

            AsaasLogger.error("FinancialInstitutionService.verifySyncIntegrity -> Banco com registro inconsistente na FinancialInstitution. bankId: ${bankId}")
        }

        List<Long> paymentServiceProviderIdList = PaymentServiceProvider.query(["column": "id"]).list()
        for (Long paymentServiceProviderId : paymentServiceProviderIdList) {
            List<FinancialInstitution> financialInstitutionList = FinancialInstitution.query([paymentServiceProviderId: paymentServiceProviderId]).list()
            if (financialInstitutionList && financialInstitutionList.size() == expectedNumberOfPaymentServiceProvidersId) continue

            AsaasLogger.error("FinancialInstitutionService.verifySyncIntegrity -> PaymentServiceProvider com registro inconsistente na FinancialInstitution. paymentServiceProviderId: ${paymentServiceProviderId}")
        }
    }
}
