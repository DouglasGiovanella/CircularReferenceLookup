package com.asaas.service.abtest

import com.asaas.domain.abtest.AbTestHistory
import com.asaas.domain.abtest.AbTest
import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class AbTestHistoryService {

    public AbTestHistory create(AbTest abTest) {
        User user = UserUtils.getCurrentUser()

        AbTestHistory abTestHistory = new AbTestHistory()
        abTestHistory.abTest = abTest
        abTestHistory.secondaryMetric = abTest.secondaryMetric
        abTestHistory.secondaryMetricHypothesis = abTest.secondaryMetricHypothesis
        abTestHistory.finishDate = new Date()
        abTestHistory.user = user
        abTestHistory.save(failOnError: true)

        return abTestHistory
    }
}
