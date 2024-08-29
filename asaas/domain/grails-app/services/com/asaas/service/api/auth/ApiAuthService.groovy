package com.asaas.service.api.auth

import com.asaas.api.auth.ApiAuthResultVO
import com.asaas.api.auth.ApiAuthUtils
import com.asaas.apiconfig.AccessTokenCryptographyUtils
import com.asaas.cache.api.ApiConfigCacheVO
import com.asaas.domain.api.ApiConfig
import com.asaas.domain.api.UserApiKey
import com.asaas.log.AsaasLogger
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.codec.binary.Base64
import org.springframework.web.context.request.RequestContextHolder

import javax.servlet.http.HttpServletRequest

@Transactional
class ApiAuthService {

    def apiConfigCacheService
    def apiConfigService
    def crypterService

    public ApiAuthResultVO authenticate(String accessToken) {
        if (!accessToken) return null

        if (ApiAuthUtils.isLegacyToken(accessToken)) {
            ApiAuthResultVO apiAuthResultVO = authenticateWithLegacyToken(accessToken)
            if (isExpired(apiAuthResultVO)) return null

            return apiAuthResultVO
        }

        String accessTokenWithoutPrefix = ApiAuthUtils.sanitizeAccessTokenPrefixIdentifier(accessToken)

        String decodedAccessToken = new String(Base64.decodeBase64(accessTokenWithoutPrefix))

        String[] accessTokenInfo = decodedAccessToken.split(ApiAuthUtils.DELIMITER)

        final Integer decodedAccessTokenSplitSize = 3
        if (accessTokenInfo.size() < decodedAccessTokenSplitSize) {
            logInvalidAuthAttempt("ApiAuthService.authenticate >> Base64 inválido", accessToken)
            return null
        }

        String domainIdentifier = accessTokenInfo[0]
        Long domainId = Utils.toLong(accessTokenInfo[1])
        String hash = accessTokenInfo[2]

        if (!domainId) {
            logInvalidAuthAttempt("ApiAuthService.authenticate >> O DomainId informado não é um Long válido", accessToken)
            return null
        }

        ApiAuthResultVO apiAuthResultVO
        String persistedAccessToken
        String persistedSalt

        if (domainIdentifier == ApiConfig.class.simpleName.encodeAsMD5()) {
            ApiConfigCacheVO apiConfigCacheVO = apiConfigCacheService.getById(domainId)
            if (!apiConfigCacheVO) return null
            if (!apiConfigCacheVO.accessTokenSalt) {
                logInvalidAuthAttempt("ApiAuthService.authenticate >> ApiConfig encontrado ${apiConfigCacheVO.id} não possui salt persistido", accessToken)
                return null
            }

            persistedAccessToken = apiConfigCacheVO.accessToken
            persistedSalt = apiConfigCacheVO.accessTokenSalt

            apiAuthResultVO = new ApiAuthResultVO(apiConfigCacheVO)
        } else if (domainIdentifier == UserApiKey.class.simpleName.encodeAsMD5()) {
            UserApiKey userApiKey = UserApiKey.query([id: domainId]).get()
            if (!userApiKey) return null
            if (!userApiKey.accessTokenSalt) {
                logInvalidAuthAttempt("ApiAuthService.authenticate >> UserApiKey encontrado ${userApiKey.id} não possui salt persistido", accessToken)
                return null
            }

            persistedAccessToken = userApiKey.encryptedAccessToken
            persistedSalt = userApiKey.accessTokenSalt

            apiAuthResultVO = new ApiAuthResultVO(userApiKey)
        } else {
            logInvalidAuthAttempt("ApiAuthService.authenticate >> O DomainIdentifier informado ${domainIdentifier} não é suportado", accessToken)
            return null
        }

        String encrpytedAccessToken = (hash + persistedSalt).encodeAsSHA256()
        if (encrpytedAccessToken == persistedAccessToken) {
            if (isExpired(apiAuthResultVO)) return null

            return apiAuthResultVO
        }

        return null
    }

    private ApiAuthResultVO authenticateWithLegacyToken(String accessToken) {
        UserApiKey userApiKey = authenticateWithLegacyUserApiKeyToken(accessToken)
        if (userApiKey) return new ApiAuthResultVO(userApiKey)

        ApiConfigCacheVO apiConfigCacheVO = authenticateWithLegacyApiConfigToken(accessToken)
        if (apiConfigCacheVO) return new ApiAuthResultVO(apiConfigCacheVO)

        return null
    }

    private UserApiKey authenticateWithLegacyUserApiKeyToken(String encodedAccessToken) {
        String decodedAccessToken = new String(Base64.decodeBase64(encodedAccessToken))

        String[] accessTokenInfo = decodedAccessToken.split(UserApiKey.DECODED_ACCESS_TOKEN_DELIMITER)

        if (accessTokenInfo.size() < UserApiKey.DECODED_ACCESS_TOKEN_SPLIT_SIZE) return null

        Long domainId = Utils.toLong(accessTokenInfo[0])
        String domainName = accessTokenInfo[1]
        String accessToken = accessTokenInfo[2]

        UserApiKey userApiKey = UserApiKey.query([id: domainId]).get()

        if (userApiKey && userApiKey.class.simpleName == domainName) {
            if (userApiKey.accessTokenSalt) {
                logInvalidAuthAttempt("ApiAuthService.authenticateWithLegacyUserApiKeyToken >> Chave de API foi encontrada pela forma antiga mas possui um salt. ${userApiKey.id}", encodedAccessToken)
                return null
            }

            String decryptedAccessToken = crypterService.decryptDomainProperty(userApiKey, "encryptedAccessToken", userApiKey.encryptedAccessToken)

            if (decryptedAccessToken == accessToken) return userApiKey
        }

        return null
    }

    private ApiConfigCacheVO authenticateWithLegacyApiConfigToken(String accessToken) {
        ApiConfigCacheVO apiConfigCacheVO = apiConfigCacheService.getByEncryptedAccessToken(accessToken.encodeAsMD5())

        if (!apiConfigCacheVO) {
            apiConfigCacheVO = apiConfigCacheService.getByEncryptedAccessToken(AccessTokenCryptographyUtils.encrypt(accessToken))
        }

        if (apiConfigCacheVO?.accessTokenSalt) {
            logInvalidAuthAttempt("ApiAuthService.authenticateWithLegacyApiConfigToken >> Chave de API foi encontrada pela forma antiga mas possui um salt. ${apiConfigCacheVO.id}.", accessToken)
            return null
        }

        return apiConfigCacheVO
    }

    private Boolean isExpired(ApiAuthResultVO apiAuthResultVO) {
        if (!apiAuthResultVO) return false
        if (!apiAuthResultVO.apiConfigCacheVO) return false
        if (!apiAuthResultVO.isAccessTokenExpired()) return false

        ApiConfig apiConfig = ApiConfig.get(apiAuthResultVO.apiConfigCacheVO.id)
        apiConfigService.invalidateAccessToken(apiConfig)
        return true
    }

    private void logInvalidAuthAttempt(String messagePrefix, String accessToken) {
        HttpServletRequest request = RequestContextHolder?.getRequestAttributes()?.currentRequest
        AsaasLogger.confidencial("${messagePrefix} >> IP[${RequestUtils.getRemoteIp()}] | City[${RequestUtils.getCityFromCloudFront()}] | Country[${RequestUtils.getCountryFromCloudFront()}] accessToken[${ApiAuthUtils.sanitizeAccessTokenPrefixIdentifier(accessToken)}] | Resource[${request.requestURI}].", "invalid_api_key_received")
    }
}
