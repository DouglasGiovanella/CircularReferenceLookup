package com.asaas.service.pushnotificationconfig

import com.asaas.api.auth.ApiAuthUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.domain.pushnotification.PushNotificationType
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationConfigAlertType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.UriUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PushNotificationConfigService {

    private static final Integer DEFAULT_API_VERSION = 3

    def crypterService
    def grailsApplication
    def modulePermissionService
    def pushNotificationConfigAlertQueueService
    def pushNotificationConfigCacheService
    def pushNotificationConfigEventService
    def pushNotificationConfigWithPendingRequestCacheService

    public PushNotificationConfig save(Customer customer, Map fields) {
        PushNotificationConfig validatedConfig = validateSave(customer, fields)
        if (validatedConfig.hasErrors()) return validatedConfig

        PushNotificationConfig pushNotificationConfig = new PushNotificationConfig()
        pushNotificationConfig.provider = customer
        pushNotificationConfig.publicId = UUID.randomUUID()
        pushNotificationConfig.type = fields.type
        pushNotificationConfig.name = fields.name
        pushNotificationConfig.url = fields.url
        pushNotificationConfig.email = fields.email
        pushNotificationConfig.enabled = fields.enabled
        pushNotificationConfig.poolInterrupted = fields.poolInterrupted
        pushNotificationConfig.apiVersion = Utils.toInteger(fields.apiVersion ? fields.apiVersion : DEFAULT_API_VERSION)
        pushNotificationConfig.sendType = fields.sendType
        pushNotificationConfig.application = fields.application

        pushNotificationConfig.save(flush: true)

        if (pushNotificationConfig.hasErrors()) return pushNotificationConfig

        if (fields.accessToken) {
            pushNotificationConfig.accessToken = crypterService.encryptDomainProperty(pushNotificationConfig, "accessToken", fields.accessToken, grailsApplication.config.asaas.crypter.webhook.accessToken.secret)

            pushNotificationConfig.save(failOnError: true)
        }

        List<PushNotificationRequestEvent> events = fields.events.collect { PushNotificationRequestEvent.convert(it) }
        pushNotificationConfigEventService.save(pushNotificationConfig, events)

        return pushNotificationConfig
    }

    public PushNotificationConfig update(PushNotificationConfig pushNotificationConfig, Map fields) {
        PushNotificationConfig validatedConfig = validateUpdate(pushNotificationConfig, fields)
        if (validatedConfig.hasErrors()) return validatedConfig

        if (fields.containsKey("sendType")) pushNotificationConfig.sendType = fields.sendType
        if (fields.containsKey("name")) pushNotificationConfig.name = fields.name
        if (fields.containsKey("url")) pushNotificationConfig.url = fields.url
        if (fields.containsKey("email")) pushNotificationConfig.email = fields.email
        if (fields.containsKey("type")) pushNotificationConfig.type = fields.type
        if (fields.containsKey("enabled")) pushNotificationConfig.enabled = Utils.toBoolean(fields.enabled)
        if (fields.containsKey("poolInterrupted")) pushNotificationConfig.poolInterrupted = fields.poolInterrupted
        if (fields.containsKey("apiVersion")) pushNotificationConfig.apiVersion = Utils.toInteger(fields.apiVersion)
        if (fields.containsKey("accessToken") && pushNotificationConfig.getDecryptedAccessToken() != fields.accessToken) {
            if (fields.accessToken) {
                pushNotificationConfig.accessToken = crypterService.encryptDomainProperty(pushNotificationConfig, "accessToken", fields.accessToken, grailsApplication.config.asaas.crypter.webhook.accessToken.secret)
            } else {
                pushNotificationConfig.accessToken = null
            }
        }

        pushNotificationConfig.save(flush: true)

        if (pushNotificationConfig.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return pushNotificationConfig
        }

        if (fields.containsKey("poolInterrupted") && !fields.poolInterrupted) {
            pushNotificationConfigAlertQueueService.onPoolResumed(pushNotificationConfig)
        }

        if (fields.events) {
            List<PushNotificationRequestEvent> events = fields.events.collect { PushNotificationRequestEvent.convert(it) }
            pushNotificationConfigEventService.save(pushNotificationConfig, events)
        }

        if (pushNotificationConfig.enabled && !pushNotificationConfig.poolInterrupted) {
            cachePushNotificationConfigEventOnPoolResumed(pushNotificationConfig.id)
        } else {
            pushNotificationConfigWithPendingRequestCacheService.decreaseTtl(pushNotificationConfig.id)
        }

        pushNotificationConfigCacheService.evict(pushNotificationConfig.id)

        return pushNotificationConfig
    }

    public void delete(PushNotificationConfig config) {
        config.deleted = true
        config.save(failOnError: true)

        pushNotificationConfigEventService.delete(config.id)
        pushNotificationConfigCacheService.evict(config.id)
        pushNotificationConfigWithPendingRequestCacheService.decreaseTtl(config.id)
    }

    public void disableInactiveConfigs() {

        Map query = [:]
        query.column = "id"
        query.disableSort = true
        query.enabled = true
        query.poolInterrupted = true
        query.anyApplication = true
        query."lastUpdated[le]" = CustomDateUtils.sumDays(new Date(), -PushNotificationConfig.INACTIVE_DAYS)

        List<Long> inactiveConfigList = PushNotificationConfig.query(query).list(max: 200)
        if (!inactiveConfigList) return

        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(inactiveConfigList, flushEvery, { Long configId ->
            PushNotificationConfig config = PushNotificationConfig.get(configId)
            config.enabled = false
            config.save(failOnError: true)

            pushNotificationConfigCacheService.evict(configId)
        })
    }

    public void interruptPool(PushNotificationConfig pushNotificationConfig, Long pushNotificationRequestId) {
        if (pushNotificationConfig.poolInterrupted) return

        pushNotificationConfig.poolInterrupted = true
        pushNotificationConfig.save(flush: true, failOnError: true)

        pushNotificationConfigWithPendingRequestCacheService.decreaseTtl(pushNotificationConfig.id)
        pushNotificationConfigCacheService.evict(pushNotificationConfig.id)

        if (pushNotificationConfig.application?.isPluga()) {
            AsaasLogger.error("Sincronização das informações entre o ASAAS e Pluga foi interrompida. PushNotificationConfigId: [${pushNotificationConfig.id}].")
            return
        }

        PushNotificationRequestAttempt pushNotificationRequestAttempt = PushNotificationRequestAttempt.query([pushNotificationRequestId: pushNotificationRequestId, anyApplication: true]).get()
        pushNotificationConfigAlertQueueService.save(pushNotificationConfig, PushNotificationConfigAlertType.QUEUE_INTERRUPTED, pushNotificationRequestAttempt)
    }

    public void cachePushNotificationConfigEventOnPoolResumed(Long pushNotificationConfigId) {
        List<Map> configIdWithEventMapList = PushNotificationRequest.notSentGroupedByConfigIdAndEvent(["configId": pushNotificationConfigId]).list()

        for (Map configIdWithEventMap : configIdWithEventMapList) {
            pushNotificationConfigWithPendingRequestCacheService.save(configIdWithEventMap."config.id", configIdWithEventMap.event)
        }
    }

    public Boolean hasReachedMaxConfigPerCustomer(Customer customer) {
        return PushNotificationConfig.query([provider: customer, exists: true]).count() >= PushNotificationConfig.MAXIMUM_CONFIG_PER_CUSTOMER
    }

    private PushNotificationConfig validateSave(Customer customer, Map params) {
        PushNotificationConfig validateConfig = new PushNotificationConfig()
        Integer apiVersion = Utils.toInteger(params.apiVersion) ?: DEFAULT_API_VERSION

        BusinessValidation validatedCurrentUser = validateCurrentUser(UserUtils.getCurrentUser())
        if (!validatedCurrentUser.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedCurrentUser, validateConfig)
            return validateConfig
        }

        if (CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_PUSH_NOTIFICATION_CONFIG_USAGE)) {
            DomainUtils.addError(validateConfig, Utils.getMessageProperty("config.pushNotification.error.usage.disabled"))
            return validateConfig
        }

        if (hasReachedMaxConfigPerCustomer(customer)) {
            DomainUtils.addError(validateConfig, "A quantidade máxima de configurações de webhooks é de ${PushNotificationConfig.MAXIMUM_CONFIG_PER_CUSTOMER} por conta.")

            return validateConfig
        }

        BusinessValidation validatedUrl = validateUrl(params.url)
        if (!validatedUrl.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedUrl, validateConfig)
        }

        if (!params.type) {
            if (params.events) {
                BusinessValidation validatedEvents = validateEvents(params.events, apiVersion)
                if (!validatedEvents.isValid()) {
                    DomainUtils.copyAllErrorsFromBusinessValidation(validatedEvents, validateConfig)
                }

                if (validatedEvents.isValid() && validatedUrl.isValid()) {
                    BusinessValidation validatedSimilarConfig = validateSimilarConfig(params.events, params.url, apiVersion, customer.id, [:])
                    if (!validatedSimilarConfig.isValid()) {
                        DomainUtils.copyAllErrorsFromBusinessValidation(validatedSimilarConfig, validateConfig)
                    }
                }
            } else {
                DomainUtils.addError(validateConfig, "É necessário informar no mínimo um evento de notificação para essa configuração.")
            }

            if (!params.name) {
                DomainUtils.addError(validateConfig, "É necessário informar um nome para essa configuração.")
            }

            if (!params.containsKey("sendType")) {
                DomainUtils.addError(validateConfig, "É necessário informar um tipo de envio para essa configuração.")
            } else if (!params.sendType) {
                DomainUtils.addError(validateConfig, "A forma de envio informado é inválido.")
            }
        } else {
            BusinessValidation validatedApiVersion = validateApiVersion(params.type, apiVersion)
            if (!validatedApiVersion.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedApiVersion, validateConfig)
            }
        }

        BusinessValidation validatedAccessToken = validateAccessToken(params)
        if (!validatedAccessToken.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedAccessToken, validateConfig)
        }

        return validateConfig
    }

    private PushNotificationConfig validateUpdate(PushNotificationConfig pushNotificationConfig, Map fields) {
        PushNotificationConfig validatedConfig = new PushNotificationConfig()

        Integer apiVersion = fields.apiVersion ? Utils.toInteger(fields.apiVersion) : pushNotificationConfig.apiVersion

        BusinessValidation validatedCurrentUser = validateCurrentUser(UserUtils.getCurrentUser())
        if (!validatedCurrentUser.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedCurrentUser, validatedConfig)
            return validatedConfig
        }

        if (CustomerParameter.getValue(pushNotificationConfig.provider, CustomerParameterName.DISABLE_PUSH_NOTIFICATION_CONFIG_USAGE)) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("config.pushNotification.error.usage.disabled"))
            return validatedConfig
        }

        BusinessValidation validatedUrl = validateUrl(fields.url)
        if (!validatedUrl.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedUrl, validatedConfig)
        }

        if (fields.type) {
            BusinessValidation validatedApiVersion = validateApiVersion(fields.type, apiVersion)
            if (!validatedApiVersion.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedApiVersion, validatedConfig)
            }
        } else {
            if (fields.containsKey("sendType") && !fields.sendType) {
                DomainUtils.addError(validatedConfig, "A forma de envio informado é inválido.")
            }

            BusinessValidation validatedEvents = validateEvents(fields.events, apiVersion)
            if (!validatedEvents.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedEvents, validatedConfig)
            }

            Boolean receivedPropertiesToValidateSimilarConfig = fields.events || fields.url || fields.apiVersion
            if (receivedPropertiesToValidateSimilarConfig && validatedUrl.isValid() && validatedEvents.isValid()) {
                String url = fields.url ?: pushNotificationConfig.url
                List<PushNotificationConfigEvent> events
                if (fields.events) {
                    events = fields.events
                } else {
                    events = PushNotificationConfigEvent.query([configId: pushNotificationConfig.id, "column": "event"]).list()
                }

                BusinessValidation validatedSimilarConfig = validateSimilarConfig(events, url, apiVersion, pushNotificationConfig.provider.id, ["configId[ne]": pushNotificationConfig.id])
                if (!validatedSimilarConfig.isValid()) {
                    DomainUtils.copyAllErrorsFromBusinessValidation(validatedSimilarConfig, validatedConfig)
                }
            }
        }

        BusinessValidation validatedAccessToken = validateAccessToken(fields)
        if (!validatedAccessToken.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedAccessToken, validatedConfig)
        }

        return validatedConfig
    }

    private BusinessValidation validateSimilarConfig(List<String> events, String url, Integer apiVersion, Long customerId, Map search) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        List<PushNotificationRequestEvent> eventsList = events.collect { PushNotificationRequestEvent.convert(it) }
        Boolean hasSimilarConfig = PushNotificationConfigEvent.query(search + [exists: true, configProviderId: customerId, configUrl: url, configApiVersion: apiVersion, "configApplication[isNull]": true, "event[in]": eventsList]).get().asBoolean()
        if (hasSimilarConfig) {
            validatedBusiness.addError("config.pushNotification.error.event.similar")
        }

        return validatedBusiness
    }

    private BusinessValidation validateCurrentUser(User currentUser) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (AsaasEnvironment.isJobServer()) return validatedBusiness

        if (!currentUser || !modulePermissionService.allowed(currentUser, "admin")) {
            validatedBusiness.addError("default.notAllowed.message")
        }

        return validatedBusiness
    }

    private BusinessValidation validateAccessToken(Map fields) {
        final Integer maximumSizeOfAccessToken = 255

        BusinessValidation validatedBusiness = new BusinessValidation()

        if (fields.accessToken) {
            if (fields.accessToken.length() > maximumSizeOfAccessToken) {
                validatedBusiness.addError("config.pushNotification.error.accessToken.oversized", [maximumSizeOfAccessToken])
            } else if (ApiAuthUtils.isAuthToken(fields.accessToken)) {
                validatedBusiness.addError("config.pushNotification.error.accessToken.apiAuthToken")
            }
        }

        return validatedBusiness
    }

    private BusinessValidation validateEvents(List<String> events, Integer apiVersion) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        events.each { String event ->
            if (!PushNotificationRequestEvent.convert(event)) {
                validatedBusiness.addError("config.pushNotification.error.event.invalid", [event])
            }

            if (apiVersion && apiVersion < DEFAULT_API_VERSION) {
                List<Integer> availableApiVersionsForEvent = listAvailableApiVersionsForEvent(PushNotificationRequestEvent.valueOf(event))
                if (!availableApiVersionsForEvent.contains(apiVersion)) {
                    validatedBusiness.addError("config.pushNotification.error.event.apiVersion.invalid", [event, apiVersion])
                }
            }
        }

        return validatedBusiness
    }

    private BusinessValidation validateApiVersion(PushNotificationType type, Integer apiVersion) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        List<Integer> availableApiVersionsList = listAvailableApiVersionsForType(type)
        if (!availableApiVersionsList.contains(apiVersion)) {
            validatedBusiness.addError("config.pushNotification.error.apiVersion.invalid", [availableApiVersionsList.join(', ')])
        }

        return validatedBusiness
    }

    private BusinessValidation validateUrl(String url) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (url) {
            if (!isValidUrl(url)) {
                validatedBusiness.addError("config.pushNotification.error.url.invalid")
            }

            if (!isSupportedUrl(url)) {
                validatedBusiness.addError("config.pushNotification.error.url.notSupported")
            }
        }

        return validatedBusiness
    }

    private List<Integer> listAvailableApiVersionsForType(PushNotificationType type) {
        List<Integer> availableApiVersionsForType = []

        if (type == PushNotificationType.PAYMENT) {
            availableApiVersionsForType = PushNotificationConfig.availablePaymentApiVersions
        } else if (type == PushNotificationType.INVOICE) {
            availableApiVersionsForType = PushNotificationConfig.availableInvoiceApiVersions
        } else if (type == PushNotificationType.TRANSFER) {
            availableApiVersionsForType = PushNotificationConfig.availableTransferApiVersions
        } else if (type == PushNotificationType.BILL) {
            availableApiVersionsForType = PushNotificationConfig.availableBillApiVersions
        } else if (type == PushNotificationType.RECEIVABLE_ANTICIPATION) {
            availableApiVersionsForType = PushNotificationConfig.AVAILABLE_RECEIVABLE_ANTICIPATION_API_VERSIONS
        } else if (type == PushNotificationType.ACCOUNT_STATUS) {
            availableApiVersionsForType = PushNotificationConfig.AVAILABLE_ACCOUNT_STATUS_API_VERSIONS
        } else if (type == PushNotificationType.PIX_ADDRESS_KEY) {
            availableApiVersionsForType = PushNotificationConfig.AVAILABLE_PIX_ADDRESS_KEY_API_VERSIONS
        } else if (type == PushNotificationType.MOBILE_PHONE_RECHARGE) {
            availableApiVersionsForType = PushNotificationConfig.AVAILABLE_MOBILE_PHONE_RECHARGE_KEY_API_VERSIONS
        } else if (type == PushNotificationType.PIX) {
            availableApiVersionsForType = PushNotificationConfig.AVAILABLE_PIX_API_VERSIONS
        }

        return availableApiVersionsForType
    }

    private List<Integer> listAvailableApiVersionsForEvent(PushNotificationRequestEvent event) {
        if (event.isPayment()) return PushNotificationConfig.availablePaymentApiVersions
        if (event.isInvoice()) return PushNotificationConfig.availableInvoiceApiVersions
        if (event.isTransfer()) return PushNotificationConfig.availableTransferApiVersions
        if (event.isBill()) return PushNotificationConfig.availableBillApiVersions
        if (event.isReceivableAnticipation()) return PushNotificationConfig.AVAILABLE_RECEIVABLE_ANTICIPATION_API_VERSIONS
        if (event.isAccountStatus()) return PushNotificationConfig.AVAILABLE_ACCOUNT_STATUS_API_VERSIONS
        if (event.isMobilePhoneRecharge()) return PushNotificationConfig.AVAILABLE_MOBILE_PHONE_RECHARGE_KEY_API_VERSIONS
        if (event.isPix()) return PushNotificationConfig.AVAILABLE_PIX_API_VERSIONS

        return []
    }

    private Boolean isValidUrl(String url) {
        try {
            final List<String> bypassUrlList = [
                "https://nuvemshop.asaas.com/webhookAsaas",
                "https://money.asaas.com/webhook",
                "https://pix.app.shopify.asaas.com",
                "https://bankslip.app.shopify.asaas.com",
                "https://card.app.shopify.asaas.com"
            ]

            if (!UriUtils.isValidUri(url)) return false

            if (UriUtils.isAsaasDomain(url) && !(bypassUrlList.any { url.startsWith(it) })) {
                return false
            }

            return true
        } catch (Exception e) {
            AsaasLogger.error("PushNotificationConfig.isValidUrl >> Falha ao verificar se a url informado é válida: ${url}", e)
            return false
        }
    }

    private Boolean isSupportedUrl(String url) {
        if (url?.contains("ddns.net")) return false

        return true
    }
}
