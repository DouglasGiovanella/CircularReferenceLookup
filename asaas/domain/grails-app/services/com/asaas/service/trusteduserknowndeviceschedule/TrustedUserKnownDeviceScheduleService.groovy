package com.asaas.service.trusteduserknowndeviceschedule

import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.login.TrustedUserKnownDeviceSchedule
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class TrustedUserKnownDeviceScheduleService {

    def userKnownDeviceService

    public void saveIfNecessary(UserKnownDevice device) {
        final Integer scheduleDaysAhead = 7
        saveIfNecessary(device, scheduleDaysAhead)
    }

    public void saveIfNecessary(UserKnownDevice device, Integer scheduleDaysAhead) {
        Boolean deviceHasBeenScheduled = TrustedUserKnownDeviceSchedule.query([exists: true, userKnownDevice: device, "scheduledDate[ge]": new Date()]).get().asBoolean()
        if (deviceHasBeenScheduled) return

        TrustedUserKnownDeviceSchedule trustedUserKnownDeviceSchedule = new TrustedUserKnownDeviceSchedule()
        trustedUserKnownDeviceSchedule.userKnownDevice = device
        trustedUserKnownDeviceSchedule.scheduledDate = CustomDateUtils.sumDays(new Date(), scheduleDaysAhead, true)

        trustedUserKnownDeviceSchedule.save(failOnError: true)
    }

    public List<Long> process() {
        final Integer maxItemsPerCycle = 500
        final Integer dateLimitInDays = 7
        final Date today = new Date().clearTime()
        final Date dateLimit = CustomDateUtils.sumDays(today, -dateLimitInDays)

        Map searchParams = [
            column: "userKnownDevice.id",
            "deviceTrustedToCheckout": false,
            "scheduledDate[lt]": today,
            "scheduledDate[ge]": dateLimit,
            disableSort: true
        ]

        List<Long> userKnownDeviceIdList = TrustedUserKnownDeviceSchedule.query(searchParams).list(max: maxItemsPerCycle)

        if (!userKnownDeviceIdList) return []

        Utils.forEachWithFlushSession(userKnownDeviceIdList, 50, { Long userKnownDeviceId ->
            Utils.withNewTransactionAndRollbackOnError({
                UserKnownDevice userKnownDevice = UserKnownDevice.get(userKnownDeviceId)
                userKnownDeviceService.setAsTrustedToCheckoutIfNecessary(userKnownDevice)
            }, [logErrorMessage: "TrustedUserKnownDeviceScheduleService.process >> Falha ao setar device como confiavel: UserKnownDevice [${userKnownDeviceIdList}]"])
        })

        return userKnownDeviceIdList
    }
}
