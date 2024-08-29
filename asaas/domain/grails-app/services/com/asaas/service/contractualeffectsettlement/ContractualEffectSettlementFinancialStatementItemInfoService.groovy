package com.asaas.service.contractualeffectsettlement

import com.asaas.domain.contractualeffectsettlement.ContractualEffectSettlementFinancialStatementItemInfo
import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.financialstatement.adapter.FinancialStatementItemAdapter
import grails.transaction.Transactional

@Transactional
class ContractualEffectSettlementFinancialStatementItemInfoService {

    public void save(FinancialStatementItemAdapter adapter, FinancialStatementItem financialStatementItem) {
        ContractualEffectSettlementFinancialStatementItemInfo itemInfo = new ContractualEffectSettlementFinancialStatementItemInfo()
        itemInfo.publicId = adapter.publicId
        itemInfo.financialStatementItem = financialStatementItem
        itemInfo.save(failOnError: true)
    }
}
