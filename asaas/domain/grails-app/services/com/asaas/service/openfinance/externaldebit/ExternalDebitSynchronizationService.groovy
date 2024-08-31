package com.asaas.service.openfinance.externaldebit

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.exception.BusinessException
import com.asaas.openfinance.externaldebit.enums.ExternalDebitRefusalReason
import com.asaas.openfinance.externaldebit.enums.ExternalDebitStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ExternalDebitSynchronizationService {

    def externalDebitService
    def externalDebitConsentService
    def recurringCheckoutScheduleService

    public void synchronizeProcessed() {
        List<Long> externalDebitIdList = ExternalDebit.withProcessedPixTransaction([column: "id", ignoreCustomer: true]).list(max: 500)

        for (Long externalDebitId : externalDebitIdList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                ExternalDebit externalDebit = ExternalDebit.get(externalDebitId)
                if (!externalDebit.type.isPix()) throw new BusinessException("Tipo de transação externa não suportada!")

                if (externalDebit.pixTransaction.status.isDone()) {
                    externalDebitService.finish(externalDebit)
                } else if (externalDebit.pixTransaction.status.isRefused()) {
                    externalDebitService.refuse(externalDebit, ExternalDebitRefusalReason.PIX_REFUSED_BY_RECEIVER, externalDebit.pixTransaction.refusalReasonDescription)
                }
            }, [onError: { hasError = true }, logErrorMessage: "ExternalDebitSynchronizationService.synchronizeProcessed() -> Erro ao sincronizar dados da transação Pix iniciada via Open Finance [ExternalDebit.id: ${externalDebitId}]"])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    externalDebitService.error(ExternalDebit.get(externalDebitId))
                }, [logErrorMessage: "ExternalDebitSynchronizationService.synchronizeProcessed() -> Erro ao atualizar transação iniciada via Open Finance para o status ERROR [ExternalDebit.id: ${externalDebitId}]"])
            }
        }
    }

    public void synchronizeRequested() {
        List<Long> idList = ExternalDebit.requested([column: "id", ignoreCustomer: true, sort: "id", order: "asc"]).list(max: 500)

        for (Long id : idList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                ExternalDebit externalDebit = ExternalDebit.get(id)
                ExternalDebitConsent consent = externalDebit.consent

                if (consent.isSingleScheduled()) {
                    consent = externalDebitConsentService.schedule(consent)
                } else if (consent.isRecurringScheduled()) {
                    Boolean lastExternalDebitOfConsent = CustomDateUtils.isSameDayOfYear(externalDebit.scheduledDate, consent.recurringCheckoutSchedule.finishDate)
                    if (lastExternalDebitOfConsent) {
                        consent = externalDebitConsentService.schedule(consent)

                        recurringCheckoutScheduleService.schedule(consent.recurringCheckoutSchedule)
                    }
                }

                if (consent.hasErrors()) {
                    externalDebitService.refuse(externalDebit, ExternalDebitRefusalReason.INVALID_CONSENT, DomainUtils.getFirstValidationMessage(consent))
                    return
                }

                if (consent.operationType.isPix()) {
                    externalDebit = externalDebitService.createPixTransaction(externalDebit)
                    if (externalDebit.status.isRefused()) return

                    ExternalDebitStatus externalDebitStatus = externalDebitService.buildStatus(consent, externalDebit.pixTransaction)

                    if (externalDebitStatus.isScheduled()) {
                        externalDebitService.schedule(externalDebit)
                    } else if (externalDebitStatus.isProcessed()) {
                        externalDebitService.process(externalDebit)
                    } else if (externalDebitStatus.isAwaitingCheckoutRiskAnalysisRequest()) {
                        externalDebitService.awaitingCheckoutRiskAnalysisRequest(externalDebit)
                    }
                }
            }, [onError: { hasError = true }, logErrorMessage: "ExternalDebitSynchronizationService.synchronizeRequested() -> Erro ao sincronizar dados da transação Pix iniciada via Open Finance [ExternalDebit.id: ${id}]"])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    externalDebitService.error(ExternalDebit.get(id))
                }, [logErrorMessage: "ExternalDebitSynchronizationService.synchronizeRequested() -> Erro ao atualizar transação iniciada via Open Finance para o status ERROR [ExternalDebit.id: ${id}]"])
            }
        }
    }
}
