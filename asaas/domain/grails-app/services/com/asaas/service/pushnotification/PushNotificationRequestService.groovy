package com.asaas.service.pushnotification

import com.asaas.domain.customer.Customer
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigAlertQueue
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationConfigAlertType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestService {

    private static final Integer SEND_ATTEMPT_ERROR_ALERT_INTERVAL = 5

    def pushNotificationConfigService
    def pushNotificationConfigWithPendingRequestCacheService
    def pushNotificationConfigAlertQueueService

    public PushNotificationRequest save(PushNotificationConfig pushNotificationConfig, PushNotificationRequestEvent event) {
        PushNotificationRequest pushNotificationRequest = new PushNotificationRequest()
        pushNotificationRequest.event = event
        pushNotificationRequest.config = pushNotificationConfig
        pushNotificationRequest.provider = pushNotificationConfig.provider
        pushNotificationRequest.save(failOnError: true)

        cacheRequestIfNecessary(pushNotificationRequest)

        return pushNotificationRequest
    }

    public void setAsSent(Long pushNotificationRequestId, String data, String sentToUrl) {
        PushNotificationRequest pushNotificationRequest = PushNotificationRequest.query([id: pushNotificationRequestId, includeDeleted: true]).get()

        pushNotificationRequest.sentData = data
        pushNotificationRequest.sentDate = new Date()
        pushNotificationRequest.sentToUrl = sentToUrl
        pushNotificationRequest.attemptsToSend += 1
        pushNotificationRequest.sent = true

        pushNotificationRequest.save(failOnError: true)
    }

    public void setAsFailed(Long pushNotificationRequestId, String data, String sentToUrl) {
        PushNotificationRequest pushNotificationRequest = PushNotificationRequest.query([id: pushNotificationRequestId, includeDeleted: true]).get()
        PushNotificationConfig pushNotificationConfig = pushNotificationRequest.config

        pushNotificationRequest.sentData = data
        pushNotificationRequest.sentToUrl = sentToUrl
        pushNotificationRequest.attemptsToSend += 1
        pushNotificationRequest.nextExecutionDate = calculateNextExecutionDate(pushNotificationConfig, pushNotificationRequest.attemptsToSend)
        pushNotificationRequest.save(failOnError: true)

        Boolean maxAttemptsReached = validateIfMaxAttemptsReached(pushNotificationRequest)
        if (maxAttemptsReached) {
            if (pushNotificationConfig.sendType.isNonSequentially()) {
                pushNotificationConfig.refresh()
                pushNotificationConfig.lock()
            }

            pushNotificationConfigService.interruptPool(pushNotificationConfig, pushNotificationRequestId)
            return
        }

        Boolean canSendAttemptFailedAlert = canSendAttemptFailedAlert(pushNotificationRequest)
        if (canSendAttemptFailedAlert) {
            if (pushNotificationConfig.application?.isPluga()) {
                AsaasLogger.error("Erro ao sincronizar informações entre o ASAAS e Pluga. PushNotificationConfigId: [${pushNotificationConfig.id}].")
                return
            }

            PushNotificationRequestAttempt pushNotificationRequestAttempt = PushNotificationRequestAttempt.query([pushNotificationRequestId: pushNotificationRequestId, anyApplication: true, order: "desc"]).get()
            pushNotificationConfigAlertQueueService.save(pushNotificationConfig, PushNotificationConfigAlertType.ATTEMPT_FAIL, pushNotificationRequestAttempt)
        }
    }

    public void setAsNotSent(Long pushNotificationRequestId, Long customerId) {
        PushNotificationRequest pushNotificationRequest = PushNotificationRequest.find(pushNotificationRequestId, customerId)

        BusinessValidation canBeResentValidation = pushNotificationRequest.canResend()
        if (!canBeResentValidation.isValid()) throw new BusinessException(canBeResentValidation.getFirstErrorMessage())

        pushNotificationRequest.sent = false
        pushNotificationRequest.deleted = false
        pushNotificationRequest.save(failOnError: true)

        cacheRequestIfNecessary(pushNotificationRequest)
    }

    public void delete(Long pushNotificationRequestId, Long customerId) {
        PushNotificationRequest pushNotificationRequest = PushNotificationRequest.find(pushNotificationRequestId, customerId)

        BusinessValidation canBeDeletedValidation = pushNotificationRequest.canDelete()
        if (!canBeDeletedValidation.isValid()) {
            throw new BusinessException(canBeDeletedValidation.getFirstErrorMessage())
        }

        pushNotificationRequest.deleted = true
        pushNotificationRequest.save(failOnError: true)
    }

    public void restore(Long pushNotificationRequestId, Long customerId) {
        PushNotificationRequest pushNotificationRequest = PushNotificationRequest.find(pushNotificationRequestId, customerId)

        BusinessValidation canBeRestoreValidation = pushNotificationRequest.canRestore()
        if (!canBeRestoreValidation.isValid()) {
            throw new BusinessException(canBeRestoreValidation.getFirstErrorMessage())
        }

        pushNotificationRequest.deleted = false
        pushNotificationRequest.save(failOnError: true)

        cacheRequestIfNecessary(pushNotificationRequest)
    }

    public Map sanitizeData(Map dataHash, Integer level) {
        final List<String> secretKeys = ["agency", "agencyDigit", "account", "accountDigit"]
        final Integer maxRecursivityLevel = 5

        for (Map.Entry entry : dataHash) {
            Boolean isASecretKey = secretKeys.any { it.toLowerCase().equals(entry.key.toLowerCase()) }
            def entryValue = dataHash[entry.key]

            if (isASecretKey || isCpf(entry, entryValue) || level > maxRecursivityLevel) {
                dataHash[entry.key] = "********"
                continue
            }

            if (entryValue instanceof Map) {
                dataHash[entry.key] = sanitizeData(entryValue, level + 1)
            } else {
                dataHash[entry.key] = entryValue
            }
        }

        return dataHash
    }

    private Date calculateNextExecutionDate(PushNotificationConfig pushNotificationConfig, Integer attempts) {
        boolean isPluga = pushNotificationConfig.application?.isPluga()
        boolean isSequentially = pushNotificationConfig.sendType.isSequentially()

        if (isSequentially && !isPluga) return null

        int remainingAttempts = isSequentially
            ? attempts % PushNotificationRequest.MAX_SEQUENTIAL_SEND_ATTEMPTS
            : attempts % PushNotificationRequest.MAX_NON_SEQUENTIAL_SEND_ATTEMPTS

        if (remainingAttempts <= 3) return new Date()
        if (remainingAttempts <= 5) return CustomDateUtils.sumSeconds(new Date(), 30)
        if (remainingAttempts <= 10) return CustomDateUtils.sumMinutes(new Date(), 1)

        return CustomDateUtils.sumMinutes(new Date(), 2)
    }

    public void delete(Customer customer, Map params) {
        Long customerId = buildCustomerIdFromParams(customer, params)
        delete(Utils.toLong(params.id), customerId)
    }

    public void updateAsNotSent(Customer customer, Map params) {
        Long customerId = buildCustomerIdFromParams(customer, params)
        setAsNotSent(Utils.toLong(params.id), customerId)
    }

    private Long buildCustomerIdFromParams(Customer customer, Map params) {
        if (!params.childAccountPublicId?.trim()) return customer.id

        Long childAccountId = Customer.childAccounts(customer, [column: "id", publicId: params.childAccountPublicId]).get()
        if (!childAccountId) throw new BusinessException("Id público da subconta informada não pertence ao seu arranjo de subcontas.")

        return childAccountId
    }

    private Boolean validateIfMaxAttemptsReached(PushNotificationRequest pushNotificationRequest) {
        if (pushNotificationRequest.config.sendType.isSequentially()) {
            return pushNotificationRequest.attemptsToSend % PushNotificationRequest.MAX_SEQUENTIAL_SEND_ATTEMPTS == 0
        }

        if (pushNotificationRequest.config.sendType.isNonSequentially()) {
            return pushNotificationRequest.attemptsToSend % PushNotificationRequest.MAX_NON_SEQUENTIAL_SEND_ATTEMPTS == 0
        }

        AsaasLogger.warn("PushNotificationRequestService.validateIfMaxAttemptsReached >>> Tipo de envio não tratado! ${pushNotificationRequest.config.sendType}")
        return false
    }

    private Boolean canSendAttemptFailedAlert(PushNotificationRequest pushNotificationRequest) {
        if (pushNotificationRequest.attemptsToSend % SEND_ATTEMPT_ERROR_ALERT_INTERVAL != 0) return false

        if (pushNotificationRequest.config.sendType.isNonSequentially()) {
            Integer lastReceivedStatus = PushNotificationRequestAttempt.query([column: "receivedStatus", pushNotificationRequestId: pushNotificationRequest.id, anyApplication: true, order: "desc"]).get()
            Map query = [
                pushNotificationConfig: pushNotificationRequest.config,
                exists: true,
                type: PushNotificationConfigAlertType.ATTEMPT_FAIL,
                "dateCreated[ge]": CustomDateUtils.sumMinutes(new Date(), -10),
                "pushNotificationAttemptReceivedStatus": lastReceivedStatus
            ]

            Boolean wasAlertAlreadyGenerated = PushNotificationConfigAlertQueue.query(query).get().asBoolean()
            return !wasAlertAlreadyGenerated
        }

        return true
    }

    private void cacheRequestIfNecessary(PushNotificationRequest pushNotificationRequest) {
        if (!pushNotificationRequest.config.enabled) return
        if (pushNotificationRequest.config.poolInterrupted) return

        pushNotificationConfigWithPendingRequestCacheService.save(pushNotificationRequest.configId, pushNotificationRequest.event)
    }

    private Boolean isCpf(Map.Entry entry, Object value) {
        final List<String> cpfKeys = ["cpf", "cpfcnpj", "pixaddresskey"]
        Boolean isACpfKey = cpfKeys.any { it.toLowerCase().equals(entry.key.toLowerCase()) }

        if (!isACpfKey) return false
        if (value == null || value.toString().isEmpty()) return false

        String validatedCpf = CpfCnpjUtils.buildCpf(value.toString())
        return validatedCpf.asBoolean()
    }
}
