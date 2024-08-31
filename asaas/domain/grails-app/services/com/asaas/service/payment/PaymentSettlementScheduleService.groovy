package com.asaas.service.payment

import com.asaas.bankslip.worker.settlement.PaymentBankSlipSettlementScheduleWorkerConfigVO
import com.asaas.bankslip.worker.settlement.PaymentBankSlipSettlementScheduleWorkerItemVO
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.domain.payment.PaymentSettlementSchedule
import com.asaas.payment.PaymentSettlementScheduleStatus
import com.asaas.payment.PaymentStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentSettlementScheduleService {

    def paymentConfirmService

    public PaymentSettlementSchedule save(Payment payment, Date settlementDate) {
        validateSave(payment, settlementDate)

        PaymentSettlementSchedule paymentSettlementSchedule = new PaymentSettlementSchedule()
        paymentSettlementSchedule.payment = payment
        paymentSettlementSchedule.settlementDate = settlementDate
        paymentSettlementSchedule.status = PaymentSettlementScheduleStatus.PENDING
        paymentSettlementSchedule.save(failOnError: true)

        return paymentSettlementSchedule
    }

    public PaymentSettlementSchedule saveFromPaymentConfirmRequest(PaymentConfirmRequest paymentConfirmRequest) {
        Date settlementDate = paymentConfirmRequest.group.settlementStartDate ?: new Date().clearTime()

        return save(paymentConfirmRequest.payment, settlementDate)
    }

    public void processPendingItems(List<Long> paymentSettlementScheduleIdList) {
        final Integer flushEvery = 25

        List<Long> processedItemsIdList = []
        List<Long> errorItemsIdList = []

        Utils.forEachWithFlushSession(paymentSettlementScheduleIdList, flushEvery, { Long paymentSettlementScheduleId ->
            Utils.withNewTransactionAndRollbackOnError({
                PaymentSettlementSchedule paymentSettlementSchedule = PaymentSettlementSchedule.get(paymentSettlementScheduleId)

                if (shouldBeSettled(paymentSettlementSchedule)) paymentConfirmService.executePaymentCredit(paymentSettlementSchedule.payment)

                processedItemsIdList.add(paymentSettlementSchedule.id)
            },
            [
                logErrorMessage: "PaymentSettlementScheduleService.processPendingItems >>> Não foi possível processar a liquidação [paymentSettlementScheduleId: ${paymentSettlementScheduleId}]",
                logLockAsWarning: true,
                onError: { Exception exception ->
                    if (!Utils.isLock(exception)) errorItemsIdList.add(paymentSettlementScheduleId)
                }
            ])
        })

        deleteInBatch(processedItemsIdList)
        updateStatusInBatch(PaymentSettlementScheduleStatus.ERROR, errorItemsIdList)
    }

    public List<PaymentBankSlipSettlementScheduleWorkerItemVO> listPendingItems(PaymentBankSlipSettlementScheduleWorkerConfigVO workerConfigVO, Integer maxQueryItems, List<Long> paymentSettlementScheduleIdListToIgnore) {
        Date referenceSettlementDate = new Date()

        Map queryParams = ["settlementDate[le]": referenceSettlementDate, "payment.creditDate": referenceSettlementDate.clone().clearTime(), status: PaymentSettlementScheduleStatus.PENDING, "payment.status": PaymentStatus.CONFIRMED, column: "id"]
        if (paymentSettlementScheduleIdListToIgnore) queryParams."id[notIn]" = paymentSettlementScheduleIdListToIgnore

        List<Long> paymentSettlementScheduleIdList = PaymentSettlementSchedule.query(queryParams).list(max: maxQueryItems)

        if (!paymentSettlementScheduleIdList) return []

        List<PaymentBankSlipSettlementScheduleWorkerItemVO> itemList = []
        paymentSettlementScheduleIdList.collate(workerConfigVO.maxItemsPerThread).each { itemList.add(new PaymentBankSlipSettlementScheduleWorkerItemVO(it)) }

        return itemList
    }

    private void validateSave(Payment payment, Date settlementDate) {
        if (!payment) throw new RuntimeException("Cobrança não informada.")
        if (!settlementDate) throw new RuntimeException("Data de liquidação não informada.")
    }

    private Boolean shouldBeSettled(PaymentSettlementSchedule paymentSettlementSchedule) {
        Payment payment = paymentSettlementSchedule.payment

        if (!payment.status.isConfirmed()) return false
        if (payment.creditDate > new Date()) return false
        if (paymentSettlementSchedule.settlementDate > new Date()) return false

        return true
    }

    private void updateStatusInBatch(PaymentSettlementScheduleStatus status, List<Long> paymentSettlementScheduleIdList) {
        if (!paymentSettlementScheduleIdList) return

        Utils.withNewTransactionAndRollbackOnError({
            String sql = "UPDATE ${PaymentSettlementSchedule.simpleName} SET version = version + 1, last_updated = :now, status = :status WHERE id IN (:paymentSettlementScheduleIdList)"

            PaymentSettlementSchedule.executeUpdate(sql, [status: status, now: new Date(), paymentSettlementScheduleIdList: paymentSettlementScheduleIdList])
        }, [logErrorMessage: "PaymentSettlementScheduleService.updateStatusInBatch >>> Não foi possível atualizar o status para ${status}: [paymentSettlementScheduleIdList: ${paymentSettlementScheduleIdList}]"])
    }

    private void deleteInBatch(List<Long> paymentSettlementScheduleIdList) {
        if (!paymentSettlementScheduleIdList) return

        Utils.withNewTransactionAndRollbackOnError({
            String sql = "DELETE FROM ${PaymentSettlementSchedule.simpleName} WHERE id IN (:paymentSettlementScheduleIdList)"

            PaymentSettlementSchedule.executeUpdate(sql, [paymentSettlementScheduleIdList: paymentSettlementScheduleIdList])
        }, [logErrorMessage: "PaymentSettlementScheduleService.deleteInBatch >>> Não foi possível deletar os items processados: [paymentSettlementScheduleIdList: ${paymentSettlementScheduleIdList}]"])
    }
}
