package com.asaas.service.integration.bacen.cadoc

import com.asaas.cadoc.retailAndChannels.adapters.CadocRetailAndChannelsFillerResponseAdapter
import com.asaas.cadoc.retailAndChannels.enums.CadocEMoneyCardFunction

import grails.transaction.Transactional

@Transactional
class AsaasBacenCadocRetailAndChannelsManagerService {

    def cadocRetailAndChannelsAsaasService

    public CadocRetailAndChannelsFillerResponseAdapter createTransaction(Date baseDate, Map info) {
        return cadocRetailAndChannelsAsaasService.createTransaction(baseDate, info)
    }

    public CadocRetailAndChannelsFillerResponseAdapter createInternalOperation(Date baseDate, Map info) {
        return cadocRetailAndChannelsAsaasService.createInternalOperation(baseDate, info)
    }

    public CadocRetailAndChannelsFillerResponseAdapter createAsaasCardTransactionEMoneyInfo(Date referenceDate, Map transactionsInfoDataMap) {
        transactionsInfoDataMap.cadocEMoneyCardFunction = CadocEMoneyCardFunction.EMONEY

        return cadocRetailAndChannelsAsaasService.createAsaasCardTransactionInfo(referenceDate, transactionsInfoDataMap)
    }
}
