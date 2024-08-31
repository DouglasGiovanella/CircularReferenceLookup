package com.asaas.service.knownyourcustomer

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.domain.knownyourcustomer.KnownYourCustomerRequestBatch
import com.asaas.knownyourcustomer.KnownYourCustomerRequestBatchStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class ChildAccountKnownYourCustomerCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def retailAndChannelsManagerService

    public void sendChildAccountKnownYourCustomerInfo() {
        CadocRetailAndChannelsFillerControl fillerControl = CadocRetailAndChannelsFillerControl.query([className: KnownYourCustomerRequestBatch.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER]).get()
        Date baseStartDate = fillerControl ? CustomDateUtils.sumDays(fillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()
        Date baseEndDate = CustomDateUtils.getLastDayOfMonth(baseStartDate)

        if (baseEndDate >= new Date().clearTime()) return

        Map searchParams = [:]
        searchParams.status = KnownYourCustomerRequestBatchStatus.PROCESSED
        searchParams."dateCreated[ge]" = baseStartDate.clearTime()
        searchParams."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(baseEndDate)

        List<KnownYourCustomerRequestBatch> batches = KnownYourCustomerRequestBatch.query(searchParams).list(readOnly:true)

        Long transactionsCount = batches.size()
        BigDecimal sumTransactionsValue = batches ? batches.sum { it.value } : 0.0

        Map cadocInternalOperationData = [
                operationType: CadocInternalOperationType.DIRECT_DEBIT_BANKING_RELATIONSHIP,
                product: CadocInternalOperationProduct.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER,
                sumTransactionsValue: sumTransactionsValue,
                transactionsCount: transactionsCount
        ]

        CadocRetailAndChannelsFillerResponseAdapter responseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseStartDate, cadocInternalOperationData)
        retailAndChannelsManagerService.createInternalOperation(baseStartDate, cadocInternalOperationData)

        if (!responseAdapter.success) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.sendChildAccountKnownYourCustomerInfo >> Erro ao enviar dados de cobrança de kyc na criação de subcontas. [errorDescription: ${responseAdapter.errorDescription}]")
            return
        }

        if (!fillerControl) {
            fillerControl = new CadocRetailAndChannelsFillerControl()
            fillerControl.className = KnownYourCustomerRequestBatch.getSimpleName()
            fillerControl.fillerType = CadocRetailAndChannelsFillerType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER
        }

        fillerControl.baseDateSynchronized = baseEndDate
        fillerControl.save(failOnError: true)
    }
}
