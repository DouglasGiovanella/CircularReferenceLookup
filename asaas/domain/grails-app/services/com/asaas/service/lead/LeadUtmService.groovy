package com.asaas.service.lead

import com.asaas.domain.lead.LeadData
import com.asaas.domain.lead.LeadUtm
import com.asaas.domain.lead.LeadUtmSequence
import com.asaas.lead.adapter.LeadUtmAdapter
import grails.transaction.Transactional

@Transactional
class LeadUtmService {

    public void save(LeadData leadData, LeadUtmAdapter adapter) {
        LeadUtm leadUtm = new LeadUtm()
        leadUtm.lead = leadData
        leadUtm.sequence = adapter.sequence
        leadUtm.source = adapter.source
        leadUtm.medium = adapter.medium
        leadUtm.campaign = adapter.campaign
        leadUtm.term = adapter.term
        leadUtm.content = adapter.content
        leadUtm.gclid = adapter.gclid
        leadUtm.fbclid = adapter.fbclid
        leadUtm.referrer = adapter.referrer
        leadUtm.save(flush: true, failOnError: true)
    }

    public void updateOrCreateLastLeadUtmIfDoesNotExist(LeadData leadData, LeadUtmAdapter adapter) {
        LeadUtm leadUtm = LeadUtm.query([lead: leadData, sequence: LeadUtmSequence.LAST]).get()
        if (!leadUtm) {
            save(leadData, adapter)
            return
        }

        leadUtm.source = adapter.source
        leadUtm.medium = adapter.medium
        leadUtm.campaign = adapter.campaign
        leadUtm.term = adapter.term
        leadUtm.content = adapter.content
        leadUtm.gclid = adapter.gclid
        leadUtm.fbclid = adapter.fbclid
        leadUtm.referrer = adapter.referrer
        leadUtm.save(flush: true, failOnError: true)
    }

    public void saveFirstLeadUtmIfDoesNotExist(LeadData leadData, LeadUtmAdapter adapter) {
        Boolean hasFirstLeadUtm = LeadUtm.query([lead: leadData, sequence: LeadUtmSequence.FIRST, exists: true]).get().asBoolean()
        if (hasFirstLeadUtm) return

        save(leadData, adapter)
    }

    public void deleteAndCreateNewLeadUtm(LeadData leadData, LeadUtm leadUtm) {
        LeadUtm newLeadUtm = new LeadUtm()
        newLeadUtm.lead = leadData
        newLeadUtm.sequence = leadUtm.sequence
        newLeadUtm.source = leadUtm.source
        newLeadUtm.medium = leadUtm.medium
        newLeadUtm.campaign = leadUtm.campaign
        newLeadUtm.term = leadUtm.term
        newLeadUtm.content = leadUtm.content
        newLeadUtm.gclid = leadUtm.gclid
        newLeadUtm.fbclid = leadUtm.fbclid
        newLeadUtm.referrer = leadUtm.referrer
        newLeadUtm.save(flush: true, failOnError: true)

        leadUtm.deleted = true
        leadUtm.save(failOnError: true)
    }
}
