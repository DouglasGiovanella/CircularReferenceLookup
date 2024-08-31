package com.asaas.service.pix.recurring

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.RecurringCheckoutSchedulePixItem
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.RecurringCheckoutSchedulePixItemStatus
import com.asaas.pix.vo.transaction.PixAddressKeyDebitVO
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.recurringCheckoutSchedule.repository.RecurringCheckoutSchedulePixItemRepository
import com.asaas.service.pix.PixDebitService
import com.asaas.service.recurringCheckoutSchedule.RecurringCheckoutScheduleService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionRecurringScheduleProcessingService {

    PixDebitService pixDebitService
    RecurringCheckoutSchedulePixItemService recurringCheckoutSchedulePixItemService
    RecurringCheckoutScheduleService recurringCheckoutScheduleService

    public Boolean process() {
        Date scheduledDateFilter = CustomDateUtils.tomorrow()

        final Integer max = 100
        List<Long> recurringItemIdList = RecurringCheckoutSchedulePixItemRepository.query([
            status: RecurringCheckoutSchedulePixItemStatus.PENDING,
            scheduledDate: scheduledDateFilter
        ]).disableRequiredFilters().column("id").list(max: max)
        if (!recurringItemIdList) return false

        for (Long recurringItemId : recurringItemIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                RecurringCheckoutSchedulePixItem recurringItem = RecurringCheckoutSchedulePixItem.get(recurringItemId)

                validate(recurringItem)

                PixTransaction pixTransaction = saveScheduledPixTransaction(recurringItem)
                if (pixTransaction.hasErrors()) {
                    recurringCheckoutSchedulePixItemService.refuse(recurringItem)
                    return
                }

                recurringCheckoutSchedulePixItemService.finish(recurringItem, pixTransaction)
                finishRecurringIfPossible(recurringItem)
            }, [logErrorMessage: "${this.class.simpleName}.process >> Erro ao processar recorrência [recurringItemId: ${recurringItemId}]", onError: { hasError = true }])

            if (hasError) {
                RecurringCheckoutSchedulePixItem recurringItem = RecurringCheckoutSchedulePixItem.get(recurringItemId)
                recurringCheckoutSchedulePixItemService.refuse(recurringItem)
            }
        }

        return true
    }

    private PixTransaction saveScheduledPixTransaction(RecurringCheckoutSchedulePixItem recurringItem) {
        PixTransaction pixTransaction

        switch (recurringItem.recurring.pixExternalAccount.originType) {
            case PixTransactionOriginType.ADDRESS_KEY:
                PixAddressKeyDebitVO pixAddressKeyDebitVO = new PixAddressKeyDebitVO(recurringItem)
                pixTransaction = pixDebitService.saveAddressKeyDebit(pixAddressKeyDebitVO.customer, pixAddressKeyDebitVO, [:])
                break
            case PixTransactionOriginType.MANUAL:
                PixManualDebitVO pixManualDebitVO = new PixManualDebitVO(recurringItem)
                pixTransaction = pixDebitService.saveManualDebit(pixManualDebitVO.customer, pixManualDebitVO, [:])
                break
            default:
                throw new IllegalArgumentException("O tipo de origem da recorrência ${recurringItem.recurring.origin} não é suportado")
        }

        if (pixTransaction.hasErrors()) return pixTransaction

        List<PixTransactionStatus> allowedStatus = [PixTransactionStatus.SCHEDULED, PixTransactionStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST]
        if (!allowedStatus.contains(pixTransaction.status)) throw new RuntimeException("A situação de retorno ${pixTransaction.status} da transação ${pixTransaction.id} é inválido.")

        return pixTransaction
    }

    private void finishRecurringIfPossible(RecurringCheckoutSchedulePixItem recurringItem) {
        Boolean isLastItem = RecurringCheckoutSchedulePixItemRepository.query([
            recurringId: recurringItem.recurring.id,
            status: RecurringCheckoutSchedulePixItemStatus.PENDING
        ]).disableRequiredFilters().count() == 0

        if (isLastItem) {
            recurringCheckoutScheduleService.finish(recurringItem.recurring)
        }
    }

    private void validate(RecurringCheckoutSchedulePixItem recurringItem) {
        if (!recurringItem.status.isPending()) throw new RuntimeException("O status ${recurringItem.status} não é hábil para processamento.")
    }
}
