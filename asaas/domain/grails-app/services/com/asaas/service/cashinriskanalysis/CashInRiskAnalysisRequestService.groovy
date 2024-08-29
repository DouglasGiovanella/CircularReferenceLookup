package com.asaas.service.cashinriskanalysis

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.analysisrequest.AnalysisRequestValidator
import com.asaas.cashinriskanalysis.CashInRiskAnalysisRequestFinishReason
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequestReason
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CashInRiskAnalysisRequestService {

    def analysisInteractionService
    def cashInRiskAnalysisApprovalService
    def pixCreditTransactionAuthorizationService

    public CashInRiskAnalysisRequest start(User analyst, Long analysisRequestId) {
        CashInRiskAnalysisRequest analysisRequestPending = CashInRiskAnalysisRequest
            .query([id: analysisRequestId, status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED]).get()

        CashInRiskAnalysisRequest validatedAnalysisRequest = validateStart(analyst, analysisRequestPending)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequestPending.analyst = analyst
        analysisRequestPending.status = AnalysisRequestStatus.STARTED
        analysisRequestPending.save(failOnError: true)

        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.START, analysisRequestPending)

        return analysisRequestPending
    }

    public CashInRiskAnalysisRequest finish(User analyst,
                                            Long analysisRequestId,
                                            AnalysisRequestStatus status,
                                            String observations) {
        CashInRiskAnalysisRequest analysisRequest = CashInRiskAnalysisRequest.get(analysisRequestId)

        CashInRiskAnalysisRequest validatedAnalysisRequest = validateFinish(analyst, analysisRequest, status, observations)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequest.status = status
        analysisRequest.analyst = analyst
        analysisRequest.analysisDate = new Date()
        analysisRequest.observations = observations
        analysisRequest.analyzed = true
        if (analysisRequest.status.isDenied()) {
            analysisRequest.finishReason = CashInRiskAnalysisRequestFinishReason.CASH_IN_SUSPECTED_OF_FRAUD
        } else {
            analysisRequest.finishReason = CashInRiskAnalysisRequestFinishReason.CASH_IN_NOT_SUSPECTED_OF_FRAUD
        }
        analysisRequest.save(failOnError: true)

        if (analysisRequest.status.isApproved()) {
            cashInRiskAnalysisApprovalService.approveTransactions(analysisRequest)
        } else {
            denyTransactions(analysisRequest)
        }

        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.FINISH, analysisRequest)

        return analysisRequest
    }

    public BusinessValidation canStartAnalysis(CashInRiskAnalysisRequest analysisRequest, User analyst) {
        BusinessValidation validatedBusiness = new BusinessValidation()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.canStartAnalysis(analysisRequest, analyst)) {
            validatedBusiness.addErrors(analysisRequestValidator.errors)
        }

        return validatedBusiness
    }

    public void processExpired() {
        final Integer expiredAnalysisLimit = 100
        final String description = "Tempo limite de análise excedido."
        List<Long> expiredCashInRiskAnalysisRequestIdList = CashInRiskAnalysisRequest.expired([column: "id", status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED]).list(max: expiredAnalysisLimit)

        if (!expiredCashInRiskAnalysisRequestIdList) return

        Utils.forEachWithFlushSession(expiredCashInRiskAnalysisRequestIdList, 20, { Long cashInRiskAnalysisRequestId ->
            Utils.withNewTransactionAndRollbackOnError ({
                CashInRiskAnalysisRequest cashInRiskAnalysisRequest = CashInRiskAnalysisRequest.get(cashInRiskAnalysisRequestId)
                cashInRiskAnalysisApprovalService.approveAutomatically(cashInRiskAnalysisRequest, CashInRiskAnalysisRequestFinishReason.CASH_IN_RISK_ANALYSIS_REQUEST_TIME_OUT, description)
            }, [onError: { Exception exception ->
                AsaasLogger.error("CashInRiskAnalysisRequestService.processExpired >> Erro ao aprovar análise de crédito por tempo limite excedido. Id: [${cashInRiskAnalysisRequestId}].", exception)
            }])
        })
    }

    public void processIdleAnalysis() {
        final Integer maxItemsPerCycle = 50

        List<Long> analysisIdList = CashInRiskAnalysisRequest.query([column: "id", order: "asc", status: AnalysisRequestStatus.STARTED]).list(max: maxItemsPerCycle)
        if (!analysisIdList) return

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CashInRiskAnalysisRequest cashInRiskAnalysisRequest = CashInRiskAnalysisRequest.get(analysisId)
                updateStatusToManualAnalysisRequiredIfNecessary(cashInRiskAnalysisRequest)
            },[onError: { Exception exception ->
                AsaasLogger.error("CashInRiskAnalysisRequestService.processIdleAnalysis >> Falha ao expirar análise ID: [${analysisId}]", exception)
            }])
        }
    }

    private CashInRiskAnalysisRequest validateStart(User analyst, CashInRiskAnalysisRequest analysisRequest) {
        CashInRiskAnalysisRequest validatedAnalysisRequest = new CashInRiskAnalysisRequest()

        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.validateStart(analyst, analysisRequest)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysisRequest)
            return validatedAnalysisRequest
        }

        CashInRiskAnalysisRequest pendingAnalysisRequest = CashInRiskAnalysisRequest
            .query([analyst: analyst, status: AnalysisRequestStatus.STARTED]).get()

        if (pendingAnalysisRequest) {
            DomainUtils.addError(validatedAnalysisRequest, "Analista já possui uma análise iniciada, volte para a fila e finalize a sua análise em aberto antes de iniciar outra")
            return validatedAnalysisRequest
        }

        return validatedAnalysisRequest
    }

    private CashInRiskAnalysisRequest validateFinish(User analyst,
                                                     CashInRiskAnalysisRequest analysisRequest,
                                                     AnalysisRequestStatus status,
                                                     String observations) {
        CashInRiskAnalysisRequest validatedAnalysisRequest = new CashInRiskAnalysisRequest()

        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.validateFinish(analyst, analysisRequest, status, observations)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysisRequest)
        }

        return validatedAnalysisRequest
    }

    private void denyTransactions(CashInRiskAnalysisRequest analysis) {
        List<CashInRiskAnalysisRequestReason> analysisRequestReasonList = analysis.getCashInRiskAnalysisRequestReasonList()
        for (CashInRiskAnalysisRequestReason analysisRequestReason : analysisRequestReasonList) {
            if (analysisRequestReason.pixTransaction) {
                if (analysisRequestReason.pixTransaction.payment.status.isRefunded()) {
                    AsaasLogger.info("CashInRiskAnalysisRequestService.denyTransactions >> Bloqueio cautelar analisado como negado, porém a cobrança já foi estornada pelo cliente [paymentId: ${analysisRequestReason.pixTransaction.paymentId}, customerId: ${analysisRequestReason.pixTransaction.payment.providerId}]")
                    continue
                }
                pixCreditTransactionAuthorizationService.onCashInRiskAnalysisRequestDenied(analysisRequestReason.pixTransaction)
            }
        }
    }

    private void updateStatusToManualAnalysisRequiredIfNecessary(CashInRiskAnalysisRequest cashInRiskAnalysisRequest) {
        if (!cashInRiskAnalysisRequest) return

        if (!cashInRiskAnalysisRequest.status.isStarted()) return

        final Integer maxIdleAnalysisTimeInMinutes = 30
        Date idleAnalysisDateLimit = CustomDateUtils.sumMinutes(new Date(), -maxIdleAnalysisTimeInMinutes)

        Date lastStartDate = analysisInteractionService.findLastStartDate(cashInRiskAnalysisRequest)
        if (!lastStartDate) return

        if (lastStartDate.after(idleAnalysisDateLimit)) return

        cashInRiskAnalysisRequest.analyst = null
        cashInRiskAnalysisRequest.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
        cashInRiskAnalysisRequest.save(failOnError: true)
    }
}
