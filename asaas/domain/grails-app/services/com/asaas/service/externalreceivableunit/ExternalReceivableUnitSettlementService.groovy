package com.asaas.service.externalreceivableunit

import com.asaas.domain.externalreceivableunit.ExternalReceivableUnit
import com.asaas.domain.externalreceivableunit.ExternalReceivableUnitSettlement
import com.asaas.integration.cerc.adapter.externalreceivableunit.ExternalReceivableUnitSettlementAdapter
import com.asaas.integration.cerc.enums.ReceivableUnitSettlementType
import grails.transaction.Transactional

@Transactional
class ExternalReceivableUnitSettlementService {

    public void refreshSettlements(ExternalReceivableUnit externalReceivableUnit, List<ExternalReceivableUnitSettlementAdapter> settlementAdapterList) {
        List<ExternalReceivableUnitSettlement> existingSettlementList = ExternalReceivableUnitSettlement.query([externalReceivableUnitId: externalReceivableUnit.id, disableSort: true]).list()

        for (ExternalReceivableUnitSettlement existingSettlement : existingSettlementList) {
            existingSettlement.delete(failOnError: true)
        }

        for (ExternalReceivableUnitSettlementAdapter adapter : settlementAdapterList) {
            save(externalReceivableUnit, adapter.beneficiaryCpfCnpj, adapter.type)
        }
    }

    private void save(ExternalReceivableUnit externalReceivableUnit, String beneficiaryCpfCnpj, ReceivableUnitSettlementType type) {
        ExternalReceivableUnitSettlement settlement = new ExternalReceivableUnitSettlement()
        settlement.externalReceivableUnit = externalReceivableUnit
        settlement.beneficiaryCpfCnpj = beneficiaryCpfCnpj
        settlement.type = type
        settlement.save(failOnError: true)
    }
}
