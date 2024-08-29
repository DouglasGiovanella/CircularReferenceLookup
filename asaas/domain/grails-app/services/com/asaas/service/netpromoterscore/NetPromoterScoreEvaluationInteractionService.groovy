package com.asaas.service.netpromoterscore

import com.asaas.domain.netpromoterscore.NetPromoterScoreEvaluation
import com.asaas.domain.netpromoterscore.NetPromoterScoreEvaluationInteraction
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class NetPromoterScoreEvaluationInteractionService {

    public NetPromoterScoreEvaluationInteraction save(NetPromoterScoreEvaluation evaluation, String observations) {
        NetPromoterScoreEvaluationInteraction interaction = new NetPromoterScoreEvaluationInteraction()

        interaction.netPromoterScoreEvaluation = evaluation
        interaction.analyst = UserUtils.getCurrentUser()
        interaction.status = evaluation.status
        interaction.observations = observations

        return interaction.save(failOnError: true)
    }
}
