package com.asaas.service.pix

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixAsyncInstantPaymentAccountBalanceValidation
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.RecurringCheckoutSchedulePixItem
import com.asaas.integration.pix.enums.policy.PolicyType
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.pix.RecurringCheckoutSchedulePixItemStatus
import com.asaas.recurringCheckoutSchedule.repository.RecurringCheckoutSchedulePixItemRepository
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class PixHealthCheckService {

    def hermesHealthCheckManagerService
    def hermesQrCodeManagerService

    public Boolean hermesHealthCheck() {
        return hermesHealthCheckManagerService.healthCheck()
    }

    public Boolean hermesCheckCreditTransactionQueueDelay() {
        return hermesHealthCheckManagerService.checkCreditTransactionQueueDelay()
    }

    public Boolean hermesCheckCreditTransactionQueueStopped() {
        return hermesHealthCheckManagerService.checkCreditTransactionQueueStopped()
    }

    public Boolean hermesCheckRequestedTransactionQueueDelay() {
        return hermesHealthCheckManagerService.checkRequestedTransactionQueueDelay()
    }

    public Boolean hermesCheckQrCodeWebhookQueueDelay() {
        return hermesHealthCheckManagerService.checkQrCodeWebhookQueueDelay()
    }

    public Boolean hermesCheckQrCodeWebhookQueueStopped() {
        return hermesHealthCheckManagerService.checkQrCodeWebhookQueueStopped()
    }

    public Boolean checkTransactionValidationQueueDelay() {
        return hermesHealthCheckManagerService.checkTransactionValidationQueueDelay()
    }

    public Boolean checkTransactionValidationQueueStopped() {
        return hermesHealthCheckManagerService.checkTransactionValidationQueueStopped()
    }

    public Boolean hermesCheckParticipantAntiScanPolicy() {
        return hermesHealthCheckManagerService.checkPolicy(PolicyType.ENTRIES_READ_PARTICIPANT_ANTISCAN)
    }

    public Boolean hermesCheckAccountConfirmedFraudQueueDelay() {
        return hermesHealthCheckManagerService.checkAccountConfirmedFraudQueueDelay()
    }

    public Boolean hermesCheckRefundRequestReversalQueueDelay() {
        return hermesHealthCheckManagerService.checkRefundRequestReversalQueueDelay()
    }

    public Boolean hermesCheckQrCodeSaveIsAvailable() {
        try {
            final Long paymentIdTest = 128192152 //cobrança teste na conta do @Chagas
            Payment payment = Payment.get(paymentIdTest)

            final BigDecimal lowValue = 5
            final BigDecimal highValue = 9.99
            BigDecimal randomValue = BigDecimalUtils.random(lowValue, highValue)

            randomValue += (randomValue == payment.value) ? 0.01 : 0

            payment.value = randomValue
            payment.save(failOnError: true)

            Map response = hermesQrCodeManagerService.createDynamicQrCode(payment)
            return response.success
        } catch (Exception ignored) {
            return false
        }
    }

    public Boolean asaasCheckDebitTransactionQueueDelay() {
        final Integer toleranceSeconds = 120
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumSeconds(instant, -1 * toleranceSeconds)

        Boolean delayDetected = PixTransaction.awaitingRequest(["lastUpdated[lt]": toleranceInstant, "type": PixTransactionType.DEBIT, "scheduledDate[isNull]": true, order: "asc", exists: true]).get().asBoolean()

        if (delayDetected) return false
        return true
    }

    public Boolean asaasCheckCreditRefundQueueDelay() {
        final Integer toleranceSeconds = 120
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumSeconds(instant, -1 * toleranceSeconds)

        Boolean delayDetected = PixTransaction.creditRefund(["lastUpdated[lt]": toleranceInstant, status: PixTransactionStatus.AWAITING_REQUEST, order: "asc", exists: true]).get().asBoolean()
        if (delayDetected) return false
        return true
    }

    public Boolean asaasCheckDebitTransactionQueueStopped() {
        final Integer toleranceMinutes = 30
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, -1 * toleranceMinutes)

        Boolean isStopped = PixTransaction.awaitingRequest(["lastUpdated[lt]": toleranceInstant, "type": PixTransactionType.DEBIT, "scheduledDate[isNull]": true, order: "asc", exists: true]).get().asBoolean()

        if (isStopped) return false
        return true
    }

    public Boolean asaasCheckUnprocessedScheduledDebitTransaction() {
        Boolean hasUnprocessedScheduledTransaction = PixTransaction.debit([status: PixTransactionStatus.SCHEDULED, "scheduledDate[lt]": new Date().clearTime(), exists: true]).get().asBoolean()

        if (hasUnprocessedScheduledTransaction) return false
        return true
    }

    public Boolean asaasAwaitingBalanceValidationDebitTransactionQueueDelay() {
        final Integer toleranceSeconds = 120
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumSeconds(instant, -1 * toleranceSeconds)

        return checkAwaitingBalanceValidationQueue(toleranceInstant)
    }

    public Boolean asaasAwaitingBalanceValidationDebitTransactionQueueStopped() {
        final Integer toleranceSeconds = 200
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumSeconds(instant, -1 * toleranceSeconds)

        return checkAwaitingBalanceValidationQueue(toleranceInstant)
    }

    public Boolean asaasCheckDebitTransactionAwaitingInstantPaymentAccountBalanceForLongPeriod() {
        final Integer toleranceMinutes = 15
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, -1 * toleranceMinutes)

        Boolean hasBlockedTransactionForLongPeriod = PixAsyncInstantPaymentAccountBalanceValidation.query([exists: true, "dateCreated[lt]": toleranceInstant, pixTransactionStatus: PixTransactionStatus.AWAITING_INSTANT_PAYMENT_ACCOUNT_BALANCE]).get().asBoolean()
        if (hasBlockedTransactionForLongPeriod) return false

        return true
    }

    public Boolean checkUnprocessedRecurringItemDebitTransaction() {
        Integer currentHour = CustomDateUtils.getInstanceOfCalendar().get(Calendar.HOUR)
        Boolean isBeforeExecutionEndTime = currentHour < RecurringCheckoutSchedulePixItem.DAILY_EXECUTION_END_HOUR
        if (isBeforeExecutionEndTime) return true

        Date scheduledDateFilter = CustomDateUtils.tomorrow()

        Boolean hasUnprocessedRecurringItem = RecurringCheckoutSchedulePixItemRepository.query([
            status: RecurringCheckoutSchedulePixItemStatus.PENDING,
            scheduledDate: scheduledDateFilter
        ]).disableRequiredFilters().exists()

        if (hasUnprocessedRecurringItem) return false
        return true
    }

    private Boolean checkAwaitingBalanceValidationQueue(Date toleranceInstant) {
        final Integer max = 10
        List<Long> delayedCustomerIdList = PixTransaction.awaitingBalanceValidation([
            "type[in]": [PixTransactionType.DEBIT, PixTransactionType.CREDIT_REFUND],
            "lastUpdated[lt]": toleranceInstant,
            "scheduledDate[isNull]": true,
            distinct: "customer.id",
            disableSort: true
        ]).list(max: max)

        Boolean delayDetected = false
        if (delayedCustomerIdList) {
            for (Long customerId : delayedCustomerIdList) {
                BigDecimal balance = FinancialTransaction.getCustomerBalance(customerId)
                BigDecimal requiredValue = PixTransaction.sumValueAbs(["type[in]": [PixTransactionType.DEBIT, PixTransactionType.CREDIT_REFUND], status: PixTransactionStatus.AWAITING_BALANCE_VALIDATION, "customerId": customerId]).get()
                if (balance >= requiredValue) {
                    delayDetected = true
                    return
                }

                AsaasLogger.info("PixHealthCheckService.checkAwaitingBalanceValidationQueue >> Saldo insuficiente para processar transações [customerId: ${customerId}, balance: ${balance}, requiredValue: ${requiredValue}]")
            }
        }

        if (delayDetected) return false
        return true
    }
}
