package com.asaas.service.abtest

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.abtest.AbTest
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class AbTestObsoleteCodeCheckService {

    public void checkAbTestObsoleteCode() {
        List<String> abTestNames = getConfiguredAbTestNames()
        if (!abTestNames) return

        final Integer toleranceInWeeks = 3
        final Integer toleranceInDays = 7 * toleranceInWeeks

        Date limitDate = CustomDateUtils.sumDays(new Date(), (toleranceInDays * -1))

        List<Map> finishedAbTests = AbTest.query([
            columnList: ["name", "finishDate", "responsibleSquad"],
            "name[in]": abTestNames,
            "finishDate[lt]": limitDate,
            "finishDate[isNotNull]": true
        ]).list(readOnly: true)
        if (!finishedAbTests) return

        for (Map abTest : finishedAbTests) {
            AsaasLogger.warn("AbTestObsoleteCodeCheckService.checkAbTestObsoleteCode >> ABTest [${abTest.name}] finalizado em [${abTest.finishDate}] e não removido, referente a squad [${abTest.responsibleSquad}]")
        }
    }

    private List<String> getConfiguredAbTestNames() {
        List<String> abTestNames = []

        AsaasApplicationHolder.config.asaas.abtests.flatten().collect { key, value ->
            if (key.contains(".name")) abTestNames.add(value)
        }

        return abTestNames
    }
}
