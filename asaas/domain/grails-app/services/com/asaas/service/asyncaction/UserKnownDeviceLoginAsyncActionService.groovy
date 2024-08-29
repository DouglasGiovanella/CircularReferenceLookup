package com.asaas.service.asyncaction

import com.asaas.domain.login.UserKnowDeviceLoginAsyncAction
import com.asaas.domain.login.UserKnownDevice
import com.asaas.login.UserKnownDeviceAdapter
import grails.transaction.Transactional

@Transactional
class UserKnownDeviceLoginAsyncActionService {

    def baseAsyncActionService
    def receivableAnticipationValidationCacheService
    def userKnownDeviceLoginService

    public void save(Long userKnownDeviceId, UserKnownDeviceAdapter userKnownDeviceAdapter) {
        Map asyncActionData = userKnownDeviceAdapter.toAsyncActionData()
        asyncActionData.userKnownDeviceId = userKnownDeviceId

        if (baseAsyncActionService.hasAsyncActionPendingWithSameParameters(UserKnowDeviceLoginAsyncAction, asyncActionData)) return

        baseAsyncActionService.save(new UserKnowDeviceLoginAsyncAction(), asyncActionData)
    }

    public void processUserKnownDeviceLogin() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(UserKnowDeviceLoginAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return

        baseAsyncActionService.processListWithNewTransaction(UserKnowDeviceLoginAsyncAction, asyncActionDataList, { Map asyncActionData ->
            UserKnownDeviceAdapter userKnownDeviceAdapter = UserKnownDeviceAdapter.fromAsyncActionData(asyncActionData)

            UserKnownDevice userKnownDevice = UserKnownDevice.get(asyncActionData.userKnownDeviceId)

            userKnownDeviceLoginService.saveUserKnownDeviceLogin(userKnownDevice, userKnownDeviceAdapter)
        })
    }
}
