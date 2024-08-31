package com.asaas.service.pushnotification.event

import com.asaas.api.ApiBaseParser
import com.asaas.api.pix.ApiPixQrCodeParser
import com.asaas.api.pix.ApiPixTransactionParser
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequestPixEvent
import com.asaas.featureflag.FeatureFlagName
import com.asaas.http.HttpRequestManager
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.qrcode.PixQrCodeTransactionAdapter
import com.asaas.pushnotification.PushNotificationParserRegistry
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.pushnotification.worker.pix.PixEventPushNotificationWorkerConfigVO
import com.asaas.pushnotification.worker.pix.PixEventPushNotificationWorkerItemVO
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import groovy.json.JsonSlurper
import org.hibernate.SQLQuery
import org.springframework.util.StopWatch

@Transactional
class PushNotificationRequestPixEventService {

    def featureFlagCacheService
    def pushNotificationRequestProcessService
    def sessionFactory

    public List<PixEventPushNotificationWorkerItemVO> listPixEventPushNotificationIdToBeProcessed(PixEventPushNotificationWorkerConfigVO pixEventWorkerConfig, List<Long> pixEventPushNotificationIdListProcessing, Integer maxQueryItems) {
        Boolean pixPushNotificationQueueListWithForceIndexEnabled = featureFlagCacheService.isEnabled(FeatureFlagName.PIX_PUSH_NOTIFICATION_QUEUE_LIST_WITH_FORCE_INDEX)
        List<Long> pixEventPushNotificationList
        if (pixPushNotificationQueueListWithForceIndexEnabled) {
            pixEventPushNotificationList = listPendingEventsWithForceIndex(pixEventPushNotificationIdListProcessing, maxQueryItems)
        } else {
            Map search = [column: "id", disableSort: true]

            if (pixEventPushNotificationIdListProcessing) {
                search."id[notIn]" = pixEventPushNotificationIdListProcessing
            }

            pixEventPushNotificationList = PushNotificationRequestPixEvent.availableToSend(search).list(max: maxQueryItems)
        }

        List<PixEventPushNotificationWorkerItemVO> itemList = []
        pixEventPushNotificationList.collate(pixEventWorkerConfig.maxItemsPerThread).each { itemList.add(new PixEventPushNotificationWorkerItemVO(it)) }

        return itemList
    }

    public void consumeQueue(List<Long> pixEventPushNotificationList) {
        if (!pixEventPushNotificationList) return

        for (Long eventId : pixEventPushNotificationList) {
            Utils.withNewTransactionAndRollbackOnError({
                processEvent(eventId)
            }, [logErrorMessage: "PushNotificationRequestPixEventService.consumeQueue >> Falha ao processar evento via worker ${eventId}"])
        }
    }

    public void processEvent(Long eventId) {
        StopWatch stopWatch = new StopWatch()

        PushNotificationRequestPixEvent pixEvent = PushNotificationRequestPixEvent.get(eventId)
        PushNotificationConfig config = pixEvent.config
        Map postResponse = null
        try {
            if (!pixEvent.firstAttemptDate) pixEvent.firstAttemptDate = new Date()

            String destinationUrl = pushNotificationRequestProcessService.ensureHasProtocol(config.url)

            Map data = [
                id: "evt_${pixEvent.event.encodeAsMD5()}&${eventId}",
                event: pixEvent.event,
                dateCreated: ApiBaseParser.formatDateWithTime(pixEvent.dateCreated, config.apiVersion)
            ]

            Map pixEventMap = new JsonSlurper().parseText(pixEvent.payload)
            if (shouldSendDeprecatedDataFormat(pixEvent)) {
                data.data = pixEventMap
            } else {
                data.pix = pixEventMap.data
            }

            String payload = pushNotificationRequestProcessService.convertToJson(config.apiVersion, data)

            stopWatch.start()
            postResponse = doPost(destinationUrl, payload, config.getDecryptedAccessToken())
            stopWatch.stop()

            if (postResponse.status == 200) {
                pixEvent.sent = true
            }
        } catch (Throwable throwable) {
            AsaasLogger.error("PushNotificationRequestPixEventService.processEvent >> Falha ao processar. Id ${eventId}", throwable)
        } finally {
            pixEvent.attempts++
            pixEvent.responseData = postResponse.receivedData?.take(PushNotificationRequestPixEvent.constraints.responseData.getMaxSize())
            pixEvent.responseStatus = postResponse.status
            pixEvent.responseTime = stopWatch.totalTimeMillis
            if (!pixEvent.sent) pixEvent.nextExecutionDate = calculateNextExecutionDate(pixEvent.attempts)

            pixEvent.save(failOnError: true)
        }
    }

    public void saveQrCodeTransaction(Long customerId, PixQrCodeTransactionAdapter qrCodeTransaction, PushNotificationRequestEvent event) {
        Boolean allowUnmaskedCpfCnpj = CustomerParameter.getValue(customerId, CustomerParameterName.ALLOW_UNMASKED_CPF_CNPJ_ON_API)
        String identifier = event.toString() + "_" + qrCodeTransaction.endToEndIdentifier

        save(customerId, identifier, event, { Integer apiVersion ->
            Map data = [id: qrCodeTransaction.conciliationIdentifier]
            data << ApiPixQrCodeParser.buildTransactionResponseItem(qrCodeTransaction, [apiVersion: apiVersion, allowUnmaskedCpfCnpj: allowUnmaskedCpfCnpj])

            return data
        })
    }

