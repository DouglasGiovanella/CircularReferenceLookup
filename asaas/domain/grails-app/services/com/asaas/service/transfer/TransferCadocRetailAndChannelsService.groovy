package com.asaas.service.transfer

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocAccessChannel
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.cadoc.retailAndChannels.enums.CadocTransactionProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class TransferCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def sessionFactory
    def retailAndChannelsManagerService

    public void sendCreditTransferRequestInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: CreditTransferRequest.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (baseDate == new Date().clearTime()) return

        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity, coalesce(sum(creditTransferRequest.value), 0) as sumValue ")
            .append(" from transfer this_ ")
            .append(" inner join credit_transfer_request creditTransferRequest on this_.credit_transfer_request_id = creditTransferRequest.id  ")
            .append(" inner join transfer_origin_requester originRequester on this_.id = originRequester.transfer_id  ")
            .append(" where this_.transfer_date >= :transferDateStart ")
            .append(" and this_.transfer_date <= :transferDateFinish ")
            .append(" and originRequester.event_origin = :eventOrigin ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setTimestamp("transferDateStart", baseDate.clearTime())
        query.setTimestamp("transferDateFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("eventOrigin", eventOrigin.toString())

        List resultQuery = query.uniqueResult()
        Map creditTransferRequestInfo = [
            sumQuantity: resultQuery.getAt(0),
            sumValue: resultQuery.getAt(1)
        ]

        Map cadocTransactionData = [
            product: CadocTransactionProduct.CREDIT_TRANSFER_REQUEST,
            accessChannel: CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            sumValue: creditTransferRequestInfo.sumValue,
            sumQuantity: creditTransferRequestInfo.sumQuantity
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendForCreditTransferRequest >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            return
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = CreditTransferRequest.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
            fillerControl.eventOrigin = eventOrigin
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
    }

    public void sendPixTransactionInfo(EventOriginType eventOrigin) {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: PixTransaction.getSimpleName(), eventOrigin: eventOrigin, fillerType: CadocRetailAndChannelsFillerType.TRANSACTION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (baseDate == new Date().clearTime()) return

        StringBuilder sql = new StringBuilder()
            .append(" select count(this_.id) as sumQuantity, coalesce(sum(abs(pixTransaction.value)), 0) as sumValue ")
            .append(" from transfer this_ ")
            .append(" inner join pix_transaction pixTransaction on this_.pix_transaction_id = pixTransaction.id  ")
            .append(" inner join transfer_origin_requester originRequester on this_.id = originRequester.transfer_id  ")
            .append(" where this_.transfer_date >= :transferDateStart ")
            .append(" and this_.transfer_date <= :transferDateFinish ")
            .append(" and originRequester.event_origin = :eventOrigin ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setTimestamp("transferDateStart", baseDate.clearTime())
        query.setTimestamp("transferDateFinish", CustomDateUtils.setTimeToEndOfDay(baseDate))
        query.setString("eventOrigin", eventOrigin.toString())

        List resultQuery = query.uniqueResult()
        Map pixTransactionInfo = [
            sumQuantity: resultQuery.getAt(0),
            sumValue: resultQuery.getAt(1)
        ]

        Map cadocTransactionData = [
            product: CadocTransactionProduct.PIX,
            accessChannel: CadocAccessChannel.parseFromEventOriginType(eventOrigin),
            sumValue: pixTransactionInfo.sumValue,
            sumQuantity: pixTransactionInfo.sumQuantity
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)
        retailAndChannelsManagerService.createTransaction(baseDate, cadocTransactionData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendPixTransactionInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            return
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = PixTransaction.getSimpleName()
            fillerControl.eventOrigin = eventOrigin.toString()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.TRANSACTION
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
    }

    public void sendInternalTransferInfo() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: InternalTransfer.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.INTERNAL_OPERATION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (baseDate == new Date().clearTime()) return

        Map internalTransferInfo = Transfer.createCriteria().get() {
            createAlias("internalTransfer", "internalTransfer")
            projections {
                resultTransformer(org.hibernate.criterion.CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

                sqlProjection("count(this_.id) as sumQuantity", ["sumQuantity"], [INTEGER])
                sqlProjection("coalesce(sum(internaltr1_.value), 0) as sumValue", ["sumValue"], [BIG_DECIMAL])
            }

            ge("transferDate", baseDate.clearTime())
            le("transferDate", CustomDateUtils.setTimeToEndOfDay(baseDate))
        }

        Map cadocInternalOperationData = [
            operationType: CadocInternalOperationType.BOOK_TRANSFER,
            sumTransactionsValue: internalTransferInfo.sumValue,
            transactionsCount: internalTransferInfo.sumQuantity,
            product: CadocInternalOperationProduct.INTERNAL_TRANSFER
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)
        retailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendInternalTransferInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            return
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = InternalTransfer.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.INTERNAL_OPERATION
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
    }
}
