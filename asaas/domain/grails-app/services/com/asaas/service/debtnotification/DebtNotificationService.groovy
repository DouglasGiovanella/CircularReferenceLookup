package com.asaas.service.debtnotification

import com.asaas.customer.CustomerParameterName
import com.asaas.debtnotification.DebtNotificationStatus
import com.asaas.debtrecovery.DebtRecoveryStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.domain.debtnotification.DebtNotification
import com.asaas.domain.debtnotification.DebtNotificationItem
import com.asaas.domain.debtrecovery.DebtRecovery
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.PaymentDunning
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class DebtNotificationService {

    def customerMessageService
    def debtRecoveryService
    def debtRecoveryHistoryService
    def smsSenderService

    public DebtNotification save(Customer customer) {
        BusinessValidation businessValidation = validateSave(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        DebtNotification debtNotification = new DebtNotification([customer: customer])
        debtNotification.status = DebtNotificationStatus.IN_PROGRESS
        debtNotification.save(flush: true, failOnError: true)

        return debtNotification
    }

    public void initiateRecoveryForNotifiedCustomers() {
        Date notificationStartDate = CustomDateUtils.dayBeforeYesterday()
        final Integer daysLimit = -2
        final Integer maxItemsPerCycle = 500

        Map search = [:]
        search.column = "id"
        search."dateCreated[ge]" = CustomDateUtils.sumDays(notificationStartDate, daysLimit)
        search."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(notificationStartDate)
        search.status = DebtNotificationStatus.LIMIT_NOTIFICATIONS_NUMBER_REACHED
        search."debtRecoveryStatusList[notExists]" = DebtRecoveryStatus.activeStatusList()
        search.disableSort = true
        search.limit = maxItemsPerCycle

        List<Long> notificationIdList = DebtNotification.query(search).list()

        Utils.forEachWithFlushSession(notificationIdList, 50, { Long notificationId ->
            Utils.withNewTransactionAndRollbackOnError({
                DebtNotification notification = DebtNotification.get(notificationId)

                if (canBeCancelled(notification)) {
                    updateAsCancelled(notification)
                    return
                }

                BigDecimal currentAbsoluteBalance = FinancialTransaction.getCustomerBalance(notification.customer).abs()

                Map params = [:]
                params.value = currentAbsoluteBalance
                params.installmentDueDate = CustomDateUtils.addBusinessDays(new Date().clearTime(), DebtRecovery.DEFAULT_BUSINESS_DAY_INTERVAL_TO_DUE_DATE)
                if (currentAbsoluteBalance >= PaymentDunning.MINIMUM_BALANCE_TO_DUNNING.abs()) params.paymentDunningEnabled = true

                initiateRecovery(notification, params)
            }, [logErrorMessage: "DebtNotificationService.initiateRecoveryForNotifiedCustomers >> Erro ao iniciar a recuperação de débito para a DebtNotification ID: [${notificationId}]"])
        })
    }

    public DebtNotification initiateRecovery(DebtNotification debtNotification, Map params) {
        BigDecimal value = Utils.toBigDecimal(params.value)
        validateInitiateRecovery(debtNotification, value)

        debtNotification.debtRecovery = debtRecoveryService.save(debtNotification.customer, value, params)
        debtNotification.status = DebtNotificationStatus.SENT_TO_RECOVERY
        debtNotification.save(failOnError: true)

        debtRecoveryHistoryService.saveForDebtRecoveryChanged(debtNotification.debtRecovery, "Recuperação iniciada")

        return debtNotification
    }

    public Boolean canBeSentToRecovery(DebtNotification debtNotification) {
        if (!DebtNotificationStatus.getRecoverableList().contains(debtNotification.status)) return false
        if (debtNotification.debtRecovery) return false

        Boolean hasPositiveBalance = (FinancialTransaction.getCustomerBalance(debtNotification.customer) >= 0)
        if (hasPositiveBalance) return false

        return true
    }

    public void processInProgressDebtNotifications() {
        List<Long> debtNotificationIdInProgressList = DebtNotification.inProgress([column: "id"]).list()

        for (Long debtNotificationId : debtNotificationIdInProgressList) {
            Boolean notified = false

            Utils.withNewTransactionAndRollbackOnError({
                notify(DebtNotification.get(debtNotificationId))
                notified = true
            }, [logErrorMessage: "DebtNotificationService -> Erro ao processar DebtNotification. [id: ${debtNotificationId}]"])

            if (notified) continue

            Utils.withNewTransactionAndRollbackOnError({
                DebtNotification debtNotificationWithError = DebtNotification.get(debtNotificationId)
                debtNotificationWithError.status = DebtNotificationStatus.ERROR
                debtNotificationWithError.save(failOnError: true)
            }, [logErrorMessage: "DebtNotificationService -> Erro ao atualizar o DebtNotification para a situação ERROR. [id: ${debtNotificationId}]"])
        }
    }

    public void cancelDebtNotificationsIfNecessary() {
        List<Long> debtNotificationIdInProgressList = DebtNotification.query([column: "id", "status[in]": DebtNotificationStatus.getRecoverableList()]).list()

        Utils.forEachWithFlushSession(debtNotificationIdInProgressList, 50, { Long debtNotificationId ->
            Utils.withNewTransactionAndRollbackOnError({
                DebtNotification debtNotification = DebtNotification.get(debtNotificationId)

                if (canBeCancelled(debtNotification)) {
                    updateAsCancelled(debtNotification)
                    return
                }

                if (!hasRemainingNotificationsToSend(debtNotification)) {
                    debtNotification.status = DebtNotificationStatus.LIMIT_NOTIFICATIONS_NUMBER_REACHED
                    debtNotification.save(failOnError: true)
                }
            }, [logErrorMessage: "DebtNotificationService.cancelDebtNotificationsIfNecessary >> Erro ao verificar se a debtNotification ainda é necessária. [id: ${debtNotificationId}]"])
        })
    }

    public Boolean hasRemainingNotificationsToSend(DebtNotification debtNotification) {
        return DebtNotification.MAXIMUM_NUMBER_OF_NOTIFICATIONS_TO_SEND - DebtNotificationItem.query([exists: true, debtNotification: debtNotification]).count() > 0
    }

    public BusinessValidation validateSave(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean hasDebtNotificationInProgress = DebtNotification.inProgress([exists: true, customer: customer]).get().asBoolean()
        if (hasDebtNotificationInProgress) {
            businessValidation.addError("debtNotification.saveDebtNotification.alreadyHasDebtNotificationInProgress")
            return businessValidation
        }

        if (CustomerDebtAppropriation.active([customer: customer, exists: true]).get().asBoolean()) {
            businessValidation.addError("customerDebtAppropriation.appropriatedCustomer.message")
            return businessValidation
        }

        Boolean debtNotificationIsDisabled = CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_DEBT_NOTIFICATION)
        if (debtNotificationIsDisabled) {
            businessValidation.addError("debtNotification.disabled")
            return businessValidation
        }

        return debtRecoveryService.validateInitiateRecovery(customer)
    }

    private void notify(DebtNotification debtNotification) {
        if (canBeCancelled(debtNotification)) {
            updateAsCancelled(debtNotification)
            return
        }

        if (!canNotify(debtNotification)) return

        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(debtNotification.customer)

        DebtNotificationItem debtNotificationItem = new DebtNotificationItem()
        debtNotificationItem.debtNotification = debtNotification
        debtNotificationItem.value = (currentBalance * -1)

        Boolean hasBeenNotified = DebtNotificationItem.query([exists: true, debtNotification: debtNotification]).get().asBoolean()
        if (!hasBeenNotified) {
            customerMessageService.sendDebtNotificationNegativeBalanceAlert(debtNotificationItem.debtNotification.customer, currentBalance)

            debtNotificationItem.emailSent = true
            debtNotificationItem.save(failOnError: true)
            return
        }

        String smsMessage = "Olá! Sua conta Asaas está com saldo negativo. Entre em contato com seu gerente de contas, ele vai te ajudar a regularizar essa situação."
        Boolean smsSent = smsSenderService.send(smsMessage, debtNotificationItem.debtNotification.customer.mobilePhone, false, [:])

        debtNotificationItem.smsSent = smsSent
        debtNotificationItem.save(failOnError: true)

        if (!hasRemainingNotificationsToSend(debtNotification)) {
            debtNotification.status = DebtNotificationStatus.LIMIT_NOTIFICATIONS_NUMBER_REACHED
            debtNotification.save(failOnError: true)
        }
    }

    private Boolean canNotify(DebtNotification debtNotification) {
        if (Holiday.isHoliday(new Date())) return false

        Boolean hasNotificationSentToday = DebtNotificationItem.query([exists: true, debtNotification: debtNotification, "dateCreated[ge]": new Date(), "dateCreated[le]": new Date()]).get().asBoolean()
        if (hasNotificationSentToday) return false

        return true
    }

    private void validateInitiateRecovery(DebtNotification debtNotification, BigDecimal value) {
        if (!value) throw new BusinessException("É necessário informar um valor para a recuperação.")

        if (!canBeSentToRecovery(debtNotification)) throw new BusinessException("Essa notificação não permite iniciar a recuperação.")
    }

    private Boolean canBeCancelled(DebtNotification debtNotification) {
        BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(debtNotification.customer)
        if (currentBalance > DebtRecovery.MINIMUM_NEGATIVE_VALUE_TO_START_RECOVERY) return true

        Boolean debtNotificationIsDisabled = CustomerParameter.getValue(debtNotification.customer, CustomerParameterName.DISABLE_DEBT_NOTIFICATION)
        if (debtNotificationIsDisabled) return true

        return false
    }

    private void updateAsCancelled(DebtNotification debtNotification) {
        debtNotification.status = DebtNotificationStatus.CANCELLED
        debtNotification.save(failOnError: true)
    }
}
