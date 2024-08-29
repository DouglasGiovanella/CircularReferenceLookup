package com.asaas.service.api.auth

import com.asaas.api.auth.ApiAuthTokenVO
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.user.User
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class UserApiKeyService {

    def apiAccessTokenGeneratorService

    public String generateAccessToken(User user, String device) {
        invalidateAll(user, device)

        UserApiKey userApiKey = new UserApiKey()
        userApiKey.user = user
        userApiKey.device = device
        userApiKey.save(failOnError: true, flush: true)

        ApiAuthTokenVO apiAuthTokenVo = apiAccessTokenGeneratorService.generateAccessToken(userApiKey)

        userApiKey.encryptedAccessToken = apiAuthTokenVo.encrpytedAccessToken
        userApiKey.accessTokenSalt = apiAuthTokenVo.salt
        userApiKey.save(failOnError: true, flush: true)

        return apiAuthTokenVo.accessToken
    }

    public void invalidateAll(User user, String device) {
        List<UserApiKey> currentDeviceApiKeyList = UserApiKey.query([user: user, device: device]).list()

        for (UserApiKey userApiKey : currentDeviceApiKeyList) {
            userApiKey.deleted = true
            userApiKey.save(failOnError: true)
        }
    }

    public void invalidateAll(User user, Boolean isUserDeletion) {
        if (!isUserDeletion && !canInvalidateUserApiKey(user)) return

        List<Long> userApiKeyIdList = UserApiKey.query([user: user, column: "id"]).list()
        Utils.forEachWithFlushNewSession(userApiKeyIdList, 50, { Long userApiKeyId ->
            UserApiKey userApiKey = UserApiKey.get(userApiKeyId)
            userApiKey.deleted = true
            userApiKey.save(failOnError: true)
        })
    }

    public void delete(UserApiKey userApiKey) {
        userApiKey.deleted = true
        userApiKey.save(failOnError: true)
    }

    private Boolean canInvalidateUserApiKey(User user) {
        if (user.isAsaasErpUser()) return false

        return true
    }
}
