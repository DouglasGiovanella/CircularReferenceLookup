package com.asaas.service.debtrecovery

import com.asaas.asyncaction.AsyncActionType
import com.asaas.debtrecovery.DebtRecoveryNegotiationPaymentStatus
import com.asaas.debtrecovery.DebtRecoveryNegotiationStatus
import com.asaas.debtrecovery.DebtRecoveryNegotiationPaymentType
import com.asaas.debtrecovery.DebtRecoveryStatus
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiation
import com.asaas.domain.debtrecovery.DebtRecoveryNegotiationPayment
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.user.UserUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class DebtRecoveryNegotiationPaymentService {

    def asyncActionService
    def creditBureauDunningService
    def debtRecoveryHistoryService
    def financialTransactionService
    def paymentDunningCustomerAccountInfoService
    def paymentDunningService
    def paymentService
    def internalTransferService

    public DebtRecoveryNegotiationPayment save(DebtRecoveryNegotiation negotiation, Payment payment, DebtRecoveryNegotiationPaymentType type, BigDecimal chargeValue, Map params) {

        DebtRecoveryNegotiationPayment negotiationPayment = new DebtRecoveryNegotiationPayment()
        negotiationPayment.debtRecovery = negotiation.debtRecovery
        negotiationPayment.debtRecoveryNegotiation = negotiation
        negotiationPayment.payment = payment
        negotiationPayment.type = type
        negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.AWAITING_PAYMENT
        negotiationPayment.chargeValue = chargeValue
        negotiationPayment.value = payment.value
        negotiationPayment.netValue = (payment.value - chargeValue)
        negotiationPayment.save(flush: true, failOnError: true)

        if (type.isMaster() && negotiation.debtRecovery.paymentDunningEnabled) saveDunning(negotiationPayment, params)

        return negotiationPayment
    }

    public void saveDunning(DebtRecoveryNegotiationPayment negotiationPayment, Map params) {
        if (!negotiationPayment.type.isMaster()) throw new BusinessException("Somente é possível enviar cobranças master para órgãos de proteção ao crédito.")

        Map saveDunningParams = params.clone()
        saveDunningParams.customerAccount = [primaryPhone: negotiationPayment.debtRecovery.debtorCustomer.mobilePhone]
        saveDunningParams.customerAccount << negotiationPayment.payment.customerAccount.properties['address', 'province', 'city', 'state']
        saveDunningParams.customerAccount << paymentDunningCustomerAccountInfoService.getLatestCustomerAccountInfo(negotiationPayment.payment.customerAccount)

        PaymentDunning paymentDunning = paymentDunningService.save(negotiationPayment.payment.provider, UserUtils.getCurrentUser(), negotiationPayment.payment.id, saveDunningParams)
        if (paymentDunning.hasErrors()) throw new ValidationException(null, paymentDunning.errors)

        negotiationPayment.paymentDunning = paymentDunning
        negotiationPayment.save(failOnError: true)

        debtRecoveryHistoryService.saveForDebtRecoveryChanged(negotiationPayment.debtRecovery, "Negativação enviada ao Serasa")
    }

    public void payDebtIfNecessary(Payment payment) {
        if (!payment.provider.isAsaasDebtRecoveryProvider()) return

        DebtRecoveryNegotiationPayment negotiationPayment = DebtRecoveryNegotiationPayment.receivable([payment: payment, includeDeleted: true]).get()
        if (!negotiationPayment) return

        if (negotiationPayment.payment.interestValue) {
            negotiationPayment.chargeValue += negotiationPayment.payment.interestValue
            negotiationPayment.debtRecoveryNegotiation.chargeValue += negotiationPayment.payment.interestValue
        }

        negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.PAID
        negotiationPayment.save(flush: true, failOnError: true)
    }

    public void cancelMasterPaymentIfNecessary(DebtRecoveryNegotiation debtRecoveryNegotiation) {
        DebtRecoveryNegotiationPayment masterNegotiationPayment = DebtRecoveryNegotiationPayment.cancellable([debtRecoveryNegotiation: debtRecoveryNegotiation, type: DebtRecoveryNegotiationPaymentType.MASTER]).get()
        if (!masterNegotiationPayment) return

        if (!masterNegotiationPayment.payment.canDelete()) {
            PaymentDunning paymentDunning = masterNegotiationPayment.payment.getDunning()
            if (paymentDunning) {
                paymentDunning = creditBureauDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.SPECIAL_SITUATION)
                if (paymentDunning.hasErrors()) throw new BusinessException(Utils.getMessageProperty(paymentDunning.errors.allErrors.first()))
                if (paymentDunning.status.isAwaitingCancellation()) paymentDunningService.setPaymentAsOverdueIfNecessary(paymentDunning)
            }
        }

        cancel(masterNegotiationPayment)
    }

    public void cancelInstallmentPaymentsIfNecessary(DebtRecoveryNegotiation debtRecoveryNegotiation) {
        List<DebtRecoveryNegotiationPayment> cancellableNegotiationPaymentList = DebtRecoveryNegotiationPayment.cancellable([debtRecoveryNegotiation: debtRecoveryNegotiation, "type[in]": DebtRecoveryNegotiationPaymentType.getDealList()]).list()
        if (!cancellableNegotiationPaymentList) return

        for (DebtRecoveryNegotiationPayment negotiationPayment : cancellableNegotiationPaymentList) {
            cancel(negotiationPayment)
        }
    }

    public void deleteRemainingInstallmentPayments(DebtRecoveryNegotiation debtRecoveryNegotiation) {
        List<DebtRecoveryNegotiationPayment> negotiationPaymentList = DebtRecoveryNegotiationPayment.cancellable([debtRecoveryNegotiation: debtRecoveryNegotiation, "type[in]": DebtRecoveryNegotiationPaymentType.getDealList()]).list()

        for (DebtRecoveryNegotiationPayment negotiationPayment : negotiationPaymentList) {
            cancel(negotiationPayment)

            negotiationPayment.deleted = true
            negotiationPayment.save(failOnError: true)
        }
    }

    private void cancel(DebtRecoveryNegotiationPayment debtRecoveryPayment) {
        if (!DebtRecoveryNegotiationPaymentStatus.getCancellableList().contains(debtRecoveryPayment.status)) throw new BusinessException("A negociação não pode ser cancelada.")

        debtRecoveryPayment.status = DebtRecoveryNegotiationPaymentStatus.CANCELLED
        debtRecoveryPayment.save(flush: true, failOnError: true)

        paymentService.delete(debtRecoveryPayment.payment, false)
    }

    public void setAsOverdueIfNecessary(Payment payment) {
        if (!payment.isOverdue()) return
        if (!payment.provider.isAsaasDebtRecoveryProvider()) return

        DebtRecoveryNegotiationPayment negotiationPayment = DebtRecoveryNegotiationPayment.receivable([payment: payment]).get()
        if (!negotiationPayment) return

        negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.OVERDUE
        negotiationPayment.save(failOnError: true)
    }

    public void processOverdueDebts() {
        List<Long> overdueNegotiationPaymentIdList = DebtRecoveryNegotiationPayment.overdue([column: "id", includeDeleted: true,
                                                                                             "debtRecoveryNegotiation.status": DebtRecoveryNegotiationStatus.IN_PROGRESS]).list()

        for (Long negotiationPaymentId : overdueNegotiationPaymentIdList) {
            Boolean updated = false

            Utils.withNewTransactionAndRollbackOnError({
                DebtRecoveryNegotiationPayment negotiationPayment = DebtRecoveryNegotiationPayment.get(negotiationPaymentId)

                if (negotiationPayment.debtRecoveryNegotiation.status.isInProgress()) {
                    negotiationPayment.debtRecoveryNegotiation.status = DebtRecoveryNegotiationStatus.FAILED
                    negotiationPayment.debtRecoveryNegotiation.save(failOnError: true)
                }

                if (negotiationPayment.debtRecovery.status.isInNegotiation()) {
                    negotiationPayment.debtRecovery.status = DebtRecoveryStatus.NEGOTIATION_FAILED
                    negotiationPayment.debtRecovery.save(failOnError: true)
                }

                updated = true
            }, [onError: { Exception exception ->
                AsaasLogger.error("DebtRecoveryPaymentService.processOverdueDebts >> Erro ao marcar negociação como falhada. [id: ${negotiationPaymentId}]", exception)
            }])

            if (updated) continue

            Utils.withNewTransactionAndRollbackOnError({
                DebtRecoveryNegotiationPayment negotiationPayment = DebtRecoveryNegotiationPayment.get(negotiationPaymentId)
                negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.ERROR
                negotiationPayment.save(failOnError: true)
            }, [onError: { Exception exception ->
                AsaasLogger.error("DebtRecoveryPaymentService.processOverdueDebts >> Erro ao atualizar DebtNotification para a situação ERROR. [id: ${negotiationPaymentId}]", exception)
            }])
        }
    }

    public void processPaidDebts() {
        List<Long> awaitingTransferNegotiationPaymentIdList = DebtRecoveryNegotiationPayment.paid([column: "id", includeDeleted: true]).list()

        for (Long negotiationPaymentId : awaitingTransferNegotiationPaymentIdList) {
            Boolean transferred = false

            Utils.withNewTransactionAndRollbackOnError({
                DebtRecoveryNegotiationPayment negotiationPayment = DebtRecoveryNegotiationPayment.get(negotiationPaymentId)
                transferPaidDebtValueToCustomer(negotiationPayment)
                transferred = true

                debtRecoveryHistoryService.saveForDebtRecoveryChanged(negotiationPayment.debtRecovery, "Recuperação finalizada")
            }, [onError: { Exception exception ->
                AsaasLogger.error("DebtRecoveryPaymentService.processPaidDebts >> Erro ao transferir recuperação para o saldo do cliente. [id: ${negotiationPaymentId}]", exception)
            }])

            if (transferred) continue

            Utils.withNewTransactionAndRollbackOnError({
                DebtRecoveryNegotiationPayment negotiationPayment = DebtRecoveryNegotiationPayment.get(negotiationPaymentId)
                negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.ERROR
                negotiationPayment.save(failOnError: true)
            }, [onError: { Exception exception ->
                AsaasLogger.error("DebtRecoveryPaymentService.processPaidDebts >> Erro ao atualizar DebtNotification para a situação ERROR. [id: ${negotiationPaymentId}]", exception)
            }])
        }
    }

    private void transferPaidDebtValueToCustomer(DebtRecoveryNegotiationPayment negotiationPayment) {
        validateTransferPaidDebtValueToCustomer(negotiationPayment)

        InternalTransfer internalTransfer = internalTransferService.saveDebtRecoveryNegotiationInternalTransfer(negotiationPayment)
        if (negotiationPayment.chargeValue) financialTransactionService.saveDebtRecoveryNegotiationPaymentFee(negotiationPayment)

        if (internalTransfer.hasErrors()) {
            Utils.withNewTransactionAndRollbackOnError({
                negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.ERROR
                negotiationPayment.save(failOnError: true)
            })

            throw new ValidationException("DebtRecoveryPaymentService.transferPaidDebtValueToCustomer >> Erro ao criar transferência de recuperação para o saldo do cliente. [id: ${negotiationPayment.id}", internalTransfer.errors)
        }
        negotiationPayment.internalTransfer = internalTransfer

        negotiationPayment.status = DebtRecoveryNegotiationPaymentStatus.USED_TO_SETTLE_DEBT
        negotiationPayment.save(flush: true, failOnError: true)

        if (DebtRecoveryNegotiationPaymentType.getDealList().contains(negotiationPayment.type)) cancelMasterPaymentIfNecessary(negotiationPayment.debtRecoveryNegotiation)
        if (negotiationPayment.type.isMaster()) cancelInstallmentPaymentsIfNecessary(negotiationPayment.debtRecoveryNegotiation)

        updateDebtRecoveryAfterTransferDebtIfNecessary(negotiationPayment.debtRecoveryNegotiation)
    }

    private void validateTransferPaidDebtValueToCustomer(DebtRecoveryNegotiationPayment debtRecoveryPayment) {
        if (!debtRecoveryPayment.payment.provider.isAsaasDebtRecoveryProvider()) throw new BusinessException("A cobrança ${debtRecoveryPayment.payment.id} é inválida.")

        if (!debtRecoveryPayment.payment.isReceived() && !debtRecoveryPayment.payment.isDunningReceived()) throw new BusinessException("A cobrança ${debtRecoveryPayment.payment.id} não foi recebida.")

        if (!debtRecoveryPayment.status.isPaid()) throw new BusinessException("A cobrança da negociação ${debtRecoveryPayment.id} não está marcada como paga.")

        if (debtRecoveryPayment.internalTransfer.asBoolean()) throw new BusinessException("Uma transferência já foi efetuada para cobrança da negociação ${debtRecoveryPayment.id}.")
    }

    private void updateDebtRecoveryAfterTransferDebtIfNecessary(DebtRecoveryNegotiation debtRecoveryNegotiation) {
        if (debtRecoveryNegotiation.status.isFailed()) {
            Boolean hasAnotherOverdueNegotiationPayment = DebtRecoveryNegotiationPayment.overdue([includeDeleted: true, "debtRecoveryNegotiation": debtRecoveryNegotiation, exists: true]).get()

            if (!hasAnotherOverdueNegotiationPayment) {
                debtRecoveryNegotiation.status = DebtRecoveryNegotiationStatus.IN_PROGRESS
                debtRecoveryNegotiation.save(failOnError: true)

                debtRecoveryNegotiation.debtRecovery.status = DebtRecoveryStatus.IN_NEGOTIATION
                debtRecoveryNegotiation.debtRecovery.save(failOnError: true)
            }
        }

        if (debtRecoveryNegotiation.getRecoveredValue() == debtRecoveryNegotiation.debtRecovery.value) {
            debtRecoveryNegotiation.status = DebtRecoveryNegotiationStatus.PAID
            debtRecoveryNegotiation.save(failOnError: true)

            debtRecoveryNegotiation.debtRecovery.status = DebtRecoveryStatus.PAID
            debtRecoveryNegotiation.debtRecovery.save(failOnError: true)

            asyncActionService.save(AsyncActionType.CANCEL_DEBT_RECOVERY_PHONE_CALL, null, [customerId: debtRecoveryNegotiation.debtorCustomer.id], [allowDuplicatePendingWithSameParameters: true])
        }
    }
}
