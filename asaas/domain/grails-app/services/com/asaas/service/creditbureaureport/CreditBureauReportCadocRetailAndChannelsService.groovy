package com.asaas.service.creditbureaureport

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocOtherNonFinancialProducts
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class CreditBureauReportCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def sessionFactory
    def retailAndChannelsManagerService

    public Boolean generateDataForCreditBureauReportRequest() {
        Boolean sentWebData = sendCreditBureauReportInfo(EventOriginType.WEB)
        Boolean sentIosData = sendCreditBureauReportInfo(EventOriginType.MOBILE_IOS)
        Boolean sentAndroidData = sendCreditBureauReportInfo(EventOriginType.MOBILE_ANDROID)

        return sentWebData || sentIosData || sentAndroidData
    }

    private Boolean sendCreditBureauReportInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: CreditBureauReport.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity, coalesce(sum(this_.cost), 0) as sumValue ")
            .append(" from credit_bureau_report this_ ")
            .append(" inner join credit_bureau_report_origin_requester_info originRequester on this_.id = originRequester.credit_bureau_report_id  ")
            .append(" where this_.date_created >= :dateCreatedStart ")
            .append(" and this_.date_created <= :dateCreatedFinish ")
            .append(" and originRequester.event_origin = :eventOrigin ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setTimestamp("dateCreatedStart", baseDate.clearTime())
        query.setTimestamp("dateCreatedFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("eventOrigin", eventOrigin.toString())

        List resultQuery = query.uniqueResult()
        Map creditBureauReportRequestInfo = [
            sumQuantity: resultQuery.getAt(0),
            sumValue: resultQuery.getAt(1)
        ]

        Map cadocTransactionData = [
            product: CadocTransactionProduct.OTHERS_NON_FINANCIAL,
            accessChannel: CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            otherNonFinancialProducts: CadocOtherNonFinancialProducts.CREDIT_BUREAU_REPORT,
            sumValue: creditBureauReportRequestInfo.sumValue,
            sumQuantity: creditBureauReportRequestInfo.sumQuantity
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendCreditBureauReportInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de consulta serasa na data ${baseDate} com o evento de origem ${eventOrigin}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = CreditBureauReport.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
            fillerControl.eventOrigin = eventOrigin
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)

        return true
    }
}
