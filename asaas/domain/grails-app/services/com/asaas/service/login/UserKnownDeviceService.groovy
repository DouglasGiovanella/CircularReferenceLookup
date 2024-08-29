package com.asaas.service.login

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.iplocation.IpLocationAdapter
import com.asaas.log.AsaasLogger
import com.asaas.login.UserKnownDeviceAdapter
import com.asaas.utils.Utils
import com.asaas.utils.WebSessionCrypter
import grails.transaction.Transactional

@Transactional
class UserKnownDeviceService {

    def asaasSegmentioService
    def asyncActionService
    def crypterService
    def userKnownDeviceLoginService
    def userKnownDeviceLoginAsyncActionService
    def securityEventNotificationService

    public void saveAsyncActionIfNecessary(Long userId, UserKnownDeviceAdapter adapter) {
        try {
            if (!adapter.distinctId) {
                AsaasLogger.error("Dispositivo conhecido sem distinctId. User ID: ${userId}, Adapter: [${adapter.properties}]",
                    new BusinessException("DistinctId não pode ser null"))
                return
            }

            User user = User.get(userId)

            UserKnownDevice existingUserKnownDevice = UserKnownDevice.query([user: user, distinctId: adapter.distinctId]).get()
            if (existingUserKnownDevice.asBoolean()) {
                activate(existingUserKnownDevice, adapter)
                userKnownDeviceLoginAsyncActionService.save(existingUserKnownDevice.id, adapter)
                return
            }
            Map asyncActionData = adapter.toAsyncActionData()
            asyncActionData.userId = userId
            if (adapter.sessionId) {
                asyncActionData.sessionId = WebSessionCrypter.encrypt(adapter.sessionId)
            }

            AsyncActionType asyncActionType = AsyncActionType.SAVE_USER_KNOWN_DEVICE

            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

            asyncActionService.save(asyncActionType, asyncActionData)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao salvar AsyncAction do dispositivo conhecido. UserId [${userId}]", exception)
        }
    }

