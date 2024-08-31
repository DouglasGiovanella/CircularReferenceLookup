package com.asaas.service.financialstatement

import com.asaas.domain.contractualeffectsettlement.ContractualEffectSettlementFinancialStatementItemInfo
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatementitem.BaseFinancialStatementItemInfo
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.domain.scd.ScdFinancialStatementItemInfo
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialstatement.adapter.FinancialStatementItemAdapter
import com.asaas.financialstatement.adapter.FinancialStatementItemBatchRequestAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class ApiFinancialStatementService {

    def contractualEffectSettlementFinancialStatementItemInfoService
    def financialStatementCacheService
    def financialStatementService
    def financialStatementItemService
    def scdFinancialStatementItemInfoService

    public Long save(FinancialStatementItemBatchRequestAdapter adapter) {
        Long financialStatementId = financialStatementCacheService.findId(adapter.type, adapter.referenceDate, adapter.bank)
        if (!financialStatementId) financialStatementId = financialStatementService.save(adapter.type, adapter.referenceDate, adapter.bank, null).id

        saveItems(adapter.type, adapter.financialStatementItemAdapterList, financialStatementId)

        return financialStatementId
    }

    private void saveItems(FinancialStatementType type, List<FinancialStatementItemAdapter> adapterList, Long financialStatementId) {
        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(adapterList, flushEvery, { FinancialStatementItemAdapter adapter ->
            if (alreadyExistsItem(type, adapter.publicId)) return

            Object itemInfo = findOrBuildObjectInfo(type, adapter)
            FinancialStatementItem financialStatementItem = financialStatementItemService.save(FinancialStatement.load(financialStatementId), itemInfo)

            saveFinancialStatementItemInfoByType(type, adapter, financialStatementItem)
        })
    }

    private void saveFinancialStatementItemInfoByType(FinancialStatementType type, FinancialStatementItemAdapter adapter, FinancialStatementItem financialStatementItem) {
        if (type.isScd()) {
            scdFinancialStatementItemInfoService.save(adapter, financialStatementItem)
            return
        }

        if (type.isContractualEffectSettlement()) {
            contractualEffectSettlementFinancialStatementItemInfoService.save(adapter, financialStatementItem)
            return
        }

        throw new NotImplementedException("Não implementado save de financialStatementItemInfo para o tipo [${type}]")
    }

    private Boolean alreadyExistsItem(FinancialStatementType type, String publicId) {
        Class<? extends BaseFinancialStatementItemInfo> domainClass

        if (type.isScd()) domainClass = ScdFinancialStatementItemInfo
        if (type.isContractualEffectSettlement()) domainClass = ContractualEffectSettlementFinancialStatementItemInfo

        if (!domainClass) throw new RuntimeException("Não implementado domain de financialStatementItemInfo para o tipo [${type}]")

        return domainClass.query([publicId: publicId, exists: true]).get().asBoolean()
    }

    private Object findOrBuildObjectInfo(FinancialStatementType type, FinancialStatementItemAdapter itemAdapter) {
        if (type.isScd()) {
            Map itemInfo = [:]
            itemInfo.originDescription = type.getLabel()
            itemInfo.value = itemAdapter.value

            return itemInfo
        }

        if (type.isContractualEffectSettlement()) return FinancialTransaction.load(itemAdapter.financialTransactionId)

        throw new NotImplementedException("Não implementado build de financialStatementItemInfo para o tipo [${type}]")
    }
}
