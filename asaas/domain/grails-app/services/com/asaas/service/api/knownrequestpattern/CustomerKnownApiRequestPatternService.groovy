package com.asaas.service.api.knownrequestpattern

import com.asaas.api.knownrequestpattern.CustomerKnownApiRequestPatternType
import com.asaas.api.knownrequestpattern.adapter.CustomerKnownApiRequestPatternAdapter
import com.asaas.domain.api.ApiRequestLog
import com.asaas.domain.api.knownrequestpattern.CustomerKnownApiRequestPattern
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.service.api.ApiBaseService
import com.asaas.user.UserUtils
import com.asaas.utils.UriUtils
import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class CustomerKnownApiRequestPatternService extends ApiBaseService {

    public CustomerKnownApiRequestPattern saveIfNecessary(Customer customer, CustomerKnownApiRequestPatternAdapter patternAdapter, CustomerKnownApiRequestPatternType type) {
        String currentPatternEncoded = encodePattern(patternAdapter.current)

        CustomerKnownApiRequestPattern customerKnownApiRequestPattern = CustomerKnownApiRequestPattern.query([customer: customer, pattern: currentPatternEncoded, type: type]).get()
        if (customerKnownApiRequestPattern) return customerKnownApiRequestPattern

        customerKnownApiRequestPattern = new CustomerKnownApiRequestPattern()
        customerKnownApiRequestPattern.customer = customer
        customerKnownApiRequestPattern.pattern = currentPatternEncoded
        customerKnownApiRequestPattern.decodedPattern = patternAdapter.current
        customerKnownApiRequestPattern.type = type
        customerKnownApiRequestPattern.save(failOnError: true)

        return customerKnownApiRequestPattern
    }

    public CustomerKnownApiRequestPatternAdapter buildPattern(Map requestParams, Map headers, List extraData) {
        try {
            Map patternData = [:]
            patternData.headers = [
                ["user-agent": headers["user-agent"]],
                ["cloudfront-city": UriUtils.decode(headers["cloudfront-viewer-city"])],
                ["cloudfront-country": UriUtils.decode(headers["cloudfront-viewer-country"])]
            ]
            patternData.parameters = listPropertiesFromRequest(requestParams)
            patternData.extraParameters = extraData.findAll({ it != null }).collect { it.toString() }

            String patternJson = JsonOutput.toJson(patternData)
            if (patternJson.length() > CustomerKnownApiRequestPattern.constraints.decodedPattern.maxSize) {
                AsaasLogger.warn("CustomerKnownApiRequestService.buildPattern >> Pattern gerado é maior que o tamanho da coluna. UserId: ${UserUtils.getCurrentUserId(null)}")
                return null
            }

            return new CustomerKnownApiRequestPatternAdapter(patternJson)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerKnownApiRequestPatternService.buildPattern >>> Falha ao gerar pattern", exception)
        }

        return null
    }

    public void approve(CustomerKnownApiRequestPattern customerKnownApiRequestPattern) {
        if (customerKnownApiRequestPattern.trusted) return

        customerKnownApiRequestPattern.trusted = true
        customerKnownApiRequestPattern.save(failOnError: true)
    }

    public String encodePattern(String pattern) {
        return pattern.encodeAsSHA256()
    }

    private List<String> listPropertiesFromRequest(Map requestParams, Integer currentLevel = 0) {
        final Integer maxLevel = 1

        List<String> propertyList = []

        for (Map.Entry entry : requestParams) {
            if (ApiRequestLog.INTERNAL_REQUEST_PARAMETERS.contains(entry.key.toString())) continue

            propertyList.add(entry.key.toString())

            if (currentLevel < maxLevel && requestParams[entry.key] instanceof Map) {
                propertyList += listPropertiesFromRequest(requestParams[entry.key], ++currentLevel)
            }
        }

        return propertyList
    }
}
