package com.asaas.service.bill

import com.asaas.bill.BillStatus
import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.domain.bank.Bank
import com.asaas.domain.bill.Bill
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class BillCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def sessionFactory
    def retailAndChannelsManagerService

    public Boolean generateDataForBillRequest() {
        Boolean sentWebData = sendPaidBillRequestInfo(EventOriginType.WEB)
        Boolean sentIosData = sendPaidBillRequestInfo(EventOriginType.MOBILE_IOS)
        Boolean sentAndroidData = sendPaidBillRequestInfo(EventOriginType.MOBILE_ANDROID)

        return sentWebData || sentIosData || sentAndroidData
    }

    public Boolean sendInternalBillInfo() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: Bill.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.INTERNAL_OPERATION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        Map billInfoMap = Bill.createCriteria().get() {
            createAlias("bank", "bank")
            ge("paymentDate", baseDate.clearTime())
            le("paymentDate", CustomDateUtils.setTimeToEndOfDay(baseDate))
            eq("bank.code", Bank.ASAAS_BANK_CODE)
            'in'("status", [BillStatus.PAID, BillStatus.REFUNDED])
            eq("deleted", false)

            projections {
                resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
                sqlProjection("count(this_.id) as sumQuantity", ["sumQuantity"], [INTEGER])
                sqlProjection("coalesce(sum(this_.value), 0) as sumValue", ["sumValue"], [BIG_DECIMAL])
            }
        }

        Map cadocInternalOperationData = [
            operationType: CadocInternalOperationType.INTERNAL_BOLETO,
            sumTransactionsValue: billInfoMap.sumValue,
            transactionsCount: billInfoMap.sumQuantity,
            product: CadocInternalOperationProduct.INTERNAL_BILL
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)
        retailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendInternalBillInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de pague contas (Boleto Interno) na data ${baseDate}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = Bill.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.INTERNAL_OPERATION
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
        return true
    }

    private Boolean sendPaidBillRequestInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: Bill.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity, coalesce(sum(this_.value), 0) as sumValue ")
            .append(" from bill this_ ")
            .append(" inner join bill_origin_requester_info originRequester on this_.id = originRequester.bill_id  ")
            .append(" left join bank on this_.bank_id = bank.id  ")
            .append(" where this_.status in (:statusList) ")
            .append(" and this_.payment_date >= :paymentDateStart ")
            .append(" and this_.payment_date <= :paymentDateFinish ")
            .append(" and originRequester.event_origin = :eventOrigin ")
            .append(" and if(this_.bank_id is not null, bank.code <> :bankCode, true )")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setParameterList("statusList", [BillStatus.PAID.toString(), BillStatus.REFUNDED.toString()])
        query.setTimestamp("paymentDateStart", baseDate.clearTime())
        query.setTimestamp("paymentDateFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("eventOrigin", eventOrigin.toString())
        query.setString("bankCode", Bank.ASAAS_BANK_CODE)

        List resultQuery = query.uniqueResult()
        Map billRequestInfoMap = [
            sumQuantity: resultQuery.getAt(0),
            sumValue: resultQuery.getAt(1)
        ]

        Map cadocTransactionData = [
            product: CadocTransactionProduct.PAYMENT_BOLETO_OR_AGREEMENT,
            accessChannel: CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            sumValue: billRequestInfoMap.sumValue,
            sumQuantity: billRequestInfoMap.sumQuantity
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendBillRequestInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de pague contas na data ${baseDate} com o evento de origem ${eventOrigin}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = Bill.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
            fillerControl.eventOrigin = eventOrigin
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
        return true
    }
}
