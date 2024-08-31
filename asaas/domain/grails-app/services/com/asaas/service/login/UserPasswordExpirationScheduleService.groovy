package com.asaas.service.login

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.user.User
import com.asaas.domain.user.UserPasswordExpirationSchedule
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserPasswordExpirationScheduleService {

    private static final Integer DAYS_TO_START_SHOW_FIRST_ALERT = 14
    private static final Integer DAYS_TO_START_SHOW_SECOND_ALERT = 7
    private static final Integer DAYS_TO_SEND_MAIL_ALERT = 10

    def asyncActionService
    def securityEventNotificationService
    def userPasswordManagementService

    public void saveScheduleAsyncAction(User user, Integer daysToExpire) {
        try {
            Map asyncActionData = [ userId: user.id, daysToExpire: daysToExpire ]
            AsyncActionType asyncActionType = AsyncActionType.SAVE_USER_PASSWORD_EXPIRATION_SCHEDULE

            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return
            asyncActionService.save(asyncActionType, asyncActionData)
        } catch (Exception exception) {
            AsaasLogger.error("UserPasswordExpirationScheduleService.saveScheduleAsyncAction >> UserID: [${user.id}]", exception)
        }
    }

    public void saveSchedule() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SAVE_USER_PASSWORD_EXPIRATION_SCHEDULE, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                User user = User.read(asyncActionData.userId)
                if (!user) return

                Integer daysToExpire = Integer.valueOf(asyncActionData.daysToExpire)

                schedule(user, daysToExpire)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    AsaasLogger.error("UserPasswordExpirationScheduleService.saveSchedule >> Falha ao salvar agendamento de expiração de senha, UserId: [${asyncActionData.userId}]", exception)
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)}
                ])
        }
    }

    public Map getExpirationPasswordAlertData(User user, Boolean hasShownFirstAlert, Boolean hasShownSecondAlert) {
        if (hasShownFirstAlert && hasShownSecondAlert) {
            return [shouldShowFirstAlert: false, shouldShowSecondAlert: false]
        }

        UserPasswordExpirationSchedule userPasswordExpirationSchedule = UserPasswordExpirationSchedule.query([user: user, ignoreCustomer: true]).get()
        if (!userPasswordExpirationSchedule) return [shouldShowFirstAlert: false, shouldShowSecondAlert: false]

        Date today = new Date().clearTime()
        Date scheduledDate = userPasswordExpirationSchedule.scheduledDate

        Integer daysToExpirePassword = CustomDateUtils.calculateDifferenceInDays(today, scheduledDate)

        if (daysToExpirePassword <= 0) return [shouldShowFirstAlert: false, shouldShowSecondAlert: false]

        return [shouldShowFirstAlert: shouldShowFirstAlert(daysToExpirePassword, hasShownFirstAlert),
                shouldShowSecondAlert: shouldShowSecondAlert(daysToExpirePassword, hasShownSecondAlert),
                daysToExpirePassword: daysToExpirePassword]
    }

    public List<Long> sendExpirationAlertMail() {
        final Integer maxNumberOfMailsToBeSent = 360
        final Date scheduledDate = CustomDateUtils.sumDays(new Date().clearTime(), UserPasswordExpirationScheduleService.DAYS_TO_SEND_MAIL_ALERT)

        List<Long> userIdList = UserPasswordExpirationSchedule.query([column: "user.id", scheduledDate: scheduledDate, "ignoreCustomer": true, hasBeenNotified: false]).list(max: maxNumberOfMailsToBeSent)

        if (!userIdList) return []
        Utils.forEachWithFlushSession(userIdList, 100, { Long userId ->
            Utils.withNewTransactionAndRollbackOnError({
                User user = User.read(userId)
                Map notificationOptions = [isMail: true, daysToExpire: UserPasswordExpirationScheduleService.DAYS_TO_SEND_MAIL_ALERT]
                securityEventNotificationService.notifyAboutUserPasswordExpiration(user, notificationOptions)
                setAsHasBeenNotified(user)
            }, [
                logErrorMessage: "UserPasswordExpirationScheduleService.sendExpirationAlertMail >> erro ao enviar email de expiracao de senha. User [${userId}]"
            ])
        })

        AsaasLogger.info("UserPasswordExpirationScheduleService.sendExpirationAlertMail >> Foram realizadas ${userIdList.size()} tentativas de envio de e-mail de aviso de expiracao de senha.")
        return userIdList
    }

    public void notifyUsersAboutPasswordExpiration() {
        final Date today = new Date().clearTime()
        final Date dateToSendFirstAlert = CustomDateUtils.sumDays(today, UserPasswordExpirationScheduleService.DAYS_TO_START_SHOW_FIRST_ALERT)
        final Date dateToSendSecondAlert = CustomDateUtils.sumDays(today, UserPasswordExpirationScheduleService.DAYS_TO_START_SHOW_SECOND_ALERT)
        List<Date> scheduledDatesToSendAlert = [dateToSendFirstAlert, dateToSendSecondAlert]

        List<Map> scheduleDataList = UserPasswordExpirationSchedule.query([columnList: ["user.id", "scheduledDate"], "scheduledDate[in]": scheduledDatesToSendAlert, ignoreCustomer: true]).list()

        if (!scheduleDataList) return

        Utils.forEachWithFlushSession(scheduleDataList, 100, { Map scheduleData ->
            Utils.withNewTransactionAndRollbackOnError({
                User user = User.read(scheduleData."user.id")
                Integer daysToExpire = CustomDateUtils.calculateDifferenceInDays(new Date().clearTime(), scheduleData."scheduledDate")
                Map notificationOptions = [isMobilePush: true, daysToExpire: daysToExpire]
                securityEventNotificationService.notifyAboutUserPasswordExpiration(user, notificationOptions)
            }, [
                logErrorMessage: "UserPasswordExpirationScheduleService.notifyUsersAboutPasswordExpiration >> erro ao enviar push de expiracao de senha. User [${scheduleData."user.id"}]"
            ])
        })

        AsaasLogger.info("UserPasswordExpirationScheduleService.notifyUsersAboutPasswordExpiration >> ${scheduleDataList.size()} tentativas de envio de push de aviso de expiracao de senha enviados.")
    }

    public List<Long> expireUserPassword() {
        final Integer maxUserPasswordToBeExpired = 1000
        final Date today = new Date().clearTime()
        final Date dateLimit = CustomDateUtils.sumDays(today, -7)

        Map searchParams = [
                column: "id",
                "scheduledDate[le]": today,
                "scheduledDate[ge]": dateLimit,
                hasBeenExpired: false,
                passwordExpired: false,
                ignoreCustomer: true,
                disableSort: true
        ]

        List<Long> userPasswordExpirationIdList = UserPasswordExpirationSchedule.query(searchParams).list(max: maxUserPasswordToBeExpired)

        if (!userPasswordExpirationIdList) return []

        List<Long> expiredIdList = []
        Utils.forEachWithFlushSession(userPasswordExpirationIdList, 100, { Long userPasswordExpirationId ->
            Utils.withNewTransactionAndRollbackOnError({
                UserPasswordExpirationSchedule userPasswordExpiration = UserPasswordExpirationSchedule.get(userPasswordExpirationId)
                userPasswordManagementService.expirePassword(userPasswordExpiration.user)
                userPasswordExpiration.hasBeenExpired = true
                userPasswordExpiration.save(failOnError: true)
                expiredIdList.add(userPasswordExpiration.id)
            }, [
                logErrorMessage: "UserPasswordExpirationScheduleService.expireUserPassword >> erro ao expirar senha do usuario. UserPasswordExpirationSchedule [${userPasswordExpirationId}]"
            ])
        })

        AsaasLogger.info("UserPasswordExpirationScheduleService.expireUserPassword >> ${expiredIdList.size()} senhas expiradas.")
        return userPasswordExpirationIdList
    }

    private Boolean shouldShowFirstAlert(Integer daysToExpirePassword, Boolean hasShownFirstAlert) {
        if (hasShownFirstAlert) return false

        if (daysToExpirePassword <= UserPasswordExpirationScheduleService.DAYS_TO_START_SHOW_SECOND_ALERT) return false
        if (daysToExpirePassword <= UserPasswordExpirationScheduleService.DAYS_TO_START_SHOW_FIRST_ALERT) return true

        return false
    }

    private Boolean shouldShowSecondAlert(Integer daysToExpirePassword, Boolean hasShownSecondAlert) {
        if (hasShownSecondAlert) return false

        if (daysToExpirePassword <= UserPasswordExpirationScheduleService.DAYS_TO_START_SHOW_SECOND_ALERT) return true

        return false
    }

    private void setAsHasBeenNotified(User user) {
        UserPasswordExpirationSchedule userPasswordExpirationSchedule = UserPasswordExpirationSchedule.query([user: user, ignoreCustomer: true]).get()
        if (!userPasswordExpirationSchedule) return

        userPasswordExpirationSchedule.hasBeenNotified = true
        userPasswordExpirationSchedule.save(failOnError: true)
    }

    private void schedule(User user, Integer daysToExpire) {
        BusinessValidation businessValidation = userPasswordManagementService.canExpire(user.customer)
        if (!businessValidation.isValid()) return

        UserPasswordExpirationSchedule userPasswordExpirationSchedule = UserPasswordExpirationSchedule.query([user: user, ignoreCustomer: true]).get()
        if (!userPasswordExpirationSchedule) {
            userPasswordExpirationSchedule = new UserPasswordExpirationSchedule()
            userPasswordExpirationSchedule.user = user
        }
        userPasswordExpirationSchedule.scheduledDate = buildScheduledDate(user, daysToExpire)
        userPasswordExpirationSchedule.hasBeenNotified = false
        userPasswordExpirationSchedule.hasBeenExpired = false

        userPasswordExpirationSchedule.save(failOnError: true)
    }

    private Date buildScheduledDate(User user, Integer daysToExpire) {
        if (UserUtils.isAsaasTeam(user.username)) {
            daysToExpire = UserPasswordExpirationSchedule.PASSWORD_EXPIRATION_DAYS_FOR_ASAAS_TEAM
        }

        return CustomDateUtils.sumDays(new Date().clearTime(), daysToExpire)
    }
}
