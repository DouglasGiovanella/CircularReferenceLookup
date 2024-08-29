package com.asaas.service.integration.cerc

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.optin.CercAsaasOptIn
import com.asaas.integration.cerc.enums.CercAsaasOptInType
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercAsaasOptInService {

    def cercAsaasOptOutService
    def cercCompanyService

    public CercAsaasOptIn saveExternalOptIn(String cpfCnpj) {
        return save(cpfCnpj, CercAsaasOptInType.EXTERNAL, null, null)
    }

    public CercAsaasOptIn toggleExternalOptIn(Customer customer, Boolean enabled) {
        CercAsaasOptIn cercAsaasExternalOptIn = CercAsaasOptIn.query([customerCpfCnpj: customer.cpfCnpj, type: CercAsaasOptInType.EXTERNAL]).get()
        if (!cercAsaasExternalOptIn && !enabled) return
        if (!cercAsaasExternalOptIn && enabled) return saveExternalOptIn(customer.cpfCnpj)

        if (cercAsaasExternalOptIn.syncStatus.isSynced()) cercAsaasOptOutService.save(cercAsaasExternalOptIn)

        cercAsaasExternalOptIn.deleted = true
        cercAsaasExternalOptIn.save(failOnError: true)
        return cercAsaasExternalOptIn
    }

    public CercAsaasOptIn save(String cpfCnpj, CercAsaasOptInType type, ReceivableAnticipationPartner partner, Date dueDate) {
        CercCompany cercCompany = cercCompanyService.saveIfNecessary(cpfCnpj)

        CercAsaasOptIn validatedDomain = validateSave(cercCompany, type, partner, dueDate)
        if (validatedDomain.hasErrors()) return validatedDomain

        CercAsaasOptIn cercAsaasOptIn = new CercAsaasOptIn()
        cercAsaasOptIn.cercCompany = cercCompany
        cercAsaasOptIn.partner = partner
        cercAsaasOptIn.type = type
        cercAsaasOptIn.dueDate = dueDate ?: CustomDateUtils.addMonths(new Date(), CercAsaasOptIn.DEFAULT_DUE_DATE_IN_YEARS * 12)
        cercAsaasOptIn.syncStatus = CercSyncStatus.NOT_SYNCABLE
        cercAsaasOptIn.save(failOnError: true)

        return cercAsaasOptIn
    }

    public void setAsSynced(CercAsaasOptIn optIn, String externalIdentifier) {
        if (optIn.operationType.isCreate()) {
            if (!externalIdentifier) throw new RuntimeException("Identificador externo não pode ser nulo")
            optIn.externalIdentifier = externalIdentifier
            optIn.operationType = CercOperationType.UPDATE
        }
        optIn.syncStatus = CercSyncStatus.SYNCED
        optIn.save(failOnError: true)
    }

    public void setAsErrorWithNewTransaction(Long optInId) {
        Utils.withNewTransactionAndRollbackOnError({
            CercAsaasOptIn optIn = CercAsaasOptIn.get(optInId)
            optIn.syncStatus = CercSyncStatus.ERROR
            optIn.save(failOnError: true)
        }, [logErrorMessage: "CercAsaasOptInService.setAsErrorWithNewTransaction >> Falha ao marcar o OPT-In [${optInId}] como erro de sincronia"])
    }

    private CercAsaasOptIn validateSave(CercCompany cercCompany, CercAsaasOptInType type, ReceivableAnticipationPartner partner, Date dueDate) {
        CercAsaasOptIn validatedDomain = new CercAsaasOptIn()

        if (!cercCompany.status.isActive()) DomainUtils.addError(validatedDomain, "Estabelecimento comercial não está ativo no CERC")
        if (!type) DomainUtils.addError(validatedDomain, "É necessário informar o tipo do OPT-IN")

        Map search = [:]
        search.exists = true
        search.cercCompanyId = cercCompany.id
        search.type = type
        if (!partner) {
            search."partner[isNull]" = true
        } else {
            search.partner = partner
        }

        Boolean companyHasOptInAlready = CercAsaasOptIn.query(search).get()
        if (companyHasOptInAlready) DomainUtils.addError(validatedDomain, "Este cliente já realizou OPT-IN")

        if (dueDate && dueDate <= new Date()) DomainUtils.addError(validatedDomain, "A validade do OPT-IN não pode ser uma data passada")

        return validatedDomain
    }
}
