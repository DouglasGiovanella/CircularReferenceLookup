package com.asaas.service.integration.cerc

import com.asaas.domain.receivableregistration.ReceivableRegistrationEventQueue
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.integration.cerc.adapter.CercWebhookAdapter
import com.asaas.integration.cerc.adapter.contract.CercContractAdapter
import com.asaas.integration.cerc.adapter.receivableunit.ReceivableUnitAdapter
import com.asaas.integration.cerc.enums.webhook.CercEventType
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueStatus
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.commons.lang.NotImplementedException
import org.hibernate.StaleObjectStateException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException

@Transactional
class CercWebhookRequestService {

    def cercFidcContractualEffectService
    def cercNotificationService
    def receivableRegistrationEventQueueService
    def receivableUnitSynchronizationService
    def webhookRequestService

    private final Integer MAX_ITEMS_PER_CYCLE = 200

    public void processPendingItems() {
        List<Long> webhookRequestIdList = listPendingItems()
        for (Long webhookRequestId in webhookRequestIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
                process(webhookRequest)
                webhookRequestService.setAsProcessed(webhookRequest)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (exception instanceof HibernateOptimisticLockingFailureException
                        || exception instanceof CannotAcquireLockException
                        || exception instanceof StaleObjectStateException) {
                        AsaasLogger.warn("CercWebhookRequestService.processPendingItems >> Ocorreu uma exception tratada no webhook [${webhookRequestId}]")
                        return
                    }

                    AsaasLogger.error("CercWebhookRequestService.processPendingItems >> Falha ao processar item [${webhookRequestId}] do webhook da integração com a CERC", exception)
                    hasError = true
                }
            ])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
                    webhookRequestService.setAsError(webhookRequest)
                }, [logErrorMessage: "CercWebhookRequestService.processPendingItems >> Falha ao finalizar o processamento do webhook [${webhookRequestId}]"])
            }
        }
    }

    private List<Long> listPendingItems() {
        Map search = [:]
        search.column = "id"
        search.requestProvider = WebhookRequestProvider.CERC
        search.requestType = WebhookRequestType.CERC_NOTIFICATIONS
        search.sort = "id"
        search.order = "asc"

        return WebhookRequest.pending(search).list(max: MAX_ITEMS_PER_CYCLE)
    }

    private void process(WebhookRequest webhookRequest) {
        List<Map> itemList = []
        itemList.addAll(new JsonSlurper().parseText(webhookRequest.requestBody))

        final Integer numberOfThreads = 10

        Utils.processWithThreads(itemList, numberOfThreads, { List<Long> subItemList ->
            List<ReceivableRegistrationEventQueue> receivableRegistrationEventQueueToCreateList = []

            Utils.forEachWithFlushSession(subItemList, 50, { Map item ->
                if (item.containsKey("payLoad")) item = item.payLoad
                CercWebhookAdapter cercWebhookAdapter = new CercWebhookAdapter(item)

                if (cercWebhookAdapter.eventType.shouldBeIgnored()) return

                switch (cercWebhookAdapter.eventType) {
                    case CercEventType.CONTRACTUAL_EFFECT:
                        List<ReceivableRegistrationEventQueue> receivableRegistrationEventQueueToCreate = createContractualEffect(cercWebhookAdapter.event, webhookRequest.id)
                        if (receivableRegistrationEventQueueToCreate) receivableRegistrationEventQueueToCreateList.addAll(receivableRegistrationEventQueueToCreate)
                        break
                    case CercEventType.NOTIFICATION:
                        cercNotificationService.process(cercWebhookAdapter.event)
                        break
                    case CercEventType.CONTRACT:
                        cercFidcContractualEffectService.processAsyncResponse(new CercContractAdapter(cercWebhookAdapter.event), webhookRequest)
                        break
                    case CercEventType.RECEIVABLE_UNIT:
                        receivableUnitSynchronizationService.processAsyncResponse(new ReceivableUnitAdapter(cercWebhookAdapter.event), webhookRequest.id)
                        break
                    case CercEventType.SCHEDULE:
                        AsaasLogger.info("CercWebhookRequestService.process >> Webhook do tipo agenda foi recebido [${webhookRequest.id}]")
                        break
                    default:
                        throw new NotImplementedException("CercWebhookRequestService.process >> Falha ao processar webhook [${webhookRequest.id}]. Tipo [${cercWebhookAdapter.eventType}] não implementado")
                }
            })

            try {
                receivableRegistrationEventQueueService.saveInBatch(receivableRegistrationEventQueueToCreateList)
            } catch (Exception exception) {
                AsaasLogger.error("CercWebhookRequestService.process >> Falha ao criar receivable registration event queue do webhook [${webhookRequest.id}]", exception)
                throw exception
            }
        })
    }

    private List<ReceivableRegistrationEventQueue> createContractualEffect(Map event, Long webhookRequestId) {
        Map eventInfo = [:]
        eventInfo.customerCpfCnpj = Utils.removeNonNumeric(event.documentoUsuarioFinalRecebedor)
        eventInfo.holderCpfCnpj = Utils.removeNonNumeric(event.documentoTitular)
        eventInfo.paymentArrangement = PaymentArrangement.valueOf(event.codigoArranjoPagamento)
        eventInfo.estimatedCreditDate = CustomDateUtils.fromStringDatabaseDateFormat(event.dataLiquidacao)

        String groupId = eventInfo.encodeAsMD5()
        List<ReceivableRegistrationEventQueue> eventQueueList = []

        try {
            for (Map contractualEffect : event.efeitosContrato) {
                eventInfo.contractualEffect = contractualEffect

                String eventDataJson = JsonOutput?.toJson(eventInfo)
                String eventDataHash = eventDataJson?.encodeAsMD5()

                Boolean hasEventPendingWithSameGroupIdAndHash = ReceivableRegistrationEventQueue.query([
                    exists: true,
                    groupId: groupId,
                    eventDataHash: eventDataHash,
                    status: ReceivableRegistrationEventQueueStatus.PENDING,
                    type: ReceivableRegistrationEventQueueType.CREATE_OR_UPDATE_CONTRACTUAL_EFFECT
                ]).get().asBoolean()
                if (hasEventPendingWithSameGroupIdAndHash) return

                ReceivableRegistrationEventQueue receivableRegistrationEventQueue = receivableRegistrationEventQueueService.buildObject(ReceivableRegistrationEventQueueType.CREATE_OR_UPDATE_CONTRACTUAL_EFFECT, eventInfo, groupId)
                eventQueueList.add(receivableRegistrationEventQueue)
            }

            return eventQueueList
        } catch (Exception exception) {
            AsaasLogger.error("CercWebhookRequestService.createContractualEffect >> Falha ao criar evento para criação ou atualização de efeitos de contrato [Webhook: ${webhookRequestId}\nEfeito: ${JsonOutput.toJson(eventInfo)}]", exception)
        }
    }
}
