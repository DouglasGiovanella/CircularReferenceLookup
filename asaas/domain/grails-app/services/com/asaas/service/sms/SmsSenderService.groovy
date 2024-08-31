package com.asaas.service.sms

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.sms.SmsMessageProvider
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.SmsFailException
import com.asaas.integration.sms.adapter.SmsMessageAdapter
import com.asaas.log.AsaasLogger
import com.asaas.sms.SmsSender
import com.asaas.status.Status
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

import java.security.SecureRandom

@Transactional
class SmsSenderService {

    def smsMessagePontalTechManagerService
    def smsMessageSinchManagerService
    def smsMessageProviderService

    public Boolean send(String message, String phoneNumber, Boolean throwExceptionOnError, Map options) {
        if (!AsaasApplicationHolder.config.sms.enabled) return true

        if (!options.from) options.from = "ASAAS"

        message = Utils.removeDiacritics(message)
        phoneNumber = PhoneNumberUtils.sanitizeNumber(phoneNumber)

        if (!message) {
            if (throwExceptionOnError) throw new SmsFailException("A mensagem SMS não pode estar vazia.")
            return false
        }

        if (!PhoneNumberUtils.validateMobilePhone(phoneNumber)) {
            if (throwExceptionOnError) throw new SmsFailException("O número ${phoneNumber} não é um número móvel válido.")
            return false
        }

        options.resendOnError = false
        SmsMessageProvider smsMessageProvider = options.smsMessageProvider as SmsMessageProvider

        if (!smsMessageProvider) {
            options.resendOnError = true
            smsMessageProvider = options.isSecret ? getSecurityProvider() : chooseSmsProvider()

        } else if (smsMessageProvider.hasSecurityContext()) {
            options.resendOnError = true
        }

        SmsMessageAdapter smsMessageAdapter = sendSmsRequest(message, phoneNumber, smsMessageProvider, options)
        smsMessageAdapter = processSmsResponse(message, phoneNumber, smsMessageProvider, smsMessageAdapter, options)

        return smsMessageAdapter.status.isSuccess()
    }

    public String buildMessage(String template, Map data, List<String> propertiesToBeReduced) {
        Integer maxMessageLength = 140
        String message = buildTemplate(template, data, propertiesToBeReduced)

        if (message.toString().length() > maxMessageLength) {
            Boolean areAllPropertiesEmpty = true

            for (String property : propertiesToBeReduced) {
                if (!data[property]) continue

                areAllPropertiesEmpty = false

                data[property] = data[property].substring(0, data[property].length() - 1)
            }

            if (areAllPropertiesEmpty) {
                throw new RuntimeException("A mensagem ultrapassou o limite de ${maxMessageLength} caracteres: [${message}]")
            }

            return buildMessage(template, data, propertiesToBeReduced)
        }

        return message.toString()
    }

    public String buildTemplate(String template, Map data, List<String> propertiesToBeReduced) {
        for (property in propertiesToBeReduced) {
            String gString = "#{" + property + "}"
            template = template.replace(gString, data[property])
        }
        return template
    }

    private SmsMessageAdapter sendSmsRequest(String message, String phoneNumber, SmsMessageProvider smsMessageProvider, Map options) {
        if (smsMessageProvider.isPontalTech()) {
            return smsMessagePontalTechManagerService.sendNotification(message, phoneNumber, options)
        }

        if (smsMessageProvider.isSinchProvider()) {
            return smsMessageSinchManagerService.sendNotification(message, phoneNumber, smsMessageProvider, options)
        }

        return SmsSender.sendWithRestAPI(message, phoneNumber, smsMessageProvider, options)
    }

