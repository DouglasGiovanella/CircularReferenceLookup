package com.asaas.service.openfinance.automatic.externaldebit

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebit.automatic.ExternalAutomaticDebitRiskInfo
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitRiskInfoAdapter

import grails.transaction.Transactional

@Transactional
class ExternalAutomaticDebitRiskInfoService {

    public ExternalAutomaticDebitRiskInfo save(ExternalDebitRiskInfoAdapter externalDebitRiskInfoAdapter, ExternalDebit externalDebit) {
        ExternalAutomaticDebitRiskInfo externalDebitRiskInfo = new ExternalAutomaticDebitRiskInfo()

        externalDebitRiskInfo.debit = externalDebit
        externalDebitRiskInfo.collectedWithUserPresence = externalDebitRiskInfoAdapter.collectedWithUserPresence

        if (externalDebitRiskInfo.collectedWithUserPresence) {
            externalDebitRiskInfo.openingAccountDate = externalDebitRiskInfoAdapter.openingAccountDate
            externalDebitRiskInfo.externalDeviceId = externalDebitRiskInfoAdapter.externalDeviceId
            externalDebitRiskInfo.deviceRooted = externalDebitRiskInfoAdapter.deviceRooted
            externalDebitRiskInfo.deviceScreenBrightness = externalDebitRiskInfoAdapter.deviceScreenBrightness
            externalDebitRiskInfo.elapsedTimeSinceDeviceBoot = externalDebitRiskInfoAdapter.elapsedTimeSinceDeviceBoot
            externalDebitRiskInfo.deviceOsVersion = externalDebitRiskInfoAdapter.deviceOsVersion
            externalDebitRiskInfo.deviceTimeZoneOffset = externalDebitRiskInfoAdapter.deviceTimeZoneOffset
            externalDebitRiskInfo.deviceLanguage = externalDebitRiskInfoAdapter.deviceLanguage
            externalDebitRiskInfo.deviceScreenDimensionHeight = externalDebitRiskInfoAdapter.deviceScreenDimensionHeight
            externalDebitRiskInfo.deviceScreenDimensionWidth = externalDebitRiskInfoAdapter.deviceScreenDimensionWidth
            externalDebitRiskInfo.deviceGeolocationLatitude = externalDebitRiskInfoAdapter.deviceGeolocationLatitude
            externalDebitRiskInfo.deviceGeolocationLongitude = externalDebitRiskInfoAdapter.deviceGeolocationLongitude
            externalDebitRiskInfo.geolocationType = externalDebitRiskInfoAdapter.geolocationType
            externalDebitRiskInfo.deviceHasCallInProgress = externalDebitRiskInfoAdapter.deviceHasCallInProgress
            externalDebitRiskInfo.deviceHasDevModeEnabled = externalDebitRiskInfoAdapter.deviceHasDevModeEnabled
            externalDebitRiskInfo.deviceHasMockGpsActive = externalDebitRiskInfoAdapter.deviceHasMockGpsActive
            externalDebitRiskInfo.deviceIsEmulated = externalDebitRiskInfoAdapter.deviceIsEmulated
            externalDebitRiskInfo.deviceHasMonkeyRunnerActive = externalDebitRiskInfoAdapter.deviceHasMonkeyRunnerActive
            externalDebitRiskInfo.deviceIsCharging = externalDebitRiskInfoAdapter.deviceIsCharging
            externalDebitRiskInfo.deviceHasUsbConnected = externalDebitRiskInfoAdapter.deviceHasUsbConnected
            externalDebitRiskInfo.deviceAntennaInformation = externalDebitRiskInfoAdapter.deviceAntennaInformation
            externalDebitRiskInfo.deviceAppRecognitionVerdict = externalDebitRiskInfoAdapter.deviceAppRecognitionVerdict
            externalDebitRiskInfo.deviceRecognitionVerdict = externalDebitRiskInfoAdapter.deviceRecognitionVerdict
        } else {
            externalDebitRiskInfo.lastLoginDateOnDevice = externalDebitRiskInfoAdapter.lastLoginDateOnDevice
            externalDebitRiskInfo.pixKeyRegistrationDate = externalDebitRiskInfoAdapter.pixKeyRegistrationDate
        }

        externalDebitRiskInfo.save(failOnError: true)

        return externalDebitRiskInfo
    }
}

