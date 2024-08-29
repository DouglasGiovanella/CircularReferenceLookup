package com.asaas.service.integration.cerc.contractualeffect

import com.asaas.receivableregistration.contractualeffect.settlement.vo.ContractualEffectSettlementVO
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.domain.split.PaymentSplit
import com.asaas.integration.cerc.enums.CercProcessingStatus
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementStatus
import com.asaas.log.AsaasLogger
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.split.PaymentSplitStatus

import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class CercContractualEffectSettlementService {

    def cercContractService
    def cercContractualEffectService
    def cercContractualEffectProcessingService
    def cercContractualEffectSettlementBatchService
    def financialTransactionContractualEffectSettlementService
    def internalLoanService
    def receivableUnitItemService

    public void useItemToSettleContractsIfNecessary(ReceivableUnitItem receivableUnitItemFromPayment) {
        if (receivableUnitItemFromPayment.anticipation) return

        List<CercContractualEffect> contractualEffectList = cercContractualEffectService.listActiveContractsForReceivableUnit(receivableUnitItemFromPayment.originalReceivableUnit.id)
        if (!contractualEffectList) {
            AsaasLogger.info("CercContractualEffectSettlementService.useItemToSettleContractsIfNecessary >> O UR item [${receivableUnitItemFromPayment.id}] não possui efeitos.")
            return
        }

        settleContractualEffectListIfPossible(contractualEffectList, receivableUnitItemFromPayment)
    }

    public void settleContractualEffectItem(ReceivableUnitItem contractualEffectItem) {
        ReceivableUnitItemStatus itemStatusBeforeSettlement = contractualEffectItem.status
        receivableUnitItemService.settleIfPossible(contractualEffectItem)

        if (contractualEffectItem.contractualEffect.isPartnerEffect()) return

        save(contractualEffectItem, itemStatusBeforeSettlement)
    }

    private void settleContractualEffectListIfPossible(List<CercContractualEffect> contractualEffectList, ReceivableUnitItem receivableUnitItemFromPayment) {
        for (CercContractualEffect contractualEffect : contractualEffectList) {
            if (contractualEffect.status.isPending()) {
                cercContractualEffectProcessingService.processIfPossible(contractualEffect)
                AsaasLogger.info("CercContractualEffectSettlementService.settleContractualEffectListIfPossible >> O efeito de contrato [${contractualEffect.id}] entrou em processo de liquidação antes de ter sido processado.")
            }

            ContractualEffectSettlementVO contractualEffectSettlementVO = new ContractualEffectSettlementVO(contractualEffect, receivableUnitItemFromPayment)
            if (!contractualEffectSettlementVO.canSettleContractualEffect) {
                cercContractualEffectProcessingService.setAsNotProcessed(contractualEffect)
                continue
            }

            finishContractualEffectIfPossible(contractualEffect, contractualEffectSettlementVO.receivableUnitItemFromContractualEffect, contractualEffectSettlementVO.debitValue)

            executeDebitValueInPaymentItem(receivableUnitItemFromPayment, contractualEffectSettlementVO.debitValue)

            executeDebitValueInContractualEffectItem(contractualEffectSettlementVO, receivableUnitItemFromPayment.payment)

            if (receivableUnitItemFromPayment.status.isReadyToScheduleSettlement()) {
                receivableUnitItemService.setAsReadyToScheduleSettlement(contractualEffectSettlementVO.receivableUnitItemFromContractualEffect)
            } else {
                settleContractualEffectItem(contractualEffectSettlementVO.receivableUnitItemFromContractualEffect)
            }

            processContractualEffectByStatus(contractualEffect)

            if (receivableUnitItemFromPayment.netValue == 0) break
        }
    }

    private void finishContractualEffectIfPossible(CercContractualEffect contractualEffect, ReceivableUnitItem receivableUnitItemFromContractualEffect, BigDecimal debitValue) {
        Boolean canFinishContractualEffect = receivableUnitItemFromContractualEffect.netValue == debitValue
        if (canFinishContractualEffect) cercContractualEffectService.setAsFinished(contractualEffect)
    }

    private void processContractualEffectByStatus(CercContractualEffect contractualEffect) {
        switch (contractualEffect.status) {
            case CercProcessingStatus.PROCESSED:
                cercContractualEffectProcessingService.processIfPossible(contractualEffect)
                break
            case CercProcessingStatus.FINISHED:
                cercContractService.consolidate(contractualEffect.contract)
                break
            default:
                throw new NotImplementedException("O efeito de contrato [${contractualEffect.id}] recebeu um status [${contractualEffect.status}] não esperado.")
        }
    }

    private void executeDebitValueInPaymentItem(ReceivableUnitItem receivableUnitItem, BigDecimal debitValue) {
        receivableUnitItem.netValue -= debitValue
        receivableUnitItem.save(failOnError: true, flush: true)
    }

    private void executeDebitValueInContractualEffectItem(ContractualEffectSettlementVO contractualEffectSettlementVO, Payment payment) {
        ReceivableUnitItem contractualEffectItem = contractualEffectSettlementVO.receivableUnitItemFromContractualEffect
        contractualEffectItem.value = contractualEffectSettlementVO.debitValue
        contractualEffectItem.netValue = contractualEffectSettlementVO.debitValue
        contractualEffectItem.payment = payment
    }

    private void save(ReceivableUnitItem item, ReceivableUnitItemStatus itemStatusBeforeSettlement) {
        CercContractualEffectSettlement settlement = new CercContractualEffectSettlement()
        settlement.batch = cercContractualEffectSettlementBatchService.saveIfNecessary(item.bankAccountId, item.paymentArrangement, new Date(), itemStatusBeforeSettlement)
        settlement.status = CercContractualEffectSettlementStatus.DEBITED
        settlement.contractualEffect = item.contractualEffect
        settlement.customer = item.payment.provider
        settlement.receivableUnitItem = item
        settlement.value = item.value
        settlement.save(failOnError: true)

        FinancialTransaction financialTransaction = financialTransactionContractualEffectSettlementService.saveDebit(settlement).financialTransaction

        Boolean paymentWasSplit = PaymentSplit.query([exists: true, payment: item.payment, status: PaymentSplitStatus.DONE]).get().asBoolean()
        if (paymentWasSplit) internalLoanService.saveIfNecessary(financialTransaction)
    }
}
