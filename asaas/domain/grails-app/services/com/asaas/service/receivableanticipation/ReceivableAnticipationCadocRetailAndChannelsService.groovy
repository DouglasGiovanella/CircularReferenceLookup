package com.asaas.service.receivableanticipation

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class ReceivableAnticipationCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def retailAndChannelsManagerService

    public void sendCreditInfo() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: ReceivableAnticipation.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.INTERNAL_OPERATION]).get()
        Date baseDate = CustomDateUtils.sumDays(new Date(), -1)
        if (fillerControl && fillerControl.baseDateSynchronized.clone().clearTime() == baseDate.clearTime()) return

        Map anticipationInfo = FinancialTransaction.createCriteria().get() {
            projections {
                resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
                sqlProjection("count(this_.id) as sumQuantity", ["sumQuantity"], [INTEGER])
                sqlProjection("coalesce(sum(this_.value), 0) as sumValue", ["sumValue"], [BIG_DECIMAL])
            }
            'in'("transactionType", FinancialTransactionType.getReceivableAnticipationCreditTypes())
            if (fillerControl) {
                Date startDate = CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1)
                ge("transactionDate", startDate.clearTime())
                le("transactionDate", baseDate.clearTime())
            } else {
                eq("transactionDate", baseDate.clearTime())
            }
            eq("deleted", false)
        }


        Map cadocInternalOperationData = [
            operationType: CadocInternalOperationType.DIRECT_CREDIT_BANKING_RELATIONSHIP,
            sumTransactionsValue: anticipationInfo.sumValue,
            transactionsCount: anticipationInfo.sumQuantity,
            product: CadocInternalOperationProduct.RECEIVABLE_ANTICIPATION_CREDIT
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)
        retailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendCreditInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            return
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = ReceivableAnticipation.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.INTERNAL_OPERATION
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
    }
}
