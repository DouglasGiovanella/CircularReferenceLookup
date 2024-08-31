package com.asaas.service.credittransferrequest

import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.exception.BusinessException
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CreditTransferRequestAuthorizationService {

    def creditTransferRequestAdminService
    def creditTransferRequestService
    def scheduledCreditTransferRequestProcessService

    public void onCriticalActionAuthorization(CriticalAction action) {
        switch (action.type) {
            case CriticalActionType.TRANSFER:
                CreditTransferRequest creditTransferRequest = action.transfer
                creditTransferRequest.awaitingCriticalActionAuthorization = false
                creditTransferRequest.save(failOnError: true)

                processTransferAuthorization(creditTransferRequest)
                break
            case CriticalActionType.TRANSFER_CANCELLING:
                creditTransferRequestService.executeCancellation(action.transfer)
                break
            default:
                throw new RuntimeException("Não é possível realizar CriticalAction para [${action.type}].")
        }
    }

    public CreditTransferRequest onCriticalActionCancellation(CriticalAction action) {
        creditTransferRequestService.cancel(UserUtils.getCurrentUser(), action.transfer.id)
        action.transfer.refresh()
    }

    public void onExternalAuthorizationApproved(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.status != CreditTransferRequestStatus.AWAITING_EXTERNAL_AUTHORIZATION) {
            throw new RuntimeException("CreditTransferRequestAuthorizationService.onExternalAuthorizationApproved > Transação [${creditTransferRequest.id}] não está aguardando autorização externa.")
        }

        if (creditTransferRequest.scheduledDate) {
            processTransferAuthorization(creditTransferRequest)
            return
        }

        creditTransferRequestService.setAsPendingOrAwaitingLiberation(creditTransferRequest)
    }

    public void onExternalAuthorizationRefused(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.status != CreditTransferRequestStatus.AWAITING_EXTERNAL_AUTHORIZATION) {
            throw new RuntimeException("CreditTransferRequestAuthorizationService.onExternalAuthorizationRefused > Transação [${creditTransferRequest.id}] não está aguardando autorização externa.")
        }

        creditTransferRequestService.cancel(creditTransferRequest)
    }

    public BusinessValidation validateExternalAuthorizationTransferStatus(CreditTransferRequest creditTransferRequest) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (creditTransferRequest.status.isCancelled()) businessValidation.addError("customerExternalAuthorization.transfer.creditTransferRequest.status.isCancelled")

        return businessValidation
    }

    private void processTransferAuthorization(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.scheduledDate) {
            processScheduledTransferAuthorization(creditTransferRequest)
        } else {
            creditTransferRequestAdminService.authorizeAutomaticallyIfPossible(creditTransferRequest)
        }
    }

    private void processScheduledTransferAuthorization(CreditTransferRequest creditTransferRequest) {
        if (creditTransferRequest.scheduledDate < new Date().clearTime()) throw new BusinessException("Não é possível aprovar um agendamento anterior ao dia de hoje")

        Boolean shouldSetToScheduled = creditTransferRequest.scheduledDate > new Date().clearTime()
        if (shouldSetToScheduled) {
            creditTransferRequestService.setAsScheduled(creditTransferRequest)
        } else {
            Integer limitHourToExecuteTransferToday = CreditTransferRequest.getLimitHourToExecuteTransferToday()
            if (CustomDateUtils.getInstanceOfCalendar().get(Calendar.HOUR_OF_DAY) < limitHourToExecuteTransferToday) {
                scheduledCreditTransferRequestProcessService.processScheduledCreditTransferRequest(creditTransferRequest)
            } else {
                throw new BusinessException("Não é possivel autorizar um agendamento para o mesmo dia após as ${limitHourToExecuteTransferToday} horas.")
            }
        }
    }
}
