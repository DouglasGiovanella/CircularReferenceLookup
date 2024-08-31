package com.asaas.service.receivableanticipationanalysis

import com.asaas.billinginfo.BillingType
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalyst
import com.asaas.domain.user.User
import com.asaas.receivableanticipationanalysis.ReceivableAnticipationAnalystEventType
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ReceivableAnticipationAnalystService {

    def receivableAnticipationAnalysisQueueService
    def receivableAnticipationAnalystEventService

    public void saveIfNotExists(User user) {
        if (ReceivableAnticipationAnalyst.query([exists: true, userId: user.id]).get().asBoolean()) return

        ReceivableAnticipationAnalyst analyst = new ReceivableAnticipationAnalyst()
        analyst.user = user
        analyst.save(failOnError: true)
    }

    public void startWorking(Long analystId, BillingType billingTypeForAnalysis) {
        ReceivableAnticipationAnalyst analyst = ReceivableAnticipationAnalyst.get(analystId)

        ReceivableAnticipationAnalyst validatedDomain = validateStartWorking(analyst, billingTypeForAnalysis)
        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível distribuir antecipações para o analista", validatedDomain.errors)

        analyst.isWorking = true
        analyst.billingTypeForAnalysis = billingTypeForAnalysis
        analyst.save(failOnError: true)

        receivableAnticipationAnalystEventService.save(analystId, ReceivableAnticipationAnalystEventType.STARTED_WORKING)

        receivableAnticipationAnalysisQueueService.distributeForAnalyst(analystId)
    }

    public void stopWorking(Long analystId) {
        ReceivableAnticipationAnalyst analyst = ReceivableAnticipationAnalyst.get(analystId)
        analyst.isWorking = false
        analyst.billingTypeForAnalysis = null
        analyst.save(failOnError: true)

        receivableAnticipationAnalystEventService.save(analystId, ReceivableAnticipationAnalystEventType.STOPPED_WORKING)

        receivableAnticipationAnalysisQueueService.returnAnalysisToQueue(analystId)
    }

    private ReceivableAnticipationAnalyst validateStartWorking(ReceivableAnticipationAnalyst analyst, BillingType billingTypeForAnalysis) {
        ReceivableAnticipationAnalyst validatedDomain = new ReceivableAnticipationAnalyst()

        if (!billingTypeForAnalysis) {
            DomainUtils.addError(validatedDomain, "É necessário informar um tipo de antecipação (Boleto ou Cartão de crédito) para este analista")
            return validatedDomain
        }

        return validatedDomain
    }
}
