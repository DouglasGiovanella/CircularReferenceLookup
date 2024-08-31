package com.asaas.service.accountingapplication

import grails.transaction.Transactional

@Transactional
class AccountingApplicationService {

    def accountingApplicationAuthenticationService
    def grailsApplication

    public String buildAccountingUrl(String targetController, String targetAction, Map params) {
        String authToken = accountingApplicationAuthenticationService.requestAuthenticationToken()

        Map queryParams = [targetAction: targetAction, targetController: targetController, authToken: authToken]

        if (params) queryParams += params

        String paramString = parseParametersToString(queryParams)

        String path = "${grailsApplication.config.asaas.accountingApplication.url.base}${grailsApplication.config.asaas.accountingApplication.index.path}"
        path += paramString

        return path
    }

    private String parseParametersToString(Map params) {
        String paramString = "?"

        params.each { key ->
            if (params.keySet().last() == key) {
                paramString += "${key}"
            } else {
                paramString += "${key}&"
            }
        }

        return paramString
    }
}
