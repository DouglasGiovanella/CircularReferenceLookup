package com.asaas.service.invoice

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.invoice.InvoiceItem
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class InvoiceItemCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def retailAndChannelsManagerService

    public Boolean sendInvoiceItemInfo() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: InvoiceItem.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.INTERNAL_OPERATION]).get()
        Date baseDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        Map invoiceItemInfoMap = InvoiceItem.createCriteria().get() {
            projections {
                resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

                sqlProjection("count(this_.id) as sumQuantity", ["sumQuantity"], [INTEGER])
                sqlProjection("coalesce(sum(this_.value), 0) as sumValue", ["sumValue"], [BIG_DECIMAL])
            }

            ge("dateCreated", baseDate.clearTime())
            le("dateCreated", CustomDateUtils.setTimeToEndOfDay(baseDate))

            isNotNull("billedCustomer")
            eq("deleted", false)
        }

         Map cadocInternalOperationData = [
            operationType: CadocInternalOperationType.DIRECT_DEBIT_BANKING_RELATIONSHIP,
            sumTransactionsValue: invoiceItemInfoMap.sumValue,
            transactionsCount: invoiceItemInfoMap.sumQuantity,
            product: CadocInternalOperationProduct.CUSTOMER_FEE
        ]
        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)
        retailAndChannelsManagerService.createInternalOperation(baseDate, cadocInternalOperationData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendInvoiceItemInfo >> Erro ao enviar dados de transações. [errorDescription: ${responseAdapter.errorDescription}]")
            throw new RuntimeException("Não foi possível processar dados de cobrança de tarifas na data ${baseDate}.")
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = InvoiceItem.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.INTERNAL_OPERATION
        }
        fillerControl.baseDateSynchronized = baseDate
        fillerControl.save(failOnError: true)
        return true
    }
}
