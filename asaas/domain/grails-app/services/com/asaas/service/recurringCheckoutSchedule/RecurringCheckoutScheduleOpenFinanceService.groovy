package com.asaas.service.recurringCheckoutSchedule

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutSchedule
import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutScheduleCustomDate
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitAdapter
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitListAdapter
import com.asaas.recurringCheckoutSchedule.RecurringCheckoutScheduleFrequency
import com.asaas.recurringCheckoutSchedule.adapter.RecurringCheckoutScheduleAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class RecurringCheckoutScheduleOpenFinanceService {

    def recurringCheckoutScheduleService

    public RecurringCheckoutSchedule save(RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        RecurringCheckoutSchedule recurringCheckoutSchedule = validateSave(recurringCheckoutScheduleAdapter)
        if (recurringCheckoutSchedule.hasErrors()) return recurringCheckoutSchedule

        return recurringCheckoutScheduleService.save(recurringCheckoutScheduleAdapter)
    }

    public ExternalDebit validateStartDate(ExternalDebit validatedDomain, ExternalDebitAdapter debitAdapter) {
        RecurringCheckoutSchedule recurringCheckoutSchedule = debitAdapter.externalDebitConsent.recurringCheckoutSchedule

        if (recurringCheckoutSchedule.frequency.isWeekly()) {
            Calendar externalDebitScheduledDate = CustomDateUtils.getInstanceOfCalendar(debitAdapter.scheduledDate)

            if (externalDebitScheduledDate.get(Calendar.DAY_OF_WEEK) != recurringCheckoutSchedule.dayOfWeek.calendarWeekDay) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidExternalDebitStartDate", "Consentimento inválido (O startDate da recorrência recebido é divergente da data semanal indicada no consentimento).")

                return validatedDomain
            }
        } else if (recurringCheckoutSchedule.frequency.isMonthly()) {
            Boolean isValidMonthlyStartDate = isValidMonthlyStartDate(debitAdapter, recurringCheckoutSchedule)

            if (!isValidMonthlyStartDate) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidExternalDebitStartDate", "Consentimento inválido (O startDate do pagamento agendado é invalido).")

                return validatedDomain
            }
        }

        return validatedDomain
    }

    public List<ExternalDebit> validateExternalDebitQuantity(ExternalDebitListAdapter externalDebitAdapterList) {
        ExternalDebit validatedDomain = new ExternalDebit()
        List<ExternalDebit> validatedDomainList = []

        if (externalDebitAdapterList.externalDebitConsent.isRecurringScheduled()) {
            RecurringCheckoutSchedule recurringSchedule = externalDebitAdapterList.externalDebitConsent.recurringCheckoutSchedule

            if (externalDebitAdapterList.externalDebitAdapterList.size() != recurringSchedule.quantity) {
                validatedDomainList.add(DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.quantityRecurringScheduleInvalid", "Consentimento inválido (Quantidade de recorrência recebida é divergente do consentimento)."))

                return validatedDomainList
            }
        }

        return validatedDomainList
    }

    public Date calculateDefaultMaximumRecurringScheduledDate() {
        return CustomDateUtils.sumDays(new Date(), ExternalDebit.RECURRING_SCHEDULING_LIMIT_DAYS)
    }

    private Boolean isValidMonthlyStartDate(ExternalDebitAdapter debitAdapter, RecurringCheckoutSchedule recurringCheckoutSchedule) {
        Calendar externalDebitScheduledDate = CustomDateUtils.getInstanceOfCalendar(debitAdapter.scheduledDate)

        Integer externalDebitScheduledDay = externalDebitScheduledDate.get(Calendar.DAY_OF_MONTH)
        Integer recurringScheduleDay = recurringCheckoutSchedule.dayOfMonth

        if (externalDebitScheduledDay == recurringScheduleDay) return true

        Boolean isScheduledForFirstDayOfMonth = externalDebitScheduledDay == 1
        if (isScheduledForFirstDayOfMonth) {
            externalDebitScheduledDate.add(Calendar.DAY_OF_MONTH, -1)
            Integer lastDayOfPreviousMonth = externalDebitScheduledDate.get(Calendar.DAY_OF_MONTH)

            Boolean isRecurrenceDayNotExistsInScheduledMonth = lastDayOfPreviousMonth < recurringScheduleDay

            return isRecurrenceDayNotExistsInScheduledMonth
        }

        return false
    }

    private RecurringCheckoutSchedule validateSave(RecurringCheckoutScheduleAdapter recurringScheduleAdapter) {
        RecurringCheckoutSchedule validatedRecurring = new RecurringCheckoutSchedule()
        RecurringCheckoutScheduleFrequency recurringCheckoutScheduleFrequency = recurringScheduleAdapter.frequency

        Boolean hasValidMaximumQuantity = hasValidMaximumQuantity(recurringCheckoutScheduleFrequency, recurringScheduleAdapter)
        if (!hasValidMaximumQuantity) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidQuantity", "Quantidade de recorrência inválida. A quantidade de recorrência é maior que o limite permitido.")

        Boolean hasValidFinishDate = hasValidFinishDate(recurringCheckoutScheduleFrequency, recurringScheduleAdapter)
        if (!hasValidFinishDate) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidPaymentDate.invalidSchedule", "Data de pagamento inválida. A data agendada recebida é maior que o limite permitido.")

        if (!recurringCheckoutScheduleFrequency) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidScheduledRecurrenceFrequency", "O valor de frequência de recorrência é inválido.")
        if (!recurringCheckoutScheduleFrequency.isCustom()) {
            if (!recurringScheduleAdapter.startDate) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidScheduledRecurrenceStartDate", "O valor da data de início, referente ao campo [startDate] é inválido.")
            if (!recurringScheduleAdapter.quantity) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidScheduleRecurrenceQuantity", "O valor da quantidade de recorrência, referente ao campo [quantity] é inválido.")
        }

        if (recurringCheckoutScheduleFrequency.isWeekly()) {
            if (!recurringScheduleAdapter.dayOfWeek) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidScheduledRecurrenceDayOfWeek", "O valor do dia da semana, referente ao campo [dayOfWeek] é inválido.")
        } else if (recurringCheckoutScheduleFrequency.isMonthly()) {
            if (!recurringScheduleAdapter.dayOfMonth) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidScheduledRecurrenceDayOfMonth", "O valor do dia do mês, referente ao campo [dayOfMonth] é inválido.")
        } else if (recurringCheckoutScheduleFrequency.isCustom()) {
            Boolean dateListContainsNull = (recurringScheduleAdapter.dateList.any { it == null })
            Boolean containsRepeatedDates = CustomDateUtils.containsRepeatedDates(recurringScheduleAdapter.dateList)

            if (recurringScheduleAdapter.dateList.size() < RecurringCheckoutScheduleCustomDate.MINIMUM_QUANTITY_OF_DATE || dateListContainsNull || containsRepeatedDates) return DomainUtils.addErrorWithErrorCode(validatedRecurring, "openFinance.invalidParameter.invalidScheduledRecurrenceDateCustom", "O valor da lista de datas customizadas, referente ao campo [dates] é inválido.")
        }

        return validatedRecurring
    }

    private Boolean hasValidFinishDate(RecurringCheckoutScheduleFrequency recurringCheckoutScheduleFrequency, RecurringCheckoutScheduleAdapter recurringScheduleAdapter) {
        Date recurrenceFinishDate = null
        Date consentMaximumScheduledDate = CustomDateUtils.sumDays(new Date(), ExternalDebit.RECURRING_SCHEDULING_LIMIT_DAYS)

        if (recurringCheckoutScheduleFrequency.isDaily()) {
            recurrenceFinishDate = CustomDateUtils.sumDays(recurringScheduleAdapter.startDate, recurringScheduleAdapter.quantity)
        } else if (recurringCheckoutScheduleFrequency.isWeekly()) {
            recurrenceFinishDate = CustomDateUtils.sumWeeks(recurringScheduleAdapter.startDate, recurringScheduleAdapter.quantity)
        } else if (recurringCheckoutScheduleFrequency.isMonthly()) {
            recurrenceFinishDate = CustomDateUtils.sumMonths(recurringScheduleAdapter.startDate, recurringScheduleAdapter.quantity)
        } else if (recurringCheckoutScheduleFrequency.isCustom()) {
            recurrenceFinishDate = recurringScheduleAdapter.dateList.last()
        }

        return recurrenceFinishDate <= consentMaximumScheduledDate
    }

    private Boolean hasValidMaximumQuantity(RecurringCheckoutScheduleFrequency recurringCheckoutScheduleFrequency, RecurringCheckoutScheduleAdapter recurringScheduleAdapter) {
        Boolean hasQuantityValid = true

        if (recurringCheckoutScheduleFrequency.isDaily()) {
            hasQuantityValid = recurringScheduleAdapter.quantity <= ExternalDebit.RECURRING_SCHEDULING_DAILY_LIMIT_QUANTITY
        } else if (recurringCheckoutScheduleFrequency.isWeekly()) {
            hasQuantityValid = recurringScheduleAdapter.quantity <= ExternalDebit.RECURRING_SCHEDULING_WEEKLY_LIMIT_QUANTITY
        } else if (recurringCheckoutScheduleFrequency.isMonthly()) {
            hasQuantityValid = recurringScheduleAdapter.quantity <= ExternalDebit.RECURRING_SCHEDULING_MONTHLY_LIMIT_QUANTITY
        } else if (recurringCheckoutScheduleFrequency.isCustom()) {
            hasQuantityValid = recurringScheduleAdapter.quantity <= ExternalDebit.RECURRING_SCHEDULING_CUSTOM_LIMIT_QUANTITY
        }

        return hasQuantityValid
    }
}
