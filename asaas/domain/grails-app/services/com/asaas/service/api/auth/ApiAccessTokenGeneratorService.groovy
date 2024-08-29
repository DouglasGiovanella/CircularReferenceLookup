package com.asaas.service.api.auth

import com.asaas.api.auth.ApiAuthTokenVO
import com.asaas.api.auth.ApiAuthUtils
import com.asaas.utils.CryptographyUtils

import grails.transaction.Transactional

@Transactional
class ApiAccessTokenGeneratorService {

    public ApiAuthTokenVO generateAccessToken(Object domain) {
        String hash = ApiAuthUtils.getHashPrefixIdentifier(domain) + UUID.randomUUID()
        String salt = CryptographyUtils.generateSecureRandom(64)

        String encrpytedAccessToken = (hash + salt).encodeAsSHA256()

        String domainIdentifier = domain.class.simpleName.encodeAsMD5()
        String accessTokenPrefixIdentifier = ApiAuthUtils.getAccessTokenPrefixIdentifier(domain)
        String domainId = domain.id.toString().padLeft(19, "0")

        String accessToken = accessTokenPrefixIdentifier + ("${domainIdentifier}${ApiAuthUtils.DELIMITER}${domainId}${ApiAuthUtils.DELIMITER}${hash}").encodeAsBase64()

        return new ApiAuthTokenVO(accessToken, encrpytedAccessToken, salt)
    }

}
