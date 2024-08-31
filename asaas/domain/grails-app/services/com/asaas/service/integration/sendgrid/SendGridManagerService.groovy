package com.asaas.service.integration.sendgrid

import com.asaas.domain.customer.Customer
import com.asaas.domain.email.AsaasMailMessage
import com.asaas.domain.email.AsaasMailMessageExternalNotificationTemplate
import com.asaas.domain.email.AsaasSecurityMailMessage
import com.asaas.domain.notification.ExternalNotificationTemplate
import com.asaas.log.AsaasLogger
import com.asaas.sendgrid.SendGridManager
import com.asaas.sendgrid.exception.SendGridException
import com.asaas.service.integration.sendgrid.dto.SendGridAssociateRequestDTO
import com.asaas.service.integration.sendgrid.dto.SendGridCreateApiKeyRequestDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.SendGridSendMailRequestDTO
import com.asaas.service.integration.sendgrid.dto.SendGridCreateSubUserRequestDTO
import com.asaas.service.integration.sendgrid.dto.SendGridCreateTemplateRequestDTO
import com.asaas.service.integration.sendgrid.dto.SendGridCreateTemplateVersionRequestDTO
import com.asaas.service.integration.sendgrid.dto.sendmailrequest.SendGridSendTemplateMailRequestDTO
import grails.transaction.Transactional
import groovy.json.JsonSlurper

@Transactional
class SendGridManagerService {

    def customerMailService
    def customerNotificationConfigService

    SendGridManager sendGridManager = new SendGridManager()

    public Boolean sendFromSecurityContext(AsaasSecurityMailMessage asaasSecurityMailMessage) {
        final String path = "v3/mail/send"

        SendGridSendMailRequestDTO mailRequestDTO = new SendGridSendMailRequestDTO(asaasSecurityMailMessage)
        try {
            sendGridManager.postFromSecurityContext(path, mailRequestDTO.toMap())
        } catch (IOException ioException) {
            throw new SendGridException(ioException)
        }

        return sendGridManager.isSuccessful()
    }

