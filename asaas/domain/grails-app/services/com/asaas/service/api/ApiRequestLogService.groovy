package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.auth.ApiAuthUtils
import com.asaas.domain.api.ApiRequestLog
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.UriUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class ApiRequestLogService {

    private static final List<String> ALLOWED_HEADERS = ["host", "x-forwarded-for", "x-amzn-trace-id",
                                                        "user-agent", "cloudfront-viewer-address",
                                                        "cloudfront-viewer-latitude", "cloudfront-viewer-longitude",
                                                        "cloudfront-viewer-country", "cloudfront-viewer-country-name",
                                                        "cloudfront-viewer-country-region", "cloudfront-viewer-country-region-name",
                                                        "cloudfront-viewer-city", "cloudfront-viewer-postal-code"]
    def apiRequestLogOriginService

    public ApiRequestLog save(Long customerId, Long userApiKeyId, Date requestDate, String url, String remoteIp, String method, Map requestParams, Map responseParams, Integer responseStatus) {
        if (!isLoggable(url, method, responseStatus, responseParams)) return null

        ApiRequestLog.withNewTransaction { status ->
            try {
                ApiRequestLog apiRequest = new ApiRequestLog()
                apiRequest.url = url.take(100)
                apiRequest.remoteIp = remoteIp.take(20)
                apiRequest.method = method
                apiRequest.customerId = customerId
                apiRequest.userApiKeyId = userApiKeyId
                apiRequest.responseHttpStatus = responseStatus
                apiRequest.dateCreated = requestDate
                apiRequest.lastUpdated = new Date()

                Map headers = RequestUtils.getHeaders()
                headers = sanitizeHeaders(headers)

                Map requestData = [headers: headers]
                if (requestParams) requestData.request = requestParams.clone()

                apiRequest.requestData = serializeData(requestData).take(10000)
                if (responseParams) apiRequest.responseData = serializeData(responseParams).take(10000)

                apiRequest.save(failOnError: true)

                if (!ApiBaseParser.getRequestOrigin().isOther()) {
                    apiRequestLogOriginService.save(apiRequest)
                }

                return apiRequest
            } catch (Throwable throwable) {
                AsaasLogger.error("ApiRequestLogService.save >> CustomerId: [${ApiBaseParser.getProviderId()}], CustomerName: [${requestParams.name}], Observations: [${requestParams.observations}], Method: [${method}], Url: [${url}]", throwable)
                status.setRollbackOnly()
            }

            return null
        }
    }

    public String serializeData(Map dataMap) {
        Map clonedData = dataMap.clone()
        if (clonedData.request) {
            for (String internalParameter : ApiRequestLog.INTERNAL_REQUEST_PARAMETERS) {
                clonedData.request.remove(internalParameter)
            }
        }

        String logDataString = getSanitizedString(clonedData, [])
        logDataString = ApiAuthUtils.sanitizeApiKeyIfNecessary(logDataString)
        logDataString = StringUtils.replaceEmojis(logDataString, " (emoji) ")

        return logDataString
    }

    public String getSanitizedString(Map logData, List<String> additionalSecretKeys) {
        Map sanitizedData = sanitizeLog(logData, 1, additionalSecretKeys)
        return (sanitizedData as JSON).toString()
    }

    private Map sanitizeLog(Map logData, Integer level = 1, List<String> additionalSecretKeys) {
        final List<String> secretKeys = ["access_token", "secretKey", "pin", "apiKey", "authToken", "accessToken", "creditCardNumber", "dueDateMonth", "dueDateYear"] + additionalSecretKeys
        final List<String> partOfSecretKeys = ["password", "senha", "cvv", "ccv", "token"]
        final Integer maxRecursivityLevel = 5

        Map newLogData = [:]

        for (Map.Entry entry : logData) {
            Boolean isASecretKey = secretKeys.any { it.toLowerCase().equals(entry.key.toLowerCase()) }
            Boolean isAPartOfSecretKey = partOfSecretKeys.any { entry.key.toLowerCase().contains(it.toLowerCase()) }

            def entryValue = logData[entry.key]

            Boolean isCpf = isCpf(entry, entryValue)

            Boolean isSensitiveData = isNotNullOrEmptyValue(entryValue) && (isASecretKey || isAPartOfSecretKey || isCpf)
            if (isSensitiveData || level >= maxRecursivityLevel) {
                newLogData[entry.key] = "********"
                continue
            }

            Boolean isFile = entryValue instanceof CommonsMultipartFile || entry.key.endsWith("FromMultiFileMap")
            if (isFile) {
                newLogData[entry.key] = "--- file content suppressed ---"
                continue
            }

            Boolean isCreditCardKey = entry.key.toLowerCase().contains("card")
            if (isCreditCardKey && AsaasEnvironment.isProduction()) {
                if (entryValue instanceof Map) {
                    Map sanitizedCreditCardInfo = [:]

                    for (String creditCardInfoKey in entryValue.keySet()) {

                        if (isNotNullOrEmptyValue(entryValue[creditCardInfoKey])) {
                            sanitizedCreditCardInfo[creditCardInfoKey] = obfuscateCreditCardValueIfNecessary(creditCardInfoKey, entryValue[creditCardInfoKey])
                        } else {
                            sanitizedCreditCardInfo[creditCardInfoKey] = entryValue[creditCardInfoKey]
                        }
                    }

                    newLogData[entry.key] = sanitizedCreditCardInfo
                } else if (isNotNullOrEmptyValue(entryValue)) {
                    newLogData[entry.key] = obfuscateCreditCardValueIfNecessary(entry.key, entryValue)
                }

                continue
            }

            if (entryValue instanceof Map) {
                newLogData[entry.key] = sanitizeLog(entryValue, level + 1, additionalSecretKeys)
            } else {
                newLogData[entry.key] = entryValue
            }
        }

        return newLogData
    }

    private Boolean isNotNullOrEmptyValue(value) {
        if (value instanceof GString || value instanceof String) {
            return value?.trim()?.asBoolean()
        }

        return value != null
    }

    private String obfuscateCreditCardValueIfNecessary(String key, value) {
        if (value instanceof String && (key.toLowerCase().contains("number") || key.toLowerCase().contains("token"))) {
            String sanitizedValue = StringUtils.removeWhitespaces(value)
            if (sanitizedValue.length() >= 14) {
                return org.apache.commons.lang.StringUtils.overlay(value, "********", 4, value.length() - 4)
            }
        }

        return "********"
    }

    private Boolean isLoggable(String url, String method, Integer responseStatus, Map responseData) {
        if (method == "GET" && (url.endsWith("getCurrentBalance") || url == "/api/v3/finance/balance")) return true

        if (method == "GET" && responseStatus != 500) return false
        if (isWooCommerceRequestWithNotUpdatedPluginDeleteError(url, method, responseStatus, responseData)) return false

        return true
    }

    private Boolean isWooCommerceRequestWithNotUpdatedPluginDeleteError(String url, String method, Integer responseStatus, Map responseData) {
        if (!ApiBaseParser.getRequestOrigin().isWooCommerce()) return false
        if (method != "DELETE") return false
        if (!url.startsWith("/api/v3/payments")) return false
        if (responseStatus != 400) return false
        if (!responseData) return false
        if (!(responseData.errors instanceof List)) return false
        if (responseData.errors[0]?.code != "required_payment_id") return false

        return true
    }

    private Boolean isCpf(Map.Entry entry, Object value) {
        final List<String> cpfKeys = ["cpf", "cpfcnpj"]
        Boolean isACpfKey = cpfKeys.any { it.toLowerCase().equals(entry.key.toLowerCase()) }

        if (!isACpfKey) return false
        if (!isNotNullOrEmptyValue(entry.value)) return false

        String validatedCpf = CpfCnpjUtils.buildCpf(value.toString())
        return validatedCpf.asBoolean()
    }

    private Map sanitizeHeaders(Map headers) {
        List<Object> keys = headers.keySet().toList()

        for (Object key : keys) {
            if (!ALLOWED_HEADERS.contains(key)) {
                headers.remove(key)
            }
        }

        String countryRegionNameKey = "cloudfront-viewer-country-region-name"
        if (headers.containsKey(countryRegionNameKey)) {
            headers[countryRegionNameKey] = sanitizeCloudfrontViewerNames(headers, countryRegionNameKey)
        }

        String cityKey = "cloudfront-viewer-city"
        if (headers.containsKey(cityKey)) {
            headers[cityKey] = sanitizeCloudfrontViewerNames(headers, cityKey)
        }

        return headers
    }

    private String sanitizeCloudfrontViewerNames(Map headers, String key) {
        return UriUtils.decode(headers[key].toString())
    }
}
