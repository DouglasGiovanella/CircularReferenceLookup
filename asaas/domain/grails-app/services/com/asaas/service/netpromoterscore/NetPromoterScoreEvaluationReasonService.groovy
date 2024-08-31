package com.asaas.service.netpromoterscore

import com.asaas.domain.netpromoterscore.NetPromoterScoreEvaluation
import com.asaas.domain.netpromoterscore.NetPromoterScoreEvaluationReason
import com.asaas.domain.netpromoterscore.NetPromoterScoreReason
import com.asaas.utils.DomainUtils
import grails.validation.ValidationException
import grails.transaction.Transactional

@Transactional
class NetPromoterScoreEvaluationReasonService {

    public void update(NetPromoterScoreEvaluation evaluation, List<Long> reasonIdList) {
        NetPromoterScoreEvaluationReason validatedEvaluationReason = validateUpdate(reasonIdList)
        if (validatedEvaluationReason.hasErrors()) throw new ValidationException("Não foi possível salvar as categorias de NPS", validatedEvaluationReason.errors)

        List<Long> currentReasonIdList = NetPromoterScoreEvaluationReason.query([column: "netPromoterScoreReason.id", netPromoterScoreEvaluation: evaluation]).list()

        List<Long> commonIdList = reasonIdList.intersect(currentReasonIdList)
        reasonIdList.removeAll(commonIdList)
        if (reasonIdList) save(evaluation, reasonIdList)

        currentReasonIdList.removeAll(commonIdList)
        if (currentReasonIdList) delete(evaluation, currentReasonIdList)
    }

    private NetPromoterScoreEvaluationReason validateUpdate(List<Long> newReasonIdList) {
        NetPromoterScoreEvaluationReason validatedEvaluationReason = new NetPromoterScoreEvaluationReason()

        if (!newReasonIdList) DomainUtils.addError(validatedEvaluationReason, "Selecione ao menos uma categoria para o NPS")

        return validatedEvaluationReason
    }

    private void save(NetPromoterScoreEvaluation evaluation, List<Long> reasonIdList) {
        for (Long reasonId : reasonIdList) {
            NetPromoterScoreEvaluationReason evaluationReason = new NetPromoterScoreEvaluationReason()
            evaluationReason.netPromoterScoreEvaluation = evaluation
            evaluationReason.netPromoterScoreReason = NetPromoterScoreReason.get(reasonId)
            evaluationReason.save(failOnError: true)
        }
    }

    private void delete(NetPromoterScoreEvaluation evaluation, List<Long> reasonIdList) {
        for (Long reasonId : reasonIdList) {
            NetPromoterScoreEvaluationReason evaluationReason = NetPromoterScoreEvaluationReason.query([netPromoterScoreEvaluation: evaluation, netPromoterScoreReason: NetPromoterScoreReason.get(reasonId)]).get()
            evaluationReason.deleted = true
            evaluationReason.save(failOnError: true)
        }
    }
}