    public Boolean sendMailFromTemplate(AsaasMailMessage asaasMailMessage) {
        AsaasMailMessageExternalNotificationTemplate asaasMailMessageExternalNotificationTemplate = AsaasMailMessageExternalNotificationTemplate.query([asaasMailMessageId: asaasMailMessage.id]).get()
        ExternalNotificationTemplate externalNotificationTemplate = asaasMailMessageExternalNotificationTemplate.externalTemplate
        Map externalTemplateData = new JsonSlurper().parseText(asaasMailMessageExternalNotificationTemplate.externalTemplateData) as Map
        Customer customer = asaasMailMessage.notificationRequest.customerAccount.provider

        final String path = "v3/mail/send"
        String apiKey = customerMailService.getEmailApiKey(customer)
        Map mailRequestMap = new SendGridSendTemplateMailRequestDTO(asaasMailMessage, externalNotificationTemplate.externalId, externalTemplateData).toMap()

        sendGridManager.post(path, mailRequestMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return true

        AsaasLogger.error("SendGridManagerService.sendMailFromTemplate -> Falha na requisição POST. AsaasMailMessage: [${asaasMailMessage.id}]. ExternalNotificationTemplate: [${externalNotificationTemplate.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha no envio de e-mail por template dinâmico")
    }

    public String createTemplate(Customer customer, String templateName) {
        final String path = "v3/templates"
        String apiKey = customerMailService.getEmailApiKey(customer)
        Map createTemplateMap = new SendGridCreateTemplateRequestDTO(templateName).toMap()

        sendGridManager.post(path, createTemplateMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBody.id

        AsaasLogger.error("SendGridManagerService.createTemplate -> Falha na requisição POST. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na criação de template dinâmico")
    }

    public String createTemplateVersion(Customer customer, String templateExternalId, Map templateParams) {
        final String path = "v3/templates/${templateExternalId}/versions"
        String apiKey = customerMailService.getEmailApiKey(customer)
        Map createTemplateVersionMap = new SendGridCreateTemplateVersionRequestDTO(templateParams.name, templateParams.subject, templateParams.body).toMap()

        sendGridManager.post(path, createTemplateVersionMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBody.id

        AsaasLogger.error("SendGridManagerService.createTemplateVersion -> Falha na requisição POST. Customer: [${customer.id}]. ExternalTemplate: [${templateExternalId}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na criação de versão de template dinâmico")
    }

    public Boolean updateTemplateVersion(Customer customer, String templateExternalId, String templateVersionExternalId, Map templateParams) {
        final String path = "v3/templates/${templateExternalId}/versions/${templateVersionExternalId}"
        String apiKey = customerMailService.getEmailApiKey(customer)
        Map updateTemplateVersionMap = new SendGridCreateTemplateVersionRequestDTO(templateParams.name, templateParams.subject, templateParams.body).toMap()

        sendGridManager.patch(path, updateTemplateVersionMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return true

        AsaasLogger.error("SendGridManagerService.updateTemplateVersion -> Falha na requisição POST. Customer: [${customer.id}]. ExternalTemplate: [${templateExternalId}]. TemplateVersion: [${templateVersionExternalId}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na atualização de versão de template dinâmico")
    }

    public Boolean createSubUser(Customer customer, List<String> ipList) {
        final String path = "v3/subusers"
        String apiKey = customerMailService.getEmailApiKey(customer)
        Map createSubUserMap = new SendGridCreateSubUserRequestDTO(customer, customerNotificationConfigService.getCustomerEmailProviderCredentials(customer), ipList).toMap()

        sendGridManager.post(path, createSubUserMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return true

        AsaasLogger.error("SendGridManagerService.createSubUser -> Falha na requisição POST. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na criação de subconta")
    }

    public Map createApiKey(Customer customer, String apiKeyName) {
        final String path = "v3/api_keys"
        String apiKey = customerMailService.getEmailApiKey(customer)
        String username = customerNotificationConfigService.getCustomerEmailProviderCredentials(customer).username
        Map createApiKeyMap = new SendGridCreateApiKeyRequestDTO(apiKeyName).toMap()

        sendGridManager.post(path, createApiKeyMap, apiKey, [onBehalfOf: username])
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBody

        AsaasLogger.error("SendGridManagerService.createApiKey -> Falha na requisição POST. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na criação de APIKey para subconta")
    }

    public List<Map> getIpList(Customer customer) {
        final String path = "v3/ips"
        String apiKey = customerMailService.getEmailApiKey(customer)

        sendGridManager.returnAsList = true
        sendGridManager.get(path, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBodyList

        AsaasLogger.error("SendGridManagerService.getIpList -> Falha na requisição GET. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na busca de IPs para envio de e-mail")
    }

    public List<Map> getDomainList(Customer customer) {
        final String path = "v3/whitelabel/domains"
        String apiKey = customerMailService.getEmailApiKey(customer)

        sendGridManager.returnAsList = true
        sendGridManager.get(path, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBodyList

        AsaasLogger.error("SendGridManagerService.getDomainList -> Falha na requisição GET. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na busca de Domains para envio de e-mail")
    }

    public Map associateDomain(Customer customer, String domainId) {
        final String path = "v3/whitelabel/domains/${domainId}/subuser"
        String apiKey = customerMailService.getEmailApiKey(customer)
        String username = customerNotificationConfigService.getCustomerEmailProviderCredentials(customer).username
        Map associateMap = new SendGridAssociateRequestDTO(username).toMap()

        sendGridManager.post(path, associateMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBody

        AsaasLogger.error("SendGridManagerService.associateDomain -> Falha na requisição POST. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na configuração de Domains para envio de e-mail")
    }

    public Map getDefaultLinkBranding(Customer customer) {
        final String path = "v3/whitelabel/links/default"
        String apiKey = customerMailService.getEmailApiKey(customer)

        sendGridManager.returnAsList = false
        sendGridManager.get(path, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBody

        AsaasLogger.error("SendGridManagerService.getDefaultLinkBranding -> Falha na requisição GET. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na busca do Default Link Branding para envio de e-mail")
    }

    public Map associateLinkBranding(Customer customer, String linkId) {
        final String path = "v3/whitelabel/links/${linkId}/subuser"
        String apiKey = customerMailService.getEmailApiKey(customer)
        String username = customerNotificationConfigService.getCustomerEmailProviderCredentials(customer).username
        Map associateMap = new SendGridAssociateRequestDTO(username).toMap()

        sendGridManager.post(path, associateMap, apiKey, null)
        if (sendGridManager.isSuccessful()) return sendGridManager.responseBody

        AsaasLogger.error("SendGridManagerService.associateLinkBranding -> Falha na requisição POST. Customer: [${customer.id}]. Status: [${sendGridManager.statusCode}]. ErrorMessage: [${sendGridManager.errorMessage}]")
        throw new SendGridException("Falha na configuração de Link Branding para envio de e-mail")
    }

    private void deleteEmailFromBouncesListFromSecurityContext(String email) {
        final String endpoint = "api/bounces.delete.json"

        Map params = [email: email]

        String url = buildUrl(endpoint, params)

        try {
            sendGridManager.postFromSecurityContext(url, [:])
        } catch (Exception exception) {
            AsaasLogger.error("SendGridManagerService.deleteEmailFromBouncesListFromSecurityContext >> Erro ao remover e-mail da bounces list do SendGrid de seguranca. AsaasSecurityMailMessageId [${asaasSecurityMailMessage.id}].", exception)
        }
    }

    private String buildUrl(String endpoint, Map params) {
        StringBuilder urlBuilder = new StringBuilder()
        urlBuilder.append(endpoint)
        urlBuilder.append(buildUrlParams(params))

        return urlBuilder.toString()
    }

    private String buildUrlParams(Map paramsMap) {
        if (!paramsMap) return ""

        StringBuilder params = new StringBuilder()
        params.append("?")
        paramsMap.collect {
            params.append("${it.key}=${it.value}&")
        }

        return params.toString()
    }
}
