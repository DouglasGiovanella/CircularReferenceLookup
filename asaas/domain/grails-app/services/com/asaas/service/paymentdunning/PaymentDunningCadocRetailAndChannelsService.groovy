package com.asaas.service.paymentdunning

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocOtherNonFinancialProducts
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.paymentdunning.PaymentDunningStatusHistory
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.payment.PaymentDunningStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class PaymentDunningCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def sessionFactory
    def retailAndChannelsManagerService

    public Boolean generateDataForPaymentDunningRequest() {
        Boolean sentWebData = sendPaymentDunningStatusHistoryInfo(EventOriginType.WEB)
        Boolean sentIosData = sendPaymentDunningStatusHistoryInfo(EventOriginType.MOBILE_IOS)
        Boolean sentAndroidData = sendPaymentDunningStatusHistoryInfo(EventOriginType.MOBILE_ANDROID)

        return sentWebData || sentIosData || sentAndroidData
    }

    private Boolean sendPaymentDunningStatusHistoryInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: PaymentDunningStatusHistory.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity ")
            .append(" from payment_dunning_status_history this_ ")
            .append(" inner join payment_dunning_origin_requester_info originRequester on (originRequester.payment_dunning_id = this_.payment_dunning_id) ")
            .append(" where this_.date_created  >= :dateCreatedStart ")
            .append(" and this_.date_created <= :dateCreatedFinish ")
            .append(" and this_.status = :status ")
            .append(" and originRequester.event_origin = :eventOrigin ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setTimestamp("dateCreatedStart", baseDate.clearTime())
        query.setTimestamp("dateCreatedFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("status", PaymentDunningStatus.PROCESSED.toString())
        query.setString("eventOrigin", eventOrigin.toString())

        Long paymentDunningCount = query.uniqueResult()

        Map cadocTransactionData = [
            product: CadocTransactionProduct.OTHERS_NON_FINANCIAL,
            accessChannel: CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            otherNonFinancialProducts: CadocOtherNonFinancialProducts.PAYMENT_DUNNING,
            sumQuantity: paymentDunningCount
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendPaymentDunningStatusHistoryInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de negativação na data ${baseDate} com o evento de origem ${eventOrigin}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = PaymentDunningStatusHistory.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
            fillerControl.eventOrigin = eventOrigin
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)

        return true
    }
}
