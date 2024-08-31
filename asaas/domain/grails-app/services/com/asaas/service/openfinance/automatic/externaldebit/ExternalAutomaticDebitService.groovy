package com.asaas.service.openfinance.automatic.externaldebit

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentReceiver
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentInfo
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentPeriodicLimits
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class ExternalAutomaticDebitService {

    def externalDebitService
    def externalAutomaticDebitRiskInfoService

    public ExternalDebit save(ExternalDebitAdapter externalDebitAdapter) {
        ExternalDebit externalDebit = validate(externalDebitAdapter)
        if (externalDebit.hasErrors()) return externalDebit

        externalDebit = externalDebitService.saveSingle(externalDebitAdapter)
        if (externalDebit.hasErrors()) return externalDebit

        if (externalDebitAdapter.externalDebitRiskInfo) externalAutomaticDebitRiskInfoService.save(externalDebitAdapter.externalDebitRiskInfo, externalDebit)

        return externalDebit
    }

    public List<ExternalDebit> listByConsent(Long externalDebitConsentId, Date startDate, Date endDate) {
        Map searchParams = [
            consentId: externalDebitConsentId,
            ignoreCustomer: true
        ]

        if (startDate && endDate) {
            searchParams."dateCreated[ge]" = startDate
            searchParams."dateCreated[le]" = endDate
        }

        return ExternalDebit.query(searchParams).list(readOnly: true)
    }

    private ExternalDebit validate(ExternalDebitAdapter externalDebitAdapter) {
        ExternalDebit validatedDomain = new ExternalDebit()

        if (!externalDebitAdapter.externalDebitConsent) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.consentNull", "O pagamento não possui um consentimento.")

            return validatedDomain
        }

        if (externalDebitAdapter.externalDebitConsent.status?.isConsumed()) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.alreadyConsumed", "O consentimento está expirado ou inválido.")

            return validatedDomain
        }

        ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo = ExternalAutomaticDebitConsentInfo.query([consent: externalDebitAdapter.externalDebitConsent]).get()
        if (!externalAutomaticDebitConsentInfo) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.notAutomaticConsent", "O consentimento não é compatível com pagamentos automáticos.")
            return validatedDomain
        }

        if (!externalDebitAdapter.receiver) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidReceptor", "Para transação de origem manual é necssário informar os dados do recebedor.")
            return validatedDomain
        }

        if (externalDebitAdapter.customer.personType.isFisica()) {
            if (externalDebitAdapter.requesterCpfCnpj != externalDebitAdapter.customer.cpfCnpj) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.payerCpfCnpjDiffersFromCustomerCpfCnpj", "O documento do pagador difere do cliente vinculado ao consentimento.")
                return validatedDomain
            }

            if (externalDebitAdapter.receiver.cpfCnpj != externalDebitAdapter.customer.cpfCnpj) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDetail.invalidReceiverDocument", "Recebedor inválido.")
                return validatedDomain
            }
        } else {
            final Integer rootCnpjCharacter = 8

            String customerRootCnpj = externalDebitAdapter.customer.cpfCnpj.take(rootCnpjCharacter)
            String requesterRootCpfCnpj = externalDebitAdapter.requesterCpfCnpj.take(rootCnpjCharacter)
            if (requesterRootCpfCnpj != customerRootCnpj) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.payerCpfCnpjDiffersFromCustomerCpfCnpj", "O documento do pagador difere do cliente vinculado ao consentimento.")
                return validatedDomain
            }

            String receiverRootCnpj = externalDebitAdapter.receiver.cpfCnpj.take(rootCnpjCharacter)
            if (receiverRootCnpj != customerRootCnpj) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDetail.invalidReceiverDocument", "Recebedor inválido.")
                return validatedDomain
            }

            List<ExternalDebitConsentReceiver> receiverList = ExternalDebitConsentReceiver.query([debitConsent: externalDebitAdapter.externalDebitConsent]).list(readOnly: true)
            Boolean hasValidCnpj = receiverList.any { ExternalDebitConsentReceiver receiver -> receiver.cpfCnpj == externalDebitAdapter.receiver.cpfCnpj }

            if (!hasValidCnpj) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDetail.invalidReceiverDocument", "Recebedor inválido.")
                return validatedDomain
            }
        }

        if (!CustomDateUtils.isToday(externalDebitAdapter.debitDate)) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDate.scheduledDate", "A data de pagamento é inválida, pois não pode ser agendada.")
            return validatedDomain
        }

        if (externalDebitAdapter.debitDate && externalAutomaticDebitConsentInfo.startDate) {
            Boolean isValidStartDate = externalDebitAdapter.debitDate.clearTime() >= externalAutomaticDebitConsentInfo.startDate.clone().clearTime()
            if (!isValidStartDate) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidDate", "A data de início do pagamento não é compatível com o intervalo definido no consentimento.")
                return validatedDomain
            }
        }

        if (externalDebitAdapter.debitDate && externalAutomaticDebitConsentInfo.expirationDate) {
            Boolean isValidExpirationDate = externalDebitAdapter.debitDate.clearTime() <= externalAutomaticDebitConsentInfo.expirationDate.clone().clearTime()
            if (!isValidExpirationDate) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidDate", "A data de início do pagamento não é compatível com o intervalo definido no consentimento.")
                return validatedDomain
            }
        }

        if (!externalDebitAdapter.value) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidValue.valueNull", "O valor da transação é nulo.")
            return validatedDomain
        }

        if (externalAutomaticDebitConsentInfo.transactionLimitValue && (externalDebitAdapter.value > externalAutomaticDebitConsentInfo.transactionLimitValue)) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.totalAllowedAmountExceeded.transactionLimitValueExceeded", "O valor da transação é maior que o valor máximo definido por transação no consentimento.")
            return validatedDomain
        }

        ExternalAutomaticDebitConsentPeriodicLimits externalAutomaticDebitConsentPeriodicLimits = ExternalAutomaticDebitConsentPeriodicLimits.query([externalAutomaticDebitConsentInfo: externalAutomaticDebitConsentInfo]).get()
        List<ExternalDebit> externalDebitList = ExternalDebit.query([consent: externalDebitAdapter.externalDebitConsent, customer: externalDebitAdapter.customer]).list()

        validatedDomain = validatePeriodicLimit(externalDebitList, externalDebitAdapter, externalAutomaticDebitConsentPeriodicLimits)
        if (validatedDomain.hasErrors()) return validatedDomain

        BigDecimal totalAmount = externalDebitList ? externalDebitList.value.sum() + externalDebitAdapter.value : externalDebitAdapter.value
        if (externalAutomaticDebitConsentInfo.totalAllowedAmount && (totalAmount > externalAutomaticDebitConsentInfo.totalAllowedAmount)) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.totalAllowedAmountExceeded.totalLimitValueExceeded", "O valor total definido no consentimento foi excedido.")
            return validatedDomain
        }

        return validatedDomain
    }

    private ExternalDebit validatePeriodicLimit(List<ExternalDebit> externalDebitList, ExternalDebitAdapter externalDebitAdapter, ExternalAutomaticDebitConsentPeriodicLimits consentPeriodicLimits) {
        ExternalDebit validatedDomain = new ExternalDebit()

        if (consentPeriodicLimits.dailyTransactionLimitQuantity || consentPeriodicLimits.dailyTransactionLimitValue) {
            List<ExternalDebit> externalDebitPeriodicDailyList = externalDebitList.findAll { CustomDateUtils.isToday(it.dateCreated) }

            if (externalDebitPeriodicDailyList) {
                validatedDomain = validateLimit(externalDebitPeriodicDailyList, externalDebitAdapter, consentPeriodicLimits.dailyTransactionLimitQuantity, consentPeriodicLimits.dailyTransactionLimitValue)
            }

            if (validatedDomain.hasErrors()) return validatedDomain
        }

        if (consentPeriodicLimits.weeklyTransactionLimitQuantity || consentPeriodicLimits.weeklyTransactionLimitValue) {
            Date firstDayOfPeriodicWeekly = CustomDateUtils.getFirstDayOfCurrentWeek().clearTime()
            Date lastDayOfPeriodicWeekly = CustomDateUtils.getFirstDayOfNextWeek().clearTime()

            List<ExternalDebit> externalDebitPeriodicWeeklyList = ExternalDebit.query(["dateCreated[ge]": firstDayOfPeriodicWeekly, "dateCreated[lt]": lastDayOfPeriodicWeekly, consent: externalDebitAdapter.externalDebitConsent, ignoreCustomer: true]).list(readOnly: true)

            if (externalDebitPeriodicWeeklyList) {
                validatedDomain = validateLimit(externalDebitPeriodicWeeklyList, externalDebitAdapter, consentPeriodicLimits.weeklyTransactionLimitQuantity, consentPeriodicLimits.weeklyTransactionLimitValue)
            }

            if (validatedDomain.hasErrors()) return validatedDomain
        }

        if (consentPeriodicLimits.monthlyTransactionLimitQuantity || consentPeriodicLimits.monthlyTransactionLimitValue) {
            Date firstDayOfPeriodicMonthly = CustomDateUtils.getFirstDayOfMonth(new Date()).clearTime()
            Date lastDayOfPeriodicMonthly = CustomDateUtils.getFirstDayOfNextMonth().clearTime()

            List<ExternalDebit> externalDebitPeriodicMonthList = ExternalDebit.query(["dateCreated[ge]": firstDayOfPeriodicMonthly, "dateCreated[lt]": lastDayOfPeriodicMonthly, consent: externalDebitAdapter.externalDebitConsent, ignoreCustomer: true]).list(readOnly: true)

            if (externalDebitPeriodicMonthList) {
                validatedDomain = validateLimit(externalDebitPeriodicMonthList, externalDebitAdapter, consentPeriodicLimits.monthlyTransactionLimitQuantity, consentPeriodicLimits.monthlyTransactionLimitValue)
            }

            if (validatedDomain.hasErrors()) return validatedDomain
        }

        if (consentPeriodicLimits.yearlyTransactionLimitQuantity || consentPeriodicLimits.yearlyTransactionLimitValue) {
            Date firstDayOfPeriodicYearly = CustomDateUtils.getFirstDayOfYear(new Date()).clearTime()
            Date lastDayOfPeriodicYearly = CustomDateUtils.getFirstDayOfNextYear().clearTime()

            List<ExternalDebit> externalDebitPeriodicYearlyList = ExternalDebit.query(["dateCreated[ge]": firstDayOfPeriodicYearly, "dateCreated[lt]": lastDayOfPeriodicYearly, consent: externalDebitAdapter.externalDebitConsent, ignoreCustomer: true]).list(readOnly: true)

            if (externalDebitPeriodicYearlyList) {
                validatedDomain = validateLimit(externalDebitPeriodicYearlyList, externalDebitAdapter, consentPeriodicLimits.yearlyTransactionLimitQuantity, consentPeriodicLimits.yearlyTransactionLimitValue)
            }

            if (validatedDomain.hasErrors()) return validatedDomain
        }

        return validatedDomain
    }

    private ExternalDebit validateLimit(List<ExternalDebit> externalDebitPeriodicList, ExternalDebitAdapter externalDebitAdapter, Integer transactionLimitQuantity, BigDecimal transactionLimitValue) {
        ExternalDebit validatedDomain = new ExternalDebit()

        if (transactionLimitQuantity && !isValidPeriodicQuantity(externalDebitPeriodicList, transactionLimitQuantity)) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.totalAllowedQuantityExceeded.totalLimitQuantityExceeded", "a quantidade de transação definida no consentimento foi excedida.")
            return validatedDomain
        }

        if (transactionLimitValue && !isValidPeriodicValue(externalDebitPeriodicList, transactionLimitValue, externalDebitAdapter)) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.totalAllowedAmountExceeded.totalLimitValueExceeded", "O valor definido no consentimento foi excedido.")
            return validatedDomain
        }

        return validatedDomain
    }

    private Boolean isValidPeriodicQuantity(List<ExternalDebit> externalDebitPeriodicList, Integer transactionLimitQuantity) {
        Integer transactionsCarriedOut = externalDebitPeriodicList.size()
        Integer transactionCurrent = 1
        Integer transactionQuantity = transactionsCarriedOut + transactionCurrent

        return transactionQuantity <= transactionLimitQuantity
    }

    private Boolean isValidPeriodicValue(List<ExternalDebit> externalDebitPeriodicList, BigDecimal transactionLimitValue, ExternalDebitAdapter externalDebitAdapter) {
        BigDecimal transactionValue = externalDebitPeriodicList.value.sum() + externalDebitAdapter.value

        return transactionValue <= transactionLimitValue
    }
}
