package com.asaas.service.receivablehub.contractualeffect

import com.asaas.domain.contractualeffectsettlement.ContractualEffectSettlementItem
import com.asaas.contractualeffectsettlement.enums.ContractualEffectSettlementItemStatus
import com.asaas.domain.financialtransactioncontractualeffectsettlementitem.FinancialTransactionContractualEffectSettlementItem
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.integration.receivablehub.adapter.RefundContractualEffectDebitAdapter
import com.asaas.integration.receivablehub.adapter.SaveContractualEffectDebitAdapter
import grails.transaction.Transactional

@Transactional
class ContractualEffectSettlementItemService {

    def financialTransactionContractualEffectSettlementItemService

    public Long saveIfNecessary(SaveContractualEffectDebitAdapter contractualEffectDebitAdapter) {
        Long settlementItemId = ContractualEffectSettlementItem.query([column: "id", externalIdentifier: contractualEffectDebitAdapter.externalIdentifier]).get()
        if (settlementItemId) return FinancialTransactionContractualEffectSettlementItem.query([column: "financialTransaction.id", settlementItemId: settlementItemId]).get()

        ContractualEffectSettlementItem settlementItem = new ContractualEffectSettlementItem()
        settlementItem.customer = contractualEffectDebitAdapter.customer
        settlementItem.status = ContractualEffectSettlementItemStatus.DEBITED
        settlementItem.value = contractualEffectDebitAdapter.value
        settlementItem.payment = contractualEffectDebitAdapter.payment
        settlementItem.externalIdentifier = contractualEffectDebitAdapter.externalIdentifier
        settlementItem.save(failOnError: true)

        return financialTransactionContractualEffectSettlementItemService.saveDebit(settlementItem, contractualEffectDebitAdapter.beneficiaryCpfCnpj).financialTransactionId
    }

    public Long updateAsRefundedIfNecessary(RefundContractualEffectDebitAdapter refundDebitAdapter) {
        ContractualEffectSettlementItem settlementItem = refundDebitAdapter.settlementItem
        if (settlementItem.status.isRefunded()) return FinancialTransactionContractualEffectSettlementItem.query([column: "financialTransaction.id", settlementItemId: refundDebitAdapter.settlementItem.id, financialTransactionType: FinancialTransactionType.CONTRACTUAL_EFFECT_SETTLEMENT_REVERSAL]).get()

        settlementItem.status = ContractualEffectSettlementItemStatus.REFUNDED
        settlementItem.save(failOnError: true)

        return financialTransactionContractualEffectSettlementItemService.reverseDebit(settlementItem, refundDebitAdapter.beneficiaryCpfCnpj).financialTransactionId
    }
}
