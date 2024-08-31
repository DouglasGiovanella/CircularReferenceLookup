package com.asaas.service.login.thirdpartyloginvalidation

import com.asaas.login.thirdpartyloginvalidation.ThirdPartyLoginValidationAdapter
import com.asaas.utils.DatabaseBatchUtils
import grails.transaction.Transactional

@Transactional
class ThirdPartyLoginValidationService {

    def dataSource

    public void saveInBatch(List<ThirdPartyLoginValidationAdapter> thirdPartyLoginValidationAdapterList) {
        List<Map> thirdPartyLoginValidationListListToInsert = thirdPartyLoginValidationAdapterList.collect { ThirdPartyLoginValidationAdapter thirdPartyLoginValidationAdapter ->
            return [
                "version": "0",
                "date_created": new Date(),
                "deleted": 0,
                "username": thirdPartyLoginValidationAdapter.username,
                "identifier": thirdPartyLoginValidationAdapter.identifier,
                "last_updated": new Date(),
                "remote_ip": thirdPartyLoginValidationAdapter.remoteIp,
                "browser_id": thirdPartyLoginValidationAdapter.browserId,
                "user_agent": thirdPartyLoginValidationAdapter.userAgent,
                "login_attempt_date": thirdPartyLoginValidationAdapter.loginAttemptDate,
                "device_fingerprint": thirdPartyLoginValidationAdapter.deviceFingerprint,
                "result": thirdPartyLoginValidationAdapter.result?.toString(),
                "auth_score": thirdPartyLoginValidationAdapter.authScore,
                "source": thirdPartyLoginValidationAdapter.source?.toString()
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "logs.third_party_login_validation", thirdPartyLoginValidationListListToInsert)
    }
}
