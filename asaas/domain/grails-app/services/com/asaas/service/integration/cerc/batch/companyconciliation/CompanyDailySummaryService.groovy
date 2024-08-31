package com.asaas.service.integration.cerc.batch.companyconciliation

import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.conciliation.company.CompanyDailySummary
import grails.transaction.Transactional

@Transactional
class CompanyDailySummaryService {

    public Boolean save(Date referenceDate) {
        Boolean todaysSummaryAlreadyExists = CompanyDailySummary.query([referenceDate: referenceDate.clearTime(), exists: true]).get().asBoolean()
        if (todaysSummaryAlreadyExists) return false

        List<Map> companiesSyncedActiveAndSuspendedStatusCountList = CercCompany.countBySyncedActiveAndSuspendedStatusList(referenceDate).list()

        CompanyDailySummary dailySummary = new CompanyDailySummary()
        dailySummary.referenceDate = referenceDate
        dailySummary.activeStatusCount = companiesSyncedActiveAndSuspendedStatusCountList.find { it.status.isActive() }.count
        dailySummary.suspendedStatusCount = companiesSyncedActiveAndSuspendedStatusCountList.find { it.status.isSuspended() }.count
        dailySummary.save(failOnError: true)

        return true
    }
}
