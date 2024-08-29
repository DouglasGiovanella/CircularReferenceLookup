package com.asaas.service.holiday

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.holiday.Holiday
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class HolidayService {

    def asyncActionService
    def holidayCacheService
    def paymentUpdateService

    public Holiday save(Date date) {
        Holiday validatedHoliday = validateSave(date)

        if (validatedHoliday.hasErrors()) return validatedHoliday

        Holiday holiday = new Holiday()
        holiday.date = date

        Map actionData = ["holidayDate": CustomDateUtils.fromDate(date, CustomDateUtils.DATABASE_DATETIME_FORMAT)]
        asyncActionService.save(AsyncActionType.UPDATE_PAYMENTS_WITH_CREDIT_DATE_ON_HOLIDAY, actionData)
        holidayCacheService.evictIsHoliday(date)

        return holiday.save(failOnError: true)
    }

    public Holiday delete(Holiday holiday) {
        holiday.deleted = true
        holiday.save(failOnError: true)

        holidayCacheService.evictIsHoliday(holiday.date)

        return holiday
    }

    public void updatePaymentsWithCreditDateOnHoliday() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.UPDATE_PAYMENTS_WITH_CREDIT_DATE_ON_HOLIDAY, null)
        if (!asyncActionDataList) return

        final Integer flushEvery = 10
        Utils.forEachWithFlushSession(asyncActionDataList, flushEvery, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Date holidayDate = CustomDateUtils.fromString(asyncActionData.holidayDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)
                Boolean existsPending = paymentUpdateService.updatePaymentCreditDateOnHolidayIfExists(holidayDate)
                if (!existsPending) asyncActionService.delete(asyncActionDataList.asyncActionId)
            }, [logErrorMessage: "HolidayService.updatePaymentsWithCreditDateOnHoliday >>> Erro ao atualizar cobranças com data de credito em feriado. holidayDate: [${asyncActionData.holidayDate}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        })
    }

    private Holiday validateSave(Date date) {
        Holiday holiday = new Holiday()

        if (!date) {
            return DomainUtils.addError(holiday, "Preencha o campo data")
        }

        Boolean holidayExists = Holiday.query([date: date, exists: true]).get().asBoolean()

        if (holidayExists) {
            DomainUtils.addError(holiday, "Feriado já cadastrado")
        }

        final Integer businessDaysTolerance = 5
        Integer differenceInBusinessDays = CustomDateUtils.calculateDifferenceInBusinessDays(new Date(), date)
        if (differenceInBusinessDays < businessDaysTolerance) {
            DomainUtils.addError(holiday, Utils.getMessageProperty("holiday.validation.error.businessDaysTolerance", [businessDaysTolerance]))
        }

        return holiday
    }
}
