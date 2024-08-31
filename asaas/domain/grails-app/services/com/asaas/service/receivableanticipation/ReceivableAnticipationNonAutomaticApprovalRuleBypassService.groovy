package com.asaas.service.receivableanticipation

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationNonAutomaticApprovalHistory
import com.asaas.domain.receivableanticipation.ReceivableAnticipationNonAutomaticApprovalRuleBypass
import com.asaas.receivableanticipation.ReceivableAnticipationNonAutomaticApprovalReason
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationNonAutomaticApprovalRuleBypassService {

    public void processBypass(ReceivableAnticipation receivableAnticipation) {
        List<ReceivableAnticipationNonAutomaticApprovalReason> nonAutomaticApprovalReasonList = ReceivableAnticipationNonAutomaticApprovalHistory.query([
            column: "reason",
            anticipationId: receivableAnticipation.id,
        ]).list()
        if (!nonAutomaticApprovalReasonList) return

        for (ReceivableAnticipationNonAutomaticApprovalReason nonAutomaticApprovalReason : nonAutomaticApprovalReasonList) {
            if (!nonAutomaticApprovalReason.getDaysToBypass()) continue

            ReceivableAnticipationNonAutomaticApprovalRuleBypass bypass = ReceivableAnticipationNonAutomaticApprovalRuleBypass.query([
                reason: nonAutomaticApprovalReason,
                customerId: receivableAnticipation.customer.id
            ]).get()

            if (!bypass) {
                bypass = new ReceivableAnticipationNonAutomaticApprovalRuleBypass()
                bypass.customer = receivableAnticipation.customer
                bypass.reason = nonAutomaticApprovalReason
            }

            bypass.expirationDate = CustomDateUtils.sumDays(new Date(), nonAutomaticApprovalReason.getDaysToBypass())
            bypass.save(failOnError: true)
        }
    }
}