    public void processAsyncActionList() {
        final Integer maxItemsPerCycle = 50

        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.SAVE_USER_KNOWN_DEVICE, maxItemsPerCycle)) {
            Utils.withNewTransactionAndRollbackOnError({
                User user = User.get(asyncActionData.userId)

                UserKnownDeviceAdapter userKnownDeviceAdapter = UserKnownDeviceAdapter.fromAsyncActionData(asyncActionData)
                if (userKnownDeviceAdapter.sessionId) {
                    userKnownDeviceAdapter.sessionId = WebSessionCrypter.decrypt(userKnownDeviceAdapter.sessionId)
                }

                Boolean alreadyHasOneBeforeSave = UserKnownDevice.query([exists: true, user: user]).get().asBoolean()

                UserKnownDevice userKnownDevice = save(user, userKnownDeviceAdapter)
                userKnownDeviceLoginService.saveUserKnownDeviceLogin(userKnownDevice, null)

                if (alreadyHasOneBeforeSave) {
                    securityEventNotificationService.notifyAndSaveHistoryAboutNewDevice(userKnownDevice)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "Erro ao salvar dispositivo conhecido. AsyncActionId: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void deactivateAllExceptCurrent(Long userId) {
        Utils.withNewTransactionAndRollbackOnError ({
            String currentDistinctId = UserKnownDeviceAdapter.getCurrentDistinctId()
            List<UserKnownDevice> activeUserKnownDeviceList =  UserKnownDevice.query(["userId": userId, "distinctId[ne]": currentDistinctId, active: true]).list()

            for (UserKnownDevice currentUserKnownDevice : activeUserKnownDeviceList) {
                deactivate(currentUserKnownDevice)
            }
        }, [logErrorMessage: "UserKnownDeviceService.deactiveAllExceptCurrent -> Erro ao inativar dispositivos (exceto o corrente) para o usuário ${userId}."])
    }

    public void deactivateAll(Long userId) {
        Utils.withNewTransactionAndRollbackOnError ({
            List<UserKnownDevice> activeUserKnownDeviceList =  UserKnownDevice.query(["userId": userId, active: true]).list()

            for (UserKnownDevice currentUserKnownDevice : activeUserKnownDeviceList) {
                deactivate(currentUserKnownDevice)
            }
        }, [logErrorMessage: "UserKnownDeviceService.deactivateAll -> Erro ao inativar todos os dispositivos do usuário ${userId}."])
    }

    public void deactivateCurrent(Long userId) {
        Utils.withNewTransactionAndRollbackOnError ({
            String currentDistinctId = UserKnownDeviceAdapter.getCurrentDistinctId()
            UserKnownDevice userKnownDevice = UserKnownDevice.query(["userId": userId, distinctId: currentDistinctId]).get()

            if (!userKnownDevice) return

            deactivate(userKnownDevice)
        }, [logErrorMessage: "UserKnownDeviceService.deactivateCurrent -> Erro ao inativar o dispositivo corrente para o usuário ${userId}."])
    }

    public void setAsTrustedToCheckoutIfNecessary(UserKnownDevice userKnownDevice) {
        if (userKnownDevice.trustedToCheckout) return

        userKnownDevice.trustedToCheckout = true
        userKnownDevice.save(flush: true, failOnError: true)
    }

    private void activate(UserKnownDevice userKnownDevice, UserKnownDeviceAdapter adapter) {
        if (adapter.sessionId) {
            userKnownDevice.encryptedSessionId = crypterService.encryptDomainProperty(userKnownDevice, "encryptedSessionId", adapter.sessionId)
        }
        userKnownDevice.userApiKey = getUpdatedUserApiKeyIfNecessary(userKnownDevice, adapter)
        userKnownDevice.active = true
        userKnownDevice.lastLogin = new Date()

        userKnownDevice.save(failOnError: true)
    }

    private void deactivate(UserKnownDevice userKnownDevice) {
        if (!userKnownDevice.active) return

        userKnownDevice.active = false
        userKnownDevice.save(failOnError: true)
    }

    private UserApiKey getUpdatedUserApiKeyIfNecessary(UserKnownDevice userKnownDevice, UserKnownDeviceAdapter adapter) {
        if (userKnownDevice.platform.isWeb()) return null
        if (!adapter.userApiKeyId) return null

        if (userKnownDevice.userApiKey?.id == adapter.userApiKeyId) return userKnownDevice.userApiKey

        return UserApiKey.get(adapter.userApiKeyId)
    }

    private UserKnownDevice save(User user, UserKnownDeviceAdapter userKnownDeviceAdapter) {
        Boolean existingUserKnownDevice = UserKnownDevice.query([exists: true, user: user, distinctId: userKnownDeviceAdapter.distinctId]).get().asBoolean()
        if (existingUserKnownDevice) {
            throw new BusinessException("Já existe um registro de dispositivo conhecido com o mesmo user e distinctId. User [${user.id}], DistinctId [${userKnownDeviceAdapter.distinctId}]")
        }

        UserKnownDevice userKnownDevice = new UserKnownDevice()

        userKnownDevice.user = user
        userKnownDevice.distinctId = userKnownDeviceAdapter.distinctId
        userKnownDevice.deviceFingerprint= userKnownDeviceAdapter.deviceFingerprint
        userKnownDevice.platform = userKnownDeviceAdapter.platform
        userKnownDevice.remoteIp = userKnownDeviceAdapter.remoteIp
        userKnownDevice.sourcePort = userKnownDeviceAdapter.sourcePort
        userKnownDevice.operatingSystem = userKnownDeviceAdapter.operatingSystem
        userKnownDevice.device = userKnownDeviceAdapter.device
        userKnownDevice.lastLogin = new Date()

        IpLocationAdapter ipLocationAdapter = userKnownDeviceAdapter.ipLocationAdapter
        userKnownDevice.city = ipLocationAdapter.city
        userKnownDevice.state = ipLocationAdapter.state
        userKnownDevice.country = ipLocationAdapter.country

        Boolean customerHasUserKnownDevice = UserKnownDevice.query([exists: true, "customerId": user.customer.id]).get().asBoolean()
        if (!customerHasUserKnownDevice) {
            userKnownDevice.trustedToCheckout = true
        }

        if (userKnownDeviceAdapter.userApiKeyId) {
            userKnownDevice.userApiKey = UserApiKey.read(userKnownDeviceAdapter.userApiKeyId)
        }

        userKnownDevice.save(failOnError: true)

        if (userKnownDeviceAdapter.sessionId) {
            userKnownDevice.encryptedSessionId = crypterService.encryptDomainProperty(userKnownDevice, "encryptedSessionId", userKnownDeviceAdapter.sessionId)
            userKnownDevice.save(failOnError: true)
        }

        trackDeviceFingerprint(userKnownDevice, userKnownDeviceAdapter)

        return userKnownDevice
    }

    private void trackDeviceFingerprint(UserKnownDevice userKnownDevice, UserKnownDeviceAdapter userKnownDeviceAdapter) {
        User user = userKnownDevice.user

        Map trackInfo = [:]
        trackInfo.userKnownDeviceId = userKnownDevice.id
        trackInfo.userId = user.id
        trackInfo.distinctId = userKnownDevice.distinctId
        trackInfo.deviceFingerprint = userKnownDeviceAdapter.deviceFingerprint
        trackInfo.secondaryDeviceFingerprint = userKnownDeviceAdapter.secondaryDeviceFingerprint
        trackInfo.darwiniuimDeviceFingerprint = userKnownDeviceAdapter.darwiniumDeviceFingerprint

        asaasSegmentioService.track(user.customer.id, "device_fingerprint", trackInfo)
    }
}
