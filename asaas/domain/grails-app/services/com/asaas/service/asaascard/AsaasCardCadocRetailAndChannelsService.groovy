package com.asaas.service.asaascard

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocEMoneyCardBrand
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationProduct
import com.asaas.cadoc.retailAndChannels.enums.CadocRetailAndChannelsFillerType
import com.asaas.cadoc.retailAndChannels.enums.CadocInternalOperationType
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.asaascardtransaction.AsaasCardTransaction
import com.asaas.domain.cadoc.CadocRetailAndChannelsFillerControl
import com.asaas.integration.bifrost.adapter.cadoc.CadocAsaasCardBillsInfoAdapter
import com.asaas.integration.bifrost.adapter.cadoc.CadocAsaasCardTransactionsInfoAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class AsaasCardCadocRetailAndChannelsService {

    def asaasBacenCadocRetailAndChannelsManagerService
    def bifrostCadocManagerService
    def retailAndChannelsManagerService

    public Boolean createForTransactionInfo() {
        CadocRetailAndChannelsFillerControl cadocRetailAndChannelsFillerControl = CadocRetailAndChannelsFillerControl.query([className: AsaasCardTransaction.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.EMONEY_CARD]).get()
        Date baseDate = cadocRetailAndChannelsFillerControl ? CustomDateUtils.sumDays(cadocRetailAndChannelsFillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        CadocAsaasCardTransactionsInfoAdapter cadocAsaasCardTransactionsInfoAdapter = bifrostCadocManagerService.findTransactionsInfo(baseDate)

        Map transactionsInfoDataMap = [
            cadocEMoneyCardBrand: CadocEMoneyCardBrand.OTHERS,
            cardCount: cadocAsaasCardTransactionsInfoAdapter.issuedCardCount,
            cardTransactionsCount: cadocAsaasCardTransactionsInfoAdapter.transactionAndWithdrawalCount,
            sumCardTransactionsValue: cadocAsaasCardTransactionsInfoAdapter.transactionAndWithdrawalAmount
        ]

        CadocRetailAndChannelsFillerResponseAdapter cadocRetailAndChannelsFillerResponseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createAsaasCardTransactionEMoneyInfo(baseDate, transactionsInfoDataMap)
        retailAndChannelsManagerService.createCardTransactionInfo(baseDate, transactionsInfoDataMap)

        if (!cadocRetailAndChannelsFillerResponseAdapter.success) {
            AsaasLogger.error("AsaasCardCadocRetailAndChannelsService.findTransactionInfo >>> Erro ao salvar os dados [errorMessage: ${cadocRetailAndChannelsFillerResponseAdapter.errorDescription}]")
            return true
        }

        if (!cadocRetailAndChannelsFillerControl) {
            cadocRetailAndChannelsFillerControl = new CadocRetailAndChannelsFillerControl()
            cadocRetailAndChannelsFillerControl.className = AsaasCardTransaction.getSimpleName()
            cadocRetailAndChannelsFillerControl.fillerType = CadocRetailAndChannelsFillerType.EMONEY_CARD
        }

        cadocRetailAndChannelsFillerControl.baseDateSynchronized = baseDate
        cadocRetailAndChannelsFillerControl.save(failOnError: true)

        return true
    }

    public Boolean createForBillsInfo() {
        CadocRetailAndChannelsFillerControl cadocRetailAndChannelsFillerControl = CadocRetailAndChannelsFillerControl.query([className: AsaasCardBillPayment.getSimpleName(), fillerType: CadocRetailAndChannelsFillerType.INTERNAL_OPERATION]).get()
        Date baseDate = cadocRetailAndChannelsFillerControl ? CustomDateUtils.sumDays(cadocRetailAndChannelsFillerControl.baseDateSynchronized, 1) : CadocRetailAndChannelsFillerControl.getInitialBaseDate()

        if (CustomDateUtils.isSameDayOfYear(baseDate, new Date())) return false

        CadocAsaasCardBillsInfoAdapter cadocAsaasCardBillsInfoAdapter = bifrostCadocManagerService.findBillsInfo(baseDate)

        Map billsInfoDataMap = [
            operationType: CadocInternalOperationType.DIRECT_DEBIT_BANKING_RELATIONSHIP,
            transactionsCount: cadocAsaasCardBillsInfoAdapter.paidBillCount,
            sumTransactionsValue: cadocAsaasCardBillsInfoAdapter.paidBillAmount,
            product: CadocInternalOperationProduct.ASAAS_CARD_BILL
        ]

        CadocRetailAndChannelsFillerResponseAdapter cadocRetailAndChannelsFillerResponseAdapter = asaasBacenCadocRetailAndChannelsManagerService.createInternalOperation(baseDate, billsInfoDataMap)
        retailAndChannelsManagerService.createInternalOperation(baseDate, billsInfoDataMap)

        if (!cadocRetailAndChannelsFillerResponseAdapter.success) {
            AsaasLogger.error("AsaasCardCadocRetailAndChannelsService.createForBillsInfo >>> Erro ao salvar os dados [errorMessage: ${cadocRetailAndChannelsFillerResponseAdapter.errorDescription}]")
            return true
        }

        if (!cadocRetailAndChannelsFillerControl) {
            cadocRetailAndChannelsFillerControl = new CadocRetailAndChannelsFillerControl()
            cadocRetailAndChannelsFillerControl.className = AsaasCardBillPayment.getSimpleName()
            cadocRetailAndChannelsFillerControl.fillerType = CadocRetailAndChannelsFillerType.INTERNAL_OPERATION
        }

        cadocRetailAndChannelsFillerControl.baseDateSynchronized = baseDate
        cadocRetailAndChannelsFillerControl.save(failOnError: true)

        return true
    }
}
