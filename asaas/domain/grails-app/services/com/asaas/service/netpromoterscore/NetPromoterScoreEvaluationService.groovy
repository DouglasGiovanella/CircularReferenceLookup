package com.asaas.service.netpromoterscore

import com.asaas.domain.netpromoterscore.NetPromoterScore
import com.asaas.domain.netpromoterscore.NetPromoterScoreEvaluation
import com.asaas.exception.BusinessException
import com.asaas.netpromoterscore.NetPromoterScoreEvaluationStatus
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class NetPromoterScoreEvaluationService {

    def netPromoterScoreEvaluationInteractionService

    private final Integer MAXIMUM_NPS_EVALUATION_CONTACT_ATTEMPTS = 3

    public List<Long> createEvaluationsForOneDayAgoAnswers() {
        final Integer maxItemsPerCycle = 1000
        final Integer batchSize = 50
        final Integer flushEvery = 50
        List<Long> netPromoterScoreIdList = NetPromoterScore.relevantNpsForEvaluation([:]).list(maxItemsPerCycle)

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(netPromoterScoreIdList, batchSize, flushEvery, { Long netPromoterScoreId ->
            save(netPromoterScoreId)
        }, [logErrorMessage: "NetPromoterScoreEvaluationService.createEvaluationsForOneDayAgoAnswers >> Erro ao salvar avaliação de NPS", appendBatchToLogErrorMessage: true])

        return netPromoterScoreIdList
    }

    public NetPromoterScoreEvaluation sendEvaluationToNextAwaitingStatusIfNecessary(Long id, String observations) {
        NetPromoterScoreEvaluation evaluation = NetPromoterScoreEvaluation.get(id)
        if (!evaluation) throw new BusinessException("Avaliação não encontrada")

        evaluation.attempts++

        if (evaluation.attempts >= MAXIMUM_NPS_EVALUATION_CONTACT_ATTEMPTS) {
            evaluation = getNextStatus(evaluation)
        }

        evaluation.observations = buildObservations(evaluation.status, observations)
        evaluation.save(failOnError: true)

        String interactionObservation = evaluation.observations
        netPromoterScoreEvaluationInteractionService.save(evaluation, interactionObservation)

        return evaluation
    }

    public NetPromoterScoreEvaluation setAsEvaluated(Long id, String customerSatisfied, String observations) {
        if (!customerSatisfied) {
            return DomainUtils.addError(new NetPromoterScoreEvaluation(), "O campo 'Cliente saiu satisfeito?' é obrigatório.")
        }

        NetPromoterScoreEvaluation evaluation = NetPromoterScoreEvaluation.get(id)
        if (!evaluation) throw new BusinessException("Avaliação não encontrada")

        evaluation.analyst = UserUtils.getCurrentUser()
        evaluation.evaluationDate = new Date()
        evaluation.observations = observations
        evaluation.customerSatisfied = Boolean.valueOf(customerSatisfied)
        evaluation = setAsEvaluatedIfPossible(evaluation)

        evaluation.save(failOnError: true)

        netPromoterScoreEvaluationInteractionService.save(evaluation, observations)

        return evaluation
    }

    private NetPromoterScoreEvaluation getNextStatus(NetPromoterScoreEvaluation evaluation) {
        if (evaluation.status.isAwaitingPhoneCallEvaluation()) return setAsAwaitingWhatsAppEvaluation(evaluation)
        return setAsExpired(evaluation)
    }

    private NetPromoterScoreEvaluation setAsEvaluatedIfPossible(NetPromoterScoreEvaluation evaluation) {
        if (evaluation.status.isAwaitingWhatsAppEvaluation()) return setAsEvaluatedByWhatsAppStatus(evaluation)
        if (evaluation.status.isAwaitingPhoneCallEvaluation()) return setAsEvaluatedByPhoneCallStatus(evaluation)
        return evaluation
    }

    private NetPromoterScoreEvaluation setAsAwaitingWhatsAppEvaluation(NetPromoterScoreEvaluation evaluation) {
        evaluation.status = NetPromoterScoreEvaluationStatus.AWAITING_WHATSAPP_EVALUATION
        evaluation.attempts = 0
        return evaluation
    }

    private NetPromoterScoreEvaluation setAsExpired(NetPromoterScoreEvaluation evaluation) {
        evaluation.status = NetPromoterScoreEvaluationStatus.EXPIRED
        return evaluation
    }

    private NetPromoterScoreEvaluation setAsEvaluatedByWhatsAppStatus(NetPromoterScoreEvaluation evaluation) {
        evaluation.status = NetPromoterScoreEvaluationStatus.EVALUATED_BY_WHATSAPP
        return evaluation
    }

    private NetPromoterScoreEvaluation setAsEvaluatedByPhoneCallStatus(NetPromoterScoreEvaluation evaluation) {
        evaluation.status = NetPromoterScoreEvaluationStatus.EVALUATED_BY_PHONE_CALL
        return evaluation
    }

    private String buildObservations(NetPromoterScoreEvaluationStatus status, String observations) {
        String evaluationObservations = observations

        if (status == NetPromoterScoreEvaluationStatus.EXPIRED && !evaluationObservations) {
            evaluationObservations = "Cliente não respondeu nenhuma das tentativas de contato"
        }

        return evaluationObservations
    }

    private void save(Long netPromoterScoreId) {
        NetPromoterScoreEvaluation evaluation = new NetPromoterScoreEvaluation()
        NetPromoterScore netPromoterScore = NetPromoterScore.load(netPromoterScoreId)

        evaluation.netPromoterScore = netPromoterScore

        evaluation.save(failOnError: true)
    }
}
