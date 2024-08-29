package com.asaas.service.blacklist

import com.asaas.blacklist.SanctionOccurrenceSource
import com.asaas.domain.blacklist.SanctionOccurrence
import com.asaas.domain.customer.Customer
import com.asaas.integration.heimdall.dto.blacklist.UnscSanctionOccurrenceDTO
import com.asaas.riskAnalysis.RiskAnalysisReason
import grails.transaction.Transactional

@Transactional
class SanctionOccurrenceService {

    def blackListManagerService
    def messageService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void processCustomerFoundInUnsc(Long id) {
        UnscSanctionOccurrenceDTO unscSanctionOccurrenceDTO = blackListManagerService.getUnscSanctionOccurrence(id)

        Customer customer = Customer.get(unscSanctionOccurrenceDTO.accountId)

        save(customer, SanctionOccurrenceSource.UNSC, id)

        saveRiskAnalysisForCustomerFoundInUnscListIfNecessary(customer)

        messageService.reportCustomerInUnscList(customer, unscSanctionOccurrenceDTO.details.properties)
    }

    private SanctionOccurrence save(Customer customer, SanctionOccurrenceSource source, Long referenceId) {
        Boolean existingSanctionOccurrence = SanctionOccurrence.query([exists: true, customer: customer, source: source, referenceId: referenceId]).get().asBoolean()
        if (existingSanctionOccurrence) return

        SanctionOccurrence sanctionOccurrence = new SanctionOccurrence()
        sanctionOccurrence.customer = customer
        sanctionOccurrence.source = source
        sanctionOccurrence.referenceId = referenceId
        sanctionOccurrence.save(failOnError: true)
        return sanctionOccurrence
    }

    private void saveRiskAnalysisForCustomerFoundInUnscListIfNecessary(Customer customer) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CUSTOMER_FOUND_IN_UNSC_LIST
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        riskAnalysisRequestService.save(customer, riskAnalysisReason, null)
    }
}