    private SmsMessageAdapter processSmsResponse(String message, String phoneNumber, SmsMessageProvider smsMessageProvider, SmsMessageAdapter smsMessageAdapter, Map options) {
        Long apiResponseTimeAdapter = Math.abs(smsMessageAdapter.responseTime.getTime() - smsMessageAdapter.requestTime.getTime())

        if (!options.isSecret || AsaasEnvironment.isDevelopment()) {
            AsaasLogger.confidencial("SmsSenderService.Send >> SMS enviado com sucesso através do parceiro: [${smsMessageProvider}] request: [message: ${options.from}: ${message}, to: ${phoneNumber}] response: [${Utils.truncateString(smsMessageAdapter.responseBody, 255)}] tempo de consumo da API do parceiro: [${apiResponseTimeAdapter} ms]", "sms_message_log")
        }

        if (smsMessageAdapter.status == Status.FAILED) {
            if (!options.isSecret || AsaasEnvironment.isDevelopment()) {
                AsaasLogger.confidencial("SmsSenderService.Send >> Falha no envio de SMS através do parceiro: [${smsMessageProvider}] request: [message: ${options.from}: ${message}, to: ${phoneNumber}] response: [${Utils.truncateString(smsMessageAdapter.responseBody, 255)}] detalhe do erro [${smsMessageAdapter.errorMessage}] tempo de consumo da API do parceiro: [${apiResponseTimeAdapter} ms]", "sms_message_log")
            } else {
                AsaasLogger.warn("SmsSenderService >> ${smsMessageAdapter.exception.getClass()} ao enviar SmsMessage: [${options.id}], para o número: [${phoneNumber}], SmsMessageProvider: [${smsMessageProvider}]", smsMessageAdapter.exception)
            }

            if (!options.resendOnError) {
                throw smsMessageAdapter.exception
            }
            options.resendOnError = false
            smsMessageProvider = chooseFallbackProvider(smsMessageProvider)
            smsMessageAdapter = sendSmsRequest(message, phoneNumber, smsMessageProvider, options)
            return processSmsResponse(message, phoneNumber, smsMessageProvider, smsMessageAdapter, options)
        } else if (smsMessageAdapter.status.isError()) {
            AsaasLogger.warn("SmsSenderService.processSmsResponse >> Falha no envio de SMS através do parceiro: [${ smsMessageProvider }]: [${ smsMessageAdapter.errorMessage }]")
        }

        return smsMessageAdapter
    }

    private SmsMessageProvider chooseSmsProvider() {
        AsaasConfig asaasConfig = AsaasConfig.getInstance()
        if (asaasConfig.smsMessageProvider) return asaasConfig.smsMessageProvider

        SmsMessageProvider smsMessageProvider = drawSmsMessageProvider()

        if (!smsMessageProviderService.getDisabledSmsProviderList().contains(smsMessageProvider)) {
            return smsMessageProvider
        }

        return chooseFallbackProvider(smsMessageProvider)
    }

    private SmsMessageProvider drawSmsMessageProvider() {
        int providerRandWeight = new SecureRandom().nextInt(100)

        if (providerRandWeight < 10) return SmsMessageProvider.SINCH
        if (providerRandWeight < 20) return SmsMessageProvider.INFOBIP

        return SmsMessageProvider.PONTALTECH
    }

    private SmsMessageProvider chooseFallbackProvider(SmsMessageProvider smsMessageProvider) {
        if (smsMessageProvider.hasSecurityContext()) {
            return smsMessageProvider.isInfobipSecurity() ? SmsMessageProvider.SINCH_SECURITY : SmsMessageProvider.INFOBIP_SECURITY
        } else {
            List<SmsMessageProvider> fallbackCandidates = smsMessageProviderService.getFallbackCandidatesList(smsMessageProvider)
            int randProviderIndex = new SecureRandom().nextInt(fallbackCandidates.size())
            return fallbackCandidates.get(randProviderIndex)
        }
    }

    private SmsMessageProvider getSecurityProvider() {
        AsaasConfig asaasConfig = AsaasConfig.getInstance()
        if (!asaasConfig.smsMessageSecurityProvider) return SmsMessageProvider.INFOBIP_SECURITY

        return asaasConfig.smsMessageSecurityProvider
    }
}
