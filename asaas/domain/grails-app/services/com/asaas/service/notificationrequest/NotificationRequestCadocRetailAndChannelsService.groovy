package com.asaas.service.notificationrequest

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocOtherNonFinancialProducts
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.notification.NotificationRequest
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationStatus
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class NotificationRequestCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def sessionFactory
    def retailAndChannelsManagerService

    public Boolean generateDataForNotificationRequest() {
        Boolean sentWebData = sendNotificationRequestInfo(EventOriginType.WEB)
        Boolean sentIosData = sendNotificationRequestInfo(EventOriginType.MOBILE_IOS)
        Boolean sentAndroidData = sendNotificationRequestInfo(EventOriginType.MOBILE_ANDROID)

        return sentWebData || sentIosData || sentAndroidData
    }

    private Boolean sendNotificationRequestInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: NotificationRequest.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity ")
            .append(" from notification_request this_ ")
            .append(" inner join payment_origin_requester_info originRequester on originRequester.payment_id = this_.payment_id  ")
            .append(" where this_.last_updated >= :lastUpdatedStart ")
            .append(" and this_.last_updated <= :lastUpdatedFinish ")
            .append(" and this_.status = :status ")
            .append(" and this_.payment_id is not null ")
            .append(" and this_.notification_id is not null ")
            .append(" and this_.deleted = false ")
            .append(" and originRequester.event_origin = :eventOrigin ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setTimestamp("lastUpdatedStart", baseDate.clearTime())
        query.setTimestamp("lastUpdatedFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("status", NotificationStatus.SENT.toString())
        query.setString("eventOrigin", eventOrigin.toString())

        Long notificationCount = query.uniqueResult()

        Map cadocTransactionData = [
            product: CadocTransactionProduct.OTHERS_NON_FINANCIAL,
            accessChannel: CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            otherNonFinancialProducts: CadocOtherNonFinancialProducts.NOTIFICATION,
            sumQuantity: notificationCount
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendNotificationRequestInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de Notificação na data ${baseDate} com o evento de origem ${eventOrigin}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = NotificationRequest.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
            fillerControl.eventOrigin = eventOrigin
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
        return true
    }
}
