package com.asaas.service.pushnotificationrequestasyncpreprocessing

import com.asaas.domain.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessing
import com.asaas.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessingStatus
import com.asaas.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessingType
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class PushNotificationRequestAsyncPreProcessingService {

    public void save(PushNotificationRequestAsyncPreProcessingType type, String groupId, Long customerId, Map dataMap) {
        PushNotificationRequestAsyncPreProcessing validatedAsyncPreProcessing = validateSave(dataMap)
        if (validatedAsyncPreProcessing.hasErrors()) {
            throw new ValidationException("Falha ao salvar processamento assíncrono do tipo [${type}] com os dados ${dataMap}", validatedAsyncPreProcessing.errors)
        }

        String dataJson = PushNotificationRequestAsyncPreProcessing.parseToDataJsonFormat(dataMap)

        PushNotificationRequestAsyncPreProcessing asyncPreProcessing = new PushNotificationRequestAsyncPreProcessing()
        asyncPreProcessing.type = type
        asyncPreProcessing.groupId = groupId
        asyncPreProcessing.customerId = customerId
        asyncPreProcessing.dataJson = dataJson
        asyncPreProcessing.save(failOnError: true)
    }

    public void delete(Long id) {
        PushNotificationRequestAsyncPreProcessing.executeUpdate("delete PushNotificationRequestAsyncPreProcessing where id = ?", [id])
    }

    public PushNotificationRequestAsyncPreProcessing sendToReprocessIfPossible(Long id) {
        PushNotificationRequestAsyncPreProcessing asyncPreProcessing = PushNotificationRequestAsyncPreProcessing.get(id)
        asyncPreProcessing.attempts = asyncPreProcessing.attempts + 1

        Boolean exceededMaxAttempts = asyncPreProcessing.attempts >= asyncPreProcessing.type.receiveMaxAttempts()
        if (exceededMaxAttempts) {
            return cancel(asyncPreProcessing)
        }

        return reprocess(asyncPreProcessing)
    }

    public PushNotificationRequestAsyncPreProcessing cancel(PushNotificationRequestAsyncPreProcessing asyncPreProcessing) {
        asyncPreProcessing.status = PushNotificationRequestAsyncPreProcessingStatus.CANCELLED

        return asyncPreProcessing.save(failOnError: true)
    }

    public PushNotificationRequestAsyncPreProcessing reprocess(PushNotificationRequestAsyncPreProcessing asyncPreProcessing) {
        asyncPreProcessing.status = PushNotificationRequestAsyncPreProcessingStatus.PENDING

        return asyncPreProcessing.save(failOnError: true)
    }

    private PushNotificationRequestAsyncPreProcessing validateSave(Map dataMap) {
        PushNotificationRequestAsyncPreProcessing validatedAsyncPreProcessing = new PushNotificationRequestAsyncPreProcessing()

        if (!dataMap) {
            DomainUtils.addError(validatedAsyncPreProcessing, "O parâmetro de processamento assíncrono não pode estar vazio")

            return validatedAsyncPreProcessing
        }

        return validatedAsyncPreProcessing
    }
}
