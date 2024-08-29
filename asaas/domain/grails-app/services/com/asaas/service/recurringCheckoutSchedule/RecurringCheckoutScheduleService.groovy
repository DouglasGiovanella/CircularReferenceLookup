package com.asaas.service.recurringCheckoutSchedule

import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutSchedule
import com.asaas.exception.BusinessException
import com.asaas.recurringCheckoutSchedule.RecurringCheckoutScheduleStatus
import com.asaas.recurringCheckoutSchedule.RecurringCheckoutScheduleFrequency
import com.asaas.recurringCheckoutSchedule.adapter.RecurringCheckoutScheduleAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class RecurringCheckoutScheduleService {

    def recurringCheckoutScheduleCustomDateService
    def recurringCheckoutSchedulePixItemService

    public RecurringCheckoutSchedule save(RecurringCheckoutScheduleAdapter recurringScheduleAdapter) {
        RecurringCheckoutScheduleFrequency recurringCheckoutScheduleFrequency = recurringScheduleAdapter.frequency
        RecurringCheckoutSchedule recurringSchedule = new RecurringCheckoutSchedule()

        BusinessValidation validatedBusiness = validate(recurringScheduleAdapter)
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(recurringSchedule, validatedBusiness.getFirstErrorMessage())
            return recurringSchedule
        }

        recurringSchedule.customer = recurringScheduleAdapter.customer
        recurringSchedule.value = recurringScheduleAdapter.value
        recurringSchedule.origin = recurringScheduleAdapter.origin
        recurringSchedule.startDate = recurringCheckoutScheduleFrequency.isCustom() ? Collections.min(recurringScheduleAdapter.dateList) : recurringScheduleAdapter.startDate
        recurringSchedule.finishDate = calculateFinishDate(recurringScheduleAdapter, recurringSchedule.startDate)
        recurringSchedule.status = RecurringCheckoutScheduleStatus.PENDING
        recurringSchedule.frequency = recurringScheduleAdapter.frequency
        recurringSchedule.quantity = recurringCheckoutScheduleFrequency.isCustom() ? recurringScheduleAdapter.dateList.size() : recurringScheduleAdapter.quantity
        recurringSchedule.dayOfWeek = recurringScheduleAdapter.dayOfWeek
        recurringSchedule.dayOfMonth = recurringScheduleAdapter.dayOfMonth
        recurringSchedule.additionalInformation = recurringScheduleAdapter.additionalInformation
        recurringSchedule.save(failOnError: true)

        if (recurringCheckoutScheduleFrequency.isCustom()) recurringCheckoutScheduleCustomDateService.saveList(recurringScheduleAdapter.dateList, recurringSchedule)

        return recurringSchedule
    }

    public RecurringCheckoutSchedule schedule(RecurringCheckoutSchedule recurringSchedule) {
        recurringSchedule.status = RecurringCheckoutScheduleStatus.SCHEDULED
        recurringSchedule.save(failOnError: true)

        return recurringSchedule
    }

    public RecurringCheckoutSchedule finish(RecurringCheckoutSchedule recurringSchedule) {
        recurringSchedule.status = RecurringCheckoutScheduleStatus.DONE
        recurringSchedule.save(failOnError: true)

        return recurringSchedule
    }

    public RecurringCheckoutSchedule cancel(RecurringCheckoutSchedule recurringSchedule) {
        BusinessValidation validatedBusiness = recurringSchedule.canBeCancelled()
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        recurringSchedule.status = RecurringCheckoutScheduleStatus.CANCELLED
        recurringSchedule.save(failOnError: true)

        recurringCheckoutSchedulePixItemService.cancelAllPending(recurringSchedule)

        return recurringSchedule
    }

    public RecurringCheckoutSchedule update(RecurringCheckoutSchedule recurringCheckoutSchedule, RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        BusinessValidation validatedBusiness = validateUpdate(recurringCheckoutSchedule, recurringCheckoutScheduleAdapter)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        recurringCheckoutSchedule.value = recurringCheckoutScheduleAdapter.value
        recurringCheckoutSchedule.save(failOnError: true)

        return recurringCheckoutSchedule
    }

    private BusinessValidation validateUpdate(RecurringCheckoutSchedule recurringCheckoutSchedule, RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        validatedBusiness = validate(recurringCheckoutScheduleAdapter)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        validatedBusiness = recurringCheckoutSchedule.canBeUpdated()
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        if (recurringCheckoutScheduleAdapter.value == recurringCheckoutSchedule.value) {
            validatedBusiness.addError("recurringCheckoutSchedule.error.cannotBeUpdated.invalidValue")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation validate(RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (recurringCheckoutScheduleAdapter.value == null) {
            validatedBusiness.addError("recurringCheckoutSchedule.error.validate.valueIsNull")
            return validatedBusiness
        }

        if (recurringCheckoutScheduleAdapter.value < RecurringCheckoutSchedule.MINIMUM_VALUE) {
            validatedBusiness.addError("recurringCheckoutSchedule.error.validate.invalidValue")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private Date calculateFinishDate(RecurringCheckoutScheduleAdapter recurringScheduleAdapter, Date startDate) {
        RecurringCheckoutScheduleFrequency recurringCheckoutScheduleFrequency = recurringScheduleAdapter.frequency

        if (recurringCheckoutScheduleFrequency.isDaily()) {
            return CustomDateUtils.sumDays(startDate, recurringScheduleAdapter.quantity - 1)
        } else if (recurringCheckoutScheduleFrequency.isWeekly()) {
            Integer quantityWeeksToAdd = recurringScheduleAdapter.quantity - 1
            Calendar calendarStartDate = Calendar.getInstance()
            calendarStartDate.setTime(startDate)

            while (calendarStartDate.get(Calendar.DAY_OF_WEEK) != recurringScheduleAdapter.dayOfWeek.calendarWeekDay) {
                calendarStartDate.add(Calendar.DAY_OF_MONTH, 1)
            }

            Calendar nextDateForDayOfWeek = calendarStartDate
            nextDateForDayOfWeek.add(Calendar.WEEK_OF_YEAR, quantityWeeksToAdd)
            Calendar finishDate = nextDateForDayOfWeek

            return finishDate.time
        } else if (recurringCheckoutScheduleFrequency.isMonthly()) {
            Calendar calculatedDate = recurringScheduleAdapter.transferDate ? CustomDateUtils.getInstanceOfCalendar(recurringScheduleAdapter.transferDate) : Calendar.getInstance()

            Boolean startDateInCurrentMonth = calculatedDate.get(Calendar.DAY_OF_MONTH) < recurringScheduleAdapter.dayOfMonth
            Integer quantityMonthsToAdd = startDateInCurrentMonth ? recurringScheduleAdapter.quantity - 1 : recurringScheduleAdapter.quantity

            calculatedDate.add(Calendar.MONTH, quantityMonthsToAdd)

            Integer lastDayOfMonth = calculatedDate.getActualMaximum(Calendar.DAY_OF_MONTH)
            Integer dayOfMonth = lastDayOfMonth < recurringScheduleAdapter.dayOfMonth ? lastDayOfMonth + 1 : recurringScheduleAdapter.dayOfMonth

            calculatedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            return calculatedDate.time
        } else if (recurringCheckoutScheduleFrequency.isCustom()) {
            return Collections.max(recurringScheduleAdapter.dateList)
        }

        return startDate
    }
}
