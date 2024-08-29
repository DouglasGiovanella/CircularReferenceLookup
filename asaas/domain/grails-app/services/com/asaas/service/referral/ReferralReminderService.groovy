package com.asaas.service.referral

import com.asaas.domain.Referral
import com.asaas.log.AsaasLogger
import com.asaas.referral.ReferralStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReferralReminderService {

    private static final Integer MAX_REMINDERS_THAT_CAN_BE_SENT = 5

    def messageService
    def smsSenderService

    public List<Long> sendInvitationReminder() {
        List<Long> referralIdList = findReferralsReadyForReminder()

        AsaasLogger.info("Encontrado ${referralIdList.size()} convites para relembrar.")

        for (Long id in referralIdList) {
            processInvitationReminder(id)
        }

        return referralIdList
    }

    private List<Long> findReferralsReadyForReminder() {
        Date sevenDaysAgo = CustomDateUtils.sumDays(new Date(), -7)
        sevenDaysAgo.clearTime()
        return Referral.query(["sentRemindersCount[lt]": ReferralReminderService.MAX_REMINDERS_THAT_CAN_BE_SENT, "lastInvitationDate[le]": sevenDaysAgo, unsubscribed: false, statusList: ReferralStatus.canResendInvite(), column: "id", limit: 100, order: "desc"]).list()
    }

    private void incrementSentRemindersCount(Referral referral) {
        referral.sentRemindersCount++
        referral.lastInvitationDate = new Date().clearTime()

        referral.save(failOnError: true)
    }

    private void processInvitationReminder(Long id) {
        Referral.withNewTransaction { transactionStatus ->
            try {
                Referral referral = Referral.get(id)

                if (referral.invitedEmail) messageService.sendReferralReminder(referral)

                if (referral.invitedPhone) {
                    String message = Utils.getMessageProperty("referral.reminderSms.message", [referral.buildInvitationUrl()])
                    smsSenderService.send(message, referral.invitedPhone, false, [:])
                }

                incrementSentRemindersCount(referral)
            } catch (Exception e) {
                transactionStatus.setRollbackOnly()
                AsaasLogger.error("Erro ao processar o lembrete de convite do referral: ${id}", e)
            }
        }
    }
}
