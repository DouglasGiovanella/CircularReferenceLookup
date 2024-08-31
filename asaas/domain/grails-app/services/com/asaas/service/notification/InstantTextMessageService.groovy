package com.asaas.service.notification

import com.asaas.domain.customer.Customer
import com.asaas.domain.notification.InstantTextMessage
import com.asaas.domain.notification.NotificationRequest
import com.asaas.integration.instanttextmessage.adapter.InstantTextMessageAdapter
import com.asaas.integration.instanttextmessage.enums.InstantTextMessageErrorReason
import com.asaas.log.AsaasLogger
import com.asaas.notification.InstantTextMessageStatus
import com.asaas.notification.InstantTextMessageType
import com.asaas.notification.NotificationPriority
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusVO
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class InstantTextMessageService {

    def chargedFeeService
    def grailsApplication
    def instantTextMessageSenderService
    def notificationRequestStatusService

    public InstantTextMessage save(String message, String toPhoneNumber, InstantTextMessageType type, NotificationRequest notificationRequest) {
        InstantTextMessage instantTextMessage = new InstantTextMessage()
        instantTextMessage.message = message
        instantTextMessage.fromPhoneNumber = chooseFromPhoneNumberByInstantMessageType(toPhoneNumber, type)
        instantTextMessage.toPhoneNumber = Utils.removeNonNumeric(toPhoneNumber)
        instantTextMessage.notificationRequest = notificationRequest
        instantTextMessage.customer = notificationRequest.customerAccount.provider
        instantTextMessage.type = type
        instantTextMessage.priority = notificationRequest.priority
        instantTextMessage.status = InstantTextMessageStatus.PENDING

        return instantTextMessage.save(failOnError: true)
    }

    public void processNotificationList() {
        final Date now = new Date()
        final Date whatsappWithoutHighPriorityStartingTime = CustomDateUtils.setTime(now, NotificationRequest.MOBILE_PHONE_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)

        Map queryParams = [:]
        queryParams.column = "id"
        queryParams.status = Status.PENDING
        queryParams.sortList = [[sort: "priority", order: "desc"], [sort: "id", order: "asc"]]
        if (now < whatsappWithoutHighPriorityStartingTime) queryParams."priority" = NotificationPriority.HIGH.priorityInt()

        List<Long> pendingInstantTextMessageIdList = InstantTextMessage.query(queryParams).list(max: 128)
        if (!pendingInstantTextMessageIdList) return

        final Integer numberOfThreads = 16

        List<NotificationRequestUpdateSendingStatusVO> updateSendingStatusVOList = Collections.synchronizedList(new ArrayList<NotificationRequestUpdateSendingStatusVO>())

        Utils.processWithThreads(pendingInstantTextMessageIdList, numberOfThreads, { List<Long> instantTextMessageIdList ->
            for (Long instantTextMessageId : instantTextMessageIdList) {
                try {
                    Utils.withNewTransactionAndRollbackOnError({
                        InstantTextMessage instantTextMessage = InstantTextMessage.get(instantTextMessageId)

                        InstantTextMessageErrorReason validationError = validateInstantTextMessage(instantTextMessage)
                        if (validationError) {
                            updateSendingStatusVOList.add(notificationRequestStatusService.buildFailedObject(instantTextMessage.notificationRequestId, "", false))

                            instantTextMessage.errorReason = validationError
                            instantTextMessage.status = InstantTextMessageStatus.FAILED
                            instantTextMessage.save(failOnError: true)
                            return
                        }

                        InstantTextMessageAdapter instantTextMessageAdapter = instantTextMessageSenderService.send(instantTextMessage)

                        instantTextMessage.status = instantTextMessageAdapter.status
                        instantTextMessage.externalIdentifier = instantTextMessageAdapter.id
                        instantTextMessage.errorReason = instantTextMessageAdapter.errorReason
                        instantTextMessage = instantTextMessage.save(failOnError: true)

                        if (instantTextMessageAdapter.success) {
                            Boolean shouldCreateTimelineEvent = instantTextMessage.status.isAlreadySent()
                            updateSendingStatusVOList.add(notificationRequestStatusService.buildSentObject(instantTextMessage.notificationRequestId, null, shouldCreateTimelineEvent))
                        } else {
                            updateSendingStatusVOList.add(notificationRequestStatusService.buildFailedObject(instantTextMessage.notificationRequestId, instantTextMessageAdapter.errorReason.getMessage(), true))

                            String errorMessage = instantTextMessageAdapter.errorMessage ?: instantTextMessageAdapter.errorReason.getMessage()
                            AsaasLogger.error("InstantTextMessageService.processNotification >> Processamento da InstantTextMessage [ID: ${ instantTextMessageId }] indicou falha no envio. Erro: ${ errorMessage }")
                        }

                        chargeInstantTextMessageIfNecessary(instantTextMessage)
                    }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
                } catch (Exception exception) {
                    AsaasLogger.error("InstantTextMessageService.processNotificationList >> Erro ao enviar a InstantTextMessage [ID: ${ instantTextMessageId }]", exception)

                    Utils.withNewTransactionAndRollbackOnError({
                        InstantTextMessage instantTextMessage = InstantTextMessage.get(instantTextMessageId)

                        updateSendingStatusVOList.add(notificationRequestStatusService.buildFailedObject(instantTextMessage.notificationRequestId, "", false))

                        instantTextMessage.status = InstantTextMessageStatus.FAILED
                        instantTextMessage.save(failOnError: true)
                    })
                }
            }
        })

        notificationRequestStatusService.saveAsyncUpdateSendingStatusList(updateSendingStatusVOList)
    }

    public void chargeInstantTextMessageIfNecessary(InstantTextMessage instantTextMessage) {
        if (!shouldChargeInstantTextMessage(instantTextMessage)) return

        chargeInstantTextMessageFee(instantTextMessage)
    }

    public void setAsRead(InstantTextMessage instantTextMessage) {
        instantTextMessage.status = InstantTextMessageStatus.READ
        instantTextMessage.save(failOnError: true)
    }

    public void setErrorReason(InstantTextMessage instantTextMessage, InstantTextMessageErrorReason instantTextMessageErrorReason) {
        instantTextMessage.errorReason = instantTextMessageErrorReason
        instantTextMessage.save(failOnError: true)
    }

    private boolean shouldChargeInstantTextMessage(InstantTextMessage instantTextMessage) {
        if (instantTextMessage.isAlreadyCharged()) return false

        if (InstantTextMessageStatus.unchargedStatusList().contains(instantTextMessage.status)) return false

        return true
    }

    private void chargeInstantTextMessageFee(InstantTextMessage instantTextMessage) {
        Customer customer = instantTextMessage.notificationRequest.customerAccount.provider
        chargedFeeService.saveInstantTextMessageFee(customer, instantTextMessage)
    }

    private InstantTextMessageErrorReason validateInstantTextMessage(InstantTextMessage instantTextMessage) {
        Customer customer = instantTextMessage.customer
        BigDecimal requiredBalance = InstantTextMessage.CUSTOMER_REQUIRED_BALANCE_TO_SEND_INSTANT_TEXT_MESSAGE

        if (customer.customerConfig.maxNegativeBalance) requiredBalance = customer.customerConfig.maxNegativeBalance * -1

        if (!customer.hasSufficientBalance(requiredBalance)) {
            return InstantTextMessageErrorReason.CUSTOMER_HAS_INSUFFICIENT_BALANCE
        }

        String phoneNumber = instantTextMessage.toPhoneNumber
        if (!PhoneNumberUtils.validateMobilePhone(phoneNumber)) {
            return InstantTextMessageErrorReason.INVALID_OR_INACTIVE_NUMBER
        }
    }

    private chooseFromPhoneNumberByInstantMessageType(String toPhoneNumber, InstantTextMessageType instantTextMessageType) {
        List<String> fromPhoneNumberOptionsList = []

        if (instantTextMessageType.isWhatsApp()) {
            fromPhoneNumberOptionsList = grailsApplication.config.twilio.whatsapp.from.phoneNumbers
        }

        return chooseFromPhoneNumber(toPhoneNumber, fromPhoneNumberOptionsList)
    }

    private String chooseFromPhoneNumber(String toPhoneNumber, List<String> fromPhoneNumberOptionsList) {
        if (!fromPhoneNumberOptionsList) throw new NotImplementedException("Os números de remetente de mensagens instantâneas não foram definidos.")

        if (fromPhoneNumberOptionsList.size() == 1) return fromPhoneNumberOptionsList[0]

        final Integer toPhoneNumberLastDigit = Utils.toInteger(toPhoneNumber[-1])
        Boolean chooseFirstFromNumberOption = toPhoneNumberLastDigit % 2

        return chooseFirstFromNumberOption ? fromPhoneNumberOptionsList[0] : fromPhoneNumberOptionsList[1]
    }
}
