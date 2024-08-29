package com.asaas.service.receivableanticipation

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.exception.BusinessException
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationCancellationService {

    def asyncActionService
    def pushNotificationRequestReceivableAnticipationService
    def receivableAnticipationAnalysisService
    def receivableAnticipationCompromisedItemService
    def receivableAnticipationPartnerAcquisitionService
    def receivableAnticipationService

    public void cancelPending(Payment payment, ReceivableAnticipationCancelReason cancelReason) {
        ReceivableAnticipation anticipation = ReceivableAnticipation.query([payment: payment, statusList: ReceivableAnticipationStatus.listStillNotSentToPartner(), disableSort: true]).get()

        if (!anticipation) return

        cancel(anticipation, cancelReason)
    }

    public void cancel(ReceivableAnticipation anticipation, ReceivableAnticipationCancelReason cancelReason) {
        if (!anticipation.status.isCancelable()) throw new BusinessException("Devido a situação atual da antecipação, não é possível realizar o cancelamento.")

        receivableAnticipationService.updateStatus(anticipation, ReceivableAnticipationStatus.CANCELLED, Utils.getMessageProperty("ReceivableAnticipationCancelReason.${cancelReason.toString()}"))

        receivableAnticipationService.setPaymentsAsNotAnticipated(anticipation)

        receivableAnticipationPartnerAcquisitionService.cancel(anticipation)

        receivableAnticipationCompromisedItemService.delete(anticipation, null)

        if (anticipation.isCreditCard() && anticipation.isVortxAcquisition()) asyncActionService.saveCancelFidcContractualEffectGuarantee(anticipation.id)

        receivableAnticipationAnalysisService.finishAnalysisIfExists(anticipation.id)

        pushNotificationRequestReceivableAnticipationService.save(PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_CANCELLED, anticipation)
    }

    public void cancelAllPossibleByCustomerAccountIdList(List<Long> customerAccountIdList, ReceivableAnticipationCancelReason cancelReason) {
        if (!customerAccountIdList) return

        Map search = [
            column: "id",
            statusList: ReceivableAnticipationStatus.getCancelableList(),
            "customerAccountId[in]": customerAccountIdList
        ]

        List<Long> receivableAnticipationIdList = ReceivableAnticipation.query(search).list()
        if (!receivableAnticipationIdList) return

        cancelFromIdList(receivableAnticipationIdList, cancelReason)
    }

    public void cancelAllPossible(Customer customer, ReceivableAnticipationCancelReason cancelReason) {
        List<ReceivableAnticipationStatus> statusList = ReceivableAnticipationStatus.getCancelableList()

        List<Long> receivableAnticipationIdList = ReceivableAnticipation.query([
            column: "id",
            customer: customer,
            statusList: statusList
        ]).list()
        if (!receivableAnticipationIdList) return

        cancelFromIdList(receivableAnticipationIdList, cancelReason)
    }

    public void cancelAllPossibleOnCustomerDisable(Customer customer, Boolean isConfirmedFraud) {
        List<ReceivableAnticipationStatus> statusList = ReceivableAnticipationStatus.getCancelableList()
        if (isConfirmedFraud) statusList.remove(ReceivableAnticipationStatus.AWAITING_PARTNER_CREDIT)

        List<ReceivableAnticipation> anticipationList = ReceivableAnticipation.query([
            customer: customer,
            statusList: statusList
        ]).list()
        if (!anticipationList) return

        for (ReceivableAnticipation anticipation : anticipationList) {
            cancel(anticipation, ReceivableAnticipationCancelReason.CUSTOMER_DISABLED)
        }
    }

    public void cancelAllPossibleByBillingType(Long customerId, BillingType billingType, ReceivableAnticipationCancelReason cancelReason) {
        List<Long> receivableAnticipationIdList = ReceivableAnticipation.query([
            column: "id",
            customerId: customerId,
            billingType: billingType,
            statusList: ReceivableAnticipationStatus.getCancelableList()
        ]).list()
        if (!receivableAnticipationIdList) return

        cancelFromIdList(receivableAnticipationIdList, cancelReason)
    }

    private void cancelFromIdList(List<Long> receivableAnticipationIdList, ReceivableAnticipationCancelReason cancelReason) {
        for (Long id : receivableAnticipationIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation receivableAnticipation = ReceivableAnticipation.get(id)

                cancel(receivableAnticipation, cancelReason)
            }, [logErrorMessage: "ReceivableAnticipationCancellationService.cancelFromIdList: Não foi possível realizar o cancelamento da antecipação [${id}]" ])
        }
    }
}
