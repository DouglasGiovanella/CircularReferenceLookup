package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class PaymentCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def retailAndChannelsManagerService

    public Boolean sendPaymentReceivedWithCreditOrDebitCardWithoutAnticipation() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: Payment.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.INTERNAL_OPERATION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        Map paymentCardInfoMap = Payment.createCriteria().get() {
            projections {
                resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

                sqlProjection("count(this_.id) as sumQuantity", ["sumQuantity"], [INTEGER])
                sqlProjection("coalesce(sum(this_.value), 0) as sumValue", ["sumValue"], [BIG_DECIMAL])
            }
            'in'("billingType", [BillingType.MUNDIPAGG_CIELO, BillingType.DEBIT_CARD])
            eq("anticipated", false)
            ge("creditDate", baseDate.clearTime())
            le("creditDate", CustomDateUtils.setTimeToEndOfDay(baseDate))
            eq("status", PaymentStatus.RECEIVED)
            eq("deleted", false)
            isNotNull("paymentDate")
        }

         Map cadocInternalOperationData = [
            operationType: CadocInternalOperationType.DIRECT_CREDIT_OTHERS,
            sumTransactionsValue: paymentCardInfoMap.sumValue,
            transactionsCount: paymentCardInfoMap.sumQuantity,
            product: CadocInternalOperationProduct.PAYMENT_RECEIVED_WITH_CREDIT_OR_DEBIT_CARD_WITHOUT_ANTICIPATION
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)
        retailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendPaymentReceivedWithCreditOrDebitCardWithoutAnticipation >> Erro ao enviar dados de operações internas. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de recebimentos de cartão sem antecipação na data ${baseDate}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = Payment.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.INTERNAL_OPERATION
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
        return true
    }
}
