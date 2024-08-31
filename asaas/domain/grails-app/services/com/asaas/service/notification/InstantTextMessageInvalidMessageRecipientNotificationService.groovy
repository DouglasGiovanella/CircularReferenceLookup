package com.asaas.service.notification

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.domain.customer.Customer
import com.asaas.domain.notification.InstantTextMessage
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.instanttextmessage.enums.InstantTextMessageErrorReason
import com.asaas.log.AsaasLogger
import com.asaas.notification.InstantTextMessageStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class InstantTextMessageInvalidMessageRecipientNotificationService {

    def asyncActionService
    def customerAlertNotificationService
    def customerMessageService
    def dataSource

    public Boolean createAsyncActions() {
        if (!AsaasEnvironment.isProduction()) return false

        final Date yesterday = CustomDateUtils.getYesterday()
        final Integer maxBatchSize = 1000

        List<Long> customerIdList = InstantTextMessage.query([
            disableSort: true,
            distinct: "customer.id",
            errorReasonList: [InstantTextMessageErrorReason.INVALID_MESSAGE_RECIPIENT],
            "lastUpdated[ge]": yesterday.clearTime(),
            "lastUpdated[le]": CustomDateUtils.setTimeToEndOfDay(yesterday),
            status: InstantTextMessageStatus.FAILED
        ]).list(readOnly: true) as List<Long>

        for (List<Long> customerBatchIdList : customerIdList.collate(maxBatchSize)) {
            List<Map> asyncActionDataList = []

            asyncActionDataList.addAll(customerBatchIdList.collect { Long customerId ->
                String jsonActionData = AsyncActionDataBuilder.parseToJsonString([
                    customerId: customerId,
                    failDate: CustomDateUtils.formatDate(yesterday)
                ])

                return [
                    "action_data": jsonActionData,
                    "action_data_hash": AsyncActionDataBuilder.buildHash(jsonActionData),
                    "type": AsyncActionType.SEND_INSTANT_TEXT_MESSAGE_INVALID_MESSAGE_RECIPIENT_NOTIFICATION.toString(),
                    "status": AsyncActionStatus.PENDING.toString(),
                    "group_id": null,
                    "date_created": new Date(),
                    "last_updated": new Date(),
                    "deleted": 0,
                    "version": 0
                ]
            })

            DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "async_action", asyncActionDataList)
        }

        return false
    }

    public Boolean processAsyncActions() {
        if (!AsaasEnvironment.isProduction()) return false

        final Integer maxBatchSize = 400
        final Integer maxItemsPerThread = 100

        List<Map> asyncActionDataList = asyncActionService.listPendingSendInstantTextMessageInvalidMessageRecipientNotification(maxBatchSize)
        if (!asyncActionDataList) return false

        ThreadUtils.processWithThreadsOnDemand(asyncActionDataList, maxItemsPerThread, { List<Map> asyncActionBatchDataList ->
            for (Map asyncActionData : asyncActionBatchDataList) {
                try {
                    Utils.withNewTransactionAndRollbackOnError ({
                        Customer customer = Customer.read(asyncActionData.customerId as Long)
                        Date failDate = CustomDateUtils.toDate(asyncActionData.failDate)

                        customerMessageService.notifyCustomerAboutInstantTextMessageInvalidMessageRecipient(customer, failDate)
                        customerAlertNotificationService.notifyInstantTextMessageInvalidMessageRecipient(customer, failDate)

                        asyncActionService.delete(asyncActionData.asyncActionId as Long)
                    }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
                } catch (Exception exception) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId as Long)
                    AsaasLogger.error("${ this.class.getSimpleName() }.processAsyncActions >> Erro ao processar AsyncAction [${ asyncActionData.asyncActionId }]", exception)
                }
            }
        })

        return true
    }
}