    public void saveTransaction(PixTransaction pixTransaction, PushNotificationRequestEvent event) {
        String identifier = event.toString() + "_" + pixTransaction.endToEndIdentifier

        save(pixTransaction.customerId, identifier, event, { Integer apiVersion ->
            Boolean allowUnmaskedCpfCnpj = CustomerParameter.getValue(pixTransaction.customerId, CustomerParameterName.ALLOW_UNMASKED_CPF_CNPJ_ON_API)

            return ApiPixTransactionParser.buildResponseItem(pixTransaction, [apiVersion: apiVersion, allowUnmaskedCpfCnpj: allowUnmaskedCpfCnpj])
        })
    }

    private void save(Long customerId, String identifier, PushNotificationRequestEvent event, Closure buildData) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(customerId, event, [:]).list()

        try {
            for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
                Map requestData = [event: event]
                requestData.data = buildData(pushNotificationConfig.apiVersion)

                String payload = pushNotificationRequestProcessService.convertToJson(pushNotificationConfig.apiVersion, requestData)

                PushNotificationRequestPixEvent pixEvent = new PushNotificationRequestPixEvent()
                pixEvent.config = pushNotificationConfig
                pixEvent.event = event
                pixEvent.payload = payload
                pixEvent.identifier = "${identifier}_${pushNotificationConfig.id}"
                pixEvent.save(failOnError: true)
            }
        } catch (ValidationException validationException) {
            Boolean isDuplicatedEvent = DomainUtils.hasErrorCode(validationException, "unique.com.asaas.domain.pushnotification.PushNotificationRequestPixEvent.identifier")
            if (!isDuplicatedEvent) {
                throw new ValidationException("Falha ao persistir evento de webhook Pix customerId: ${customerId}", validationException.errors)
            } else {
                AsaasLogger.warn("PushNotificationRequestPixEventService.save >>> Evento já recebido. customerId: ${customerId} identifier: ${identifier}")
            }
        }
    }

    private Map doPost(String url, String data, String accessToken) {
        Map headersMap = [
            "asaas-access-token": accessToken,
            "Content-Type": "application/json",
            "Accept": "application/json"
        ]

        HttpRequestManager requestManager = new HttpRequestManager(url, headersMap, data, 5000)
        requestManager.setParserRegistry(new PushNotificationParserRegistry())
        requestManager.notWarnNonSuccessErrors()
        requestManager.ignoreSslIssues()
        requestManager.post()

        return [
            status: requestManager.responseHttpStatus,
            receivedData: requestManager.responseBody
        ]
    }

    private Date calculateNextExecutionDate(Integer attempts) {
        if (attempts <= 5) return new Date()
        if (attempts <= 15) return CustomDateUtils.sumMinutes(new Date(), 5)
        if (attempts <= 25) return CustomDateUtils.sumMinutes(new Date(), 10)
        if (attempts <= 35) return CustomDateUtils.sumMinutes(new Date(), 20)

        return CustomDateUtils.sumMinutes(new Date(), 30)
    }

    private Boolean shouldSendDeprecatedDataFormat(PushNotificationRequestPixEvent pixEvent) {
        final List<Long> deprecatedDataFormatCustomerIdList = [2375888L, 3306741L, 3525089L]

        if (deprecatedDataFormatCustomerIdList.contains(pixEvent.config.providerId)) return true
        if (pixEvent.config.provider.accountOwnerId && deprecatedDataFormatCustomerIdList.contains(pixEvent.config.provider.accountOwnerId)) return true

        return false
    }

    private List<Long> listPendingEventsWithForceIndex(List<Long> pixEventPushNotificationIdListProcessing, Integer maxQueryItems) {
        StringBuilder sqlQueryBuilder = new StringBuilder(" " +
            " SELECT pnrpe.id " +
            "  FROM push_notification_request_pix_event pnrpe FORCE INDEX (idx_pnr_pix_event_deleted_sent_next_execution_date) " +
            " JOIN push_notification_config pnc ON pnrpe.config_id = pnc.id " +
            " WHERE pnrpe.deleted = false " +
            "  AND pnrpe.sent = false " +
            "  AND pnc.enabled = true " +
            "  AND pnc.pool_interrupted = false " +
            "  AND (pnrpe.next_execution_date IS NULL OR pnrpe.next_execution_date <= :nextExecutionDate) ")

        if (pixEventPushNotificationIdListProcessing) {
            sqlQueryBuilder.append(" AND pnrpe.id NOT IN (:idList)")
        }

        sqlQueryBuilder.append(" LIMIT :limit")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sqlQueryBuilder.toString())
        query.setTimestamp("nextExecutionDate", new Date())
        query.setInteger("limit", maxQueryItems)

        if (pixEventPushNotificationIdListProcessing) {
            query.setParameterList("idList", pixEventPushNotificationIdListProcessing)
        }

        return query.list().collect { Utils.toLong(it) }
    }
}
