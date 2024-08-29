package com.asaas.service.login

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.login.UserTrustedDevice
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class UserTrustedDeviceService {

    def asyncActionService

    public void processRecalculateExpirationDate() {
        final Integer maxItemsPerCycle = 100

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.RECALCULATE_USER_TRUSTED_DEVICE_EXPIRATION_DATE, maxItemsPerCycle)
        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Long userId = asyncActionData.userId
                String identifier = asyncActionData.identifier

                calculateExpirationDate(userId, identifier)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "UserTrustedDeviceService > Erro ao recalcular data de expiracao de UserTrustedDevice. AsyncActionId: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void save(Long userId, String identifier) {
        if (!identifier) return

        User user = User.get(userId)

        UserTrustedDevice userTrustedDevice = UserTrustedDevice.query([user: user, identifier: identifier]).get()
        if (!userTrustedDevice) {
            userTrustedDevice = new UserTrustedDevice()
            userTrustedDevice.user = user
            userTrustedDevice.identifier = identifier
        }

        userTrustedDevice.expirationDate = userTrustedDevice.calculateTrustedExpirationDate()
        userTrustedDevice.save(failOnError: true)
    }

    public void saveRecalculateUserTrustedDeviceExpirationDateAsyncAction(Long userId, String identifier) {
        if (!AsaasEnvironment.isProduction()) return

        AsyncActionType asyncActionType = AsyncActionType.RECALCULATE_USER_TRUSTED_DEVICE_EXPIRATION_DATE
        Map asyncActionData = [:]
        asyncActionData.userId = userId
        asyncActionData.identifier = identifier

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void invalidateAll(User user) {
        Utils.withNewTransactionAndRollbackOnError ({
            List<UserTrustedDevice> userTrustedDeviceList = UserTrustedDevice.query(["user": user, "expirationDate[ge]": new Date()]).list()

            for (UserTrustedDevice userTrustedDevice in userTrustedDeviceList) {
                userTrustedDevice.deleted = true
                userTrustedDevice.save(failOnError: true)
            }
        }, [logErrorMessage: "Erro ao invalidar UserTrustedDevices para o usuário ${user.id}."])
    }

    public Boolean existsUserTrustedDevice(User user, String identifier) {
        return UserTrustedDevice.query([exists: true, user: user, identifier: identifier, "expirationDate[ge]": new Date()]).get().asBoolean()
    }

    private void calculateExpirationDate(Long userId, String identifier) {
        if (!identifier) return

        UserTrustedDevice userTrustedDevice = UserTrustedDevice.query(["userId": userId, "identifier": identifier]).get()
        if (!userTrustedDevice) return

        userTrustedDevice.expirationDate = userTrustedDevice.calculateTrustedExpirationDate()
        userTrustedDevice.save(failOnError: true)
    }

}
