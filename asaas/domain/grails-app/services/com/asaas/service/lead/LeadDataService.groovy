package com.asaas.service.lead

import com.asaas.domain.customer.Customer
import com.asaas.domain.lead.LeadData
import com.asaas.domain.lead.LeadUtm
import com.asaas.domain.lead.LeadUtmSequence
import com.asaas.lead.adapter.LeadDataAdapter
import com.asaas.lead.adapter.LeadUtmAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class LeadDataService {

    def asyncActionService
    def createCampaignEventMessageService
    def leadUtmService
    def sessionFactory

    public Boolean createFirstLeadUtmForLeadDataThatDoesNotHave() {
        final Integer flushEvery = 50
        List<Long> leadDataList = getLeadDataIdWithoutLeadUtmList(LeadUtmSequence.FIRST)
        if (!leadDataList) return false

        Utils.forEachWithFlushSession(leadDataList, flushEvery, { Long leadDataId ->
            Utils.withNewTransactionAndRollbackOnError({
                LeadData leadData = LeadData.read(leadDataId)
                Map params = [:]
                params.utmSource = leadData.utmSource
                params.utmMedium = leadData.utmMedium
                params.utmCampaign = leadData.utmCampaign
                params.utmTerm = leadData.utmTerm
                params.utmContent = leadData.utmContent
                params.referrer = leadData.referer

                LeadUtmAdapter firstLeadUtm = LeadUtmAdapter.buildFirstUtmData(params)
                leadUtmService.save(leadData, firstLeadUtm)
            }, [
                onError: { Exception exception ->
                    AsaasLogger.error("LeadDataService.createFirstLeadUtmForLeadData -> Erro ao criar first leadUtm para leadData [${leadDataId}]", exception)
                }]
            )
        })

        return true
    }

    public Boolean createLastLeadUtmForLeadDataThatDoesNotHave() {
        final Integer flushEvery = 50
        List<Long> leadDataList = getLeadDataIdWithoutLeadUtmList(LeadUtmSequence.LAST)
        if (!leadDataList) return false

        Utils.forEachWithFlushSession(leadDataList, flushEvery, { Long leadDataId ->
            Utils.withNewTransactionAndRollbackOnError({
                LeadData leadData = LeadData.read(leadDataId)
                Map params = [:]
                params.lastUtmSource = leadData.utmSource
                params.lastUtmMedium = leadData.utmMedium
                params.lastUtmCampaign = leadData.utmCampaign
                params.lastUtmTerm = leadData.utmTerm
                params.lastUtmContent = leadData.utmContent
                params.lastReferrer = leadData.referer

                LeadUtmAdapter lastLeadUtm = LeadUtmAdapter.buildLastUtmData(params)
                leadUtmService.save(leadData, lastLeadUtm)
            }, [
                onError: { Exception exception ->
                    AsaasLogger.error("LeadDataService.createLastLeadUtmForLeadDataThatDoesNotHave -> Erro ao criar last leadUtm para leadData [${leadDataId}]", exception)
                }]
            )
        })

        return true
    }

    public void createLeadIfNecessary(LeadDataAdapter adapter) {
        validate(adapter)

        LeadData leadData = LeadData.query([email: adapter.email]).get()
        if (leadData) {
            leadUtmService.saveFirstLeadUtmIfDoesNotExist(leadData, adapter.firstLeadUtm)
            return
        }

        create(adapter)
    }

    public void associateLeadWithCustomer(Customer customer, LeadUtmAdapter firstLeadUtm, LeadUtmAdapter lastLeadUtm) {
        LeadData leadData = LeadData.query([email: customer.email, "customer[isNull]": true]).get()
        if (!leadData) {
            AsaasLogger.error("LeadDataService.associateLeadWithCustomer >> Lead não encontrado para o email: [${customer.email}], customerId: [${customer.id}]")
            return
        }

        leadData.customer = customer
        if (customer.signedUpThrough) leadData.utmSource = customer.signedUpThrough.toString()
        leadData.save(failOnError: true)

        leadUtmService.saveFirstLeadUtmIfDoesNotExist(leadData, firstLeadUtm)
        leadUtmService.updateOrCreateLastLeadUtmIfDoesNotExist(leadData, lastLeadUtm)
    }

    public void delete(String email, Long customerId) {
        LeadData leadData = LeadData.query([email: email, customerId: customerId]).get()
        if (!leadData) return

        leadData.deleted = true
        leadData.save(failOnError: true)
    }

    public void processArchiveOldAndCreateNewLead() {
        final Integer maxItemsPerCycle = 250
        final Integer flushEvery = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingArchiveOldAndCreateNewLead(maxItemsPerCycle)
        if (!asyncActionDataList) return

        Utils.forEachWithFlushSession(asyncActionDataList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                String newEmail = asyncActionData.newEmail
                Long leadDataId = Long.valueOf(asyncActionData.leadDataId)

                Boolean deletedAndCreatedNewLead = deleteAndCreateNewLead(leadDataId, newEmail)
                if (!deletedAndCreatedNewLead) {
                    AsaasLogger.error("LeadDataService.processArchiveOldAndCreateNewLead >> Erro ao deletar e criar novo leadData.AsyncActionId: ${asyncActionData.asyncActionId}")
                    asyncActionService.setAsCancelled(asyncActionData.asyncActionId)
                    return
                }
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "LeadDataService.processArchiveOldAndCreateNewLead >> Erro ao deletar e criar novo leadData. AsyncActionId: ${asyncActionData.asyncActionId}",
            onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        })
    }

    public void saveArchiveOldAndCreateNewLeadIfPossible(Long customerId, String newEmail) {
        Long leadDataId = LeadData.query([column: "id", customerId: customerId]).get()
        if (leadDataId) asyncActionService.saveArchiveOldAndCreateNewLead(newEmail, leadDataId)
    }

    public void createLeadDataBasedOnCustomer(Customer customer) {
        Map params = [:]
        params.utmSource = customer.utmSource
        LeadDataAdapter adapter = LeadDataAdapter.build(customer.email, customer?.registerIp, params)
        LeadData leadData = create(adapter, customer)

        Boolean isNecessarySetAsDeleted = customer.status.isDisabled() || customer.deleted
        if (!isNecessarySetAsDeleted) return

        leadData.deleted = true
        leadData.save(flush: true)
    }

    private List<Long> getLeadDataIdWithoutLeadUtmList(LeadUtmSequence leadUtmSequence) {
        final Integer maxItemsPerCycle = 250
        StringBuilder builder = new StringBuilder()

        builder.append("SELECT ld.id FROM lead_data AS ld")
        builder.append("    LEFT JOIN lead_utm AS lu ON ld.id = lu.lead_id AND lu.sequence = :sequence ")
        builder.append("        WHERE ld.email is NOT NULL")
        builder.append("            GROUP BY ld.id")
        builder.append("            HAVING COUNT(lu.lead_id) = 0")
        builder.append("            LIMIT :limit")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())
        query.setString("sequence", leadUtmSequence.toString())
        query.setLong("limit", maxItemsPerCycle)

        List<Long> leadIdList = query.list().collect { Utils.toLong(it) }
        return leadIdList
    }

    private Boolean deleteAndCreateNewLead(Long leadDataId, String newEmail) {
        LeadData leadData = LeadData.get(leadDataId)
        if (!leadData) return false

        leadData.deleted = true
        leadData.save(failOnError: true)

        LeadData newLeadData = new LeadData()
        newLeadData.email = newEmail
        newLeadData.utmSource = leadData.utmSource
        newLeadData.customer = leadData.customer
        newLeadData.created = leadData.created
        newLeadData.distinctId = leadData.distinctId
        newLeadData.registerIp = leadData.registerIp
        newLeadData.utmMedium = leadData.utmMedium
        newLeadData.utmCampaign = leadData.utmCampaign
        newLeadData.utmTerm = leadData.utmTerm
        newLeadData.utmContent = leadData.utmContent
        newLeadData.referer = leadData.referer
        newLeadData.lastUpdated = new Date()
        newLeadData.save(flush: true, failOnError: true)

        LeadUtm firstLeadUtm = LeadUtm.query([lead: leadData, sequence: LeadUtmSequence.FIRST]).get()
        if (firstLeadUtm) leadUtmService.deleteAndCreateNewLeadUtm(newLeadData, firstLeadUtm)

        LeadUtm lastLeadUtm = LeadUtm.query([lead: leadData, sequence: LeadUtmSequence.LAST]).get()
        if (lastLeadUtm) leadUtmService.deleteAndCreateNewLeadUtm(newLeadData, lastLeadUtm)

        createCampaignEventMessageService.saveForAccountEmailChanged(leadData.customer, leadData.email, newEmail)
        return true
    }

    private LeadData create(LeadDataAdapter adapter) {
        String distinctId = adapter.asaasDistinctId ?: adapter.distinctId
        if (!distinctId) distinctId = UUID.randomUUID().toString()

        LeadData leadData = new LeadData()
        leadData.email = adapter.email
        leadData.distinctId = distinctId
        leadData.utmSource = adapter.firstLeadUtm.source
        leadData.registerIp = adapter.registerIp
        leadData.utmMedium = adapter.firstLeadUtm.medium
        leadData.utmCampaign = adapter.firstLeadUtm.campaign
        leadData.utmTerm = adapter.firstLeadUtm.term
        leadData.utmContent = adapter.firstLeadUtm.content
        leadData.referer = adapter.firstLeadUtm.referrer
        leadData.created = new Date()
        leadData.save(flush: true, failOnError: true)

        leadUtmService.save(leadData, adapter.firstLeadUtm)
        leadUtmService.save(leadData, adapter.lastLeadUtm)

        createCampaignEventMessageService.saveForLeadCreated(leadData, adapter)
        return leadData
    }

    private void validate(LeadDataAdapter adapter) {
        if (!adapter.email || !Utils.emailIsValid(adapter.email)) {
            AsaasLogger.error("LeadDataService.validate >> Email inválido: [${adapter.email}]")
        }
    }
}
