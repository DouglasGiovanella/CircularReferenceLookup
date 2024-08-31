package com.asaas.service.integration.cerc.batch.receivableunitconciliation


import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.conciliation.receivableunit.WeeklyReceivableUnitSettlementConciliation
import com.asaas.integration.cerc.builder.conciliation.CercWeeklyReceivableUnitSettlementFileBuilder
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class WeeklyReceivableUnitSettlementConciliationService {

    def fileService
    def grailsApplication

    public Boolean generateFile() {
        final Date nextBusinessDay = CustomDateUtils.getNextBusinessDay()
        final Date todayDate = new Date().clearTime()
        final Date lastWeek = CustomDateUtils.getLastWeek().clearTime()

        Boolean conciliationAlreadyExists = WeeklyReceivableUnitSettlementConciliation.query([exists: true, referenceStartDate: lastWeek, referenceEndDate: todayDate]).get().asBoolean()
        if (conciliationAlreadyExists) return false

        final String asaasRootCnpj = grailsApplication.config.asaas.cnpj.substring(1, 9)

        final String fileName = "${CercWeeklyReceivableUnitSettlementFileBuilder.WEEKLY_SETTLEMENT_FILE}_${asaasRootCnpj}_${CustomDateUtils.fromDate(nextBusinessDay, CercWeeklyReceivableUnitSettlementFileBuilder.DATE_FORMAT_PATTERN)}_${CercWeeklyReceivableUnitSettlementFileBuilder.SEQUENCIAL_NUMBER}.csv"
        File file = CercWeeklyReceivableUnitSettlementFileBuilder.build(fileName)
        AsaasFile asaasFile = fileService.createFile(fileName, file.text)
        save(asaasFile, lastWeek, todayDate)

        return false
    }

    private void save(AsaasFile asaasFile, Date referenceStartDate, Date referenceEndDate) {
        WeeklyReceivableUnitSettlementConciliation weeklyReceivableUnitSettlementConciliation = new WeeklyReceivableUnitSettlementConciliation()

        weeklyReceivableUnitSettlementConciliation.asaasFile = asaasFile
        weeklyReceivableUnitSettlementConciliation.referenceStartDate = referenceStartDate
        weeklyReceivableUnitSettlementConciliation.referenceEndDate = referenceEndDate

        weeklyReceivableUnitSettlementConciliation.save(failOnError: true)
    }
}
