package com.asaas.service.pix.recurring

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.RecurringCheckoutSchedulePixItem
import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutSchedule
import com.asaas.exception.BusinessException
import com.asaas.pix.RecurringCheckoutSchedulePixItemStatus
import com.asaas.recurringCheckoutSchedule.repository.RecurringCheckoutSchedulePixItemRepository
import com.asaas.utils.CustomDateUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class RecurringCheckoutSchedulePixItemService {

    public void saveList(RecurringCheckoutSchedule recurringCheckoutSchedule, Date transferDate) {
        List<Date> scheduleDateList = calculateScheduleDateList(recurringCheckoutSchedule, transferDate)

        scheduleDateList.each { Date scheduleDate ->
            save(recurringCheckoutSchedule, scheduleDate)
        }
    }

    public void cancelAllPending(RecurringCheckoutSchedule recurringCheckoutSchedule) {
        List<Long> itemIdList = RecurringCheckoutSchedulePixItemRepository.query([recurringId: recurringCheckoutSchedule.id, "scheduledDate[gt]": new Date().clearTime(), status: RecurringCheckoutSchedulePixItemStatus.PENDING]).column("id").disableRequiredFilters().list(order: "asc")
        if (!itemIdList) return

        for (Long itemId : itemIdList) {
            RecurringCheckoutSchedulePixItem item = RecurringCheckoutSchedulePixItemRepository.get(itemId)
            cancel(item)
        }
    }

    public RecurringCheckoutSchedulePixItem cancel(RecurringCheckoutSchedulePixItem recurringCheckoutSchedulePixItem) {
        BusinessValidation validatedBusiness = recurringCheckoutSchedulePixItem.canBeCancelled()
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        recurringCheckoutSchedulePixItem.status = RecurringCheckoutSchedulePixItemStatus.CANCELLED
        return recurringCheckoutSchedulePixItem.save(failOnError: true)
    }

    public void finish(RecurringCheckoutSchedulePixItem item, PixTransaction pixTransaction) {
        if (!item.status.isPending()) throw new RuntimeException("A situação dessa recorrência não permite conclusão")

        item.status = RecurringCheckoutSchedulePixItemStatus.DONE
        item.pixTransaction = pixTransaction
        item.save(failOnError: true)
    }

    public void refuse(RecurringCheckoutSchedulePixItem item) {
        if (!item.status.isPending()) throw new RuntimeException("A situação dessa recorrência não permite recusar")

        item.status = RecurringCheckoutSchedulePixItemStatus.REFUSED
        item.save(failOnError: true)
    }

    private void save(RecurringCheckoutSchedule recurring, Date scheduledDate) {
        RecurringCheckoutSchedulePixItem recurringCheckoutSchedulePixItem = new RecurringCheckoutSchedulePixItem()
        recurringCheckoutSchedulePixItem.recurring = recurring
        recurringCheckoutSchedulePixItem.scheduledDate = scheduledDate
        recurringCheckoutSchedulePixItem.status = RecurringCheckoutSchedulePixItemStatus.PENDING
        recurringCheckoutSchedulePixItem.save(failOnError: true)
    }

    private List<Date> calculateScheduleDateList(RecurringCheckoutSchedule recurringCheckoutSchedule, Date transferDate) {
        List<Date> scheduleDateList = []

        for (Integer index = 1; index <= recurringCheckoutSchedule.quantity; index++) {
            if (recurringCheckoutSchedule.frequency.isWeekly()) {
                Date executionDate = CustomDateUtils.sumWeeks(transferDate, index).clearTime()
                scheduleDateList.add(executionDate)
                continue
            }
            if (recurringCheckoutSchedule.frequency.isMonthly()) {
                Date executionDate = RecurringCheckoutSchedule.calculateMonthlyExecutionDate(transferDate, index)
                scheduleDateList.add(executionDate)
                continue
            }

            throw new IllegalArgumentException("Frequência ${recurringCheckoutSchedule.frequency} não mapeada")
        }

        return scheduleDateList
    }
}
