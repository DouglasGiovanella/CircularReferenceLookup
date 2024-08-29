package com.asaas.service.recurringCheckoutSchedule

import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutSchedule
import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutScheduleCustomDate

import grails.transaction.Transactional

@Transactional
class RecurringCheckoutScheduleCustomDateService {

    public void saveList(List<Date> dateList, RecurringCheckoutSchedule recurringCheckoutSchedule) {
        for (Date date : dateList) {
            RecurringCheckoutScheduleCustomDate recurringCheckoutScheduleCustomDate = new RecurringCheckoutScheduleCustomDate()
            recurringCheckoutScheduleCustomDate.date = date
            recurringCheckoutScheduleCustomDate.recurringCheckoutSchedule = recurringCheckoutSchedule
            recurringCheckoutScheduleCustomDate.save(failOnError: true)
        }
    }
}
