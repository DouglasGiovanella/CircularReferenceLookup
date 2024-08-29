package com.asaas.service.receivableanticipation

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils

@Transactional
class ReceivableAnticipationSandboxService {

    def receivableAnticipationPartnerStatementService
    def receivableAnticipationService

    public void credit(ReceivableAnticipation anticipation) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Não é possível executar esta operação em ambiente de produção")
        if (anticipation.isAsaasAcquisition() || !anticipation.status.isAwaitingPartnerCredit()) throw new BusinessException("Não é possível creditar esta antecipação.")

        receivableAnticipationService.credit(anticipation)

        if (anticipation.isVortxAcquisition()) {
            mockPartnerAcquisitionBatchExternalIdentifierFromVortx(anticipation.partnerAcquisition)
            receivableAnticipationPartnerStatementService.saveAnticipationCredit(anticipation.partnerAcquisition)
            receivableAnticipationPartnerStatementService.saveExternalTransfer(anticipation.partnerAcquisition.getValueWithPartnerFee(), anticipation.partnerAcquisition.batchExternalIdentifier)
        }
    }

    private void mockPartnerAcquisitionBatchExternalIdentifierFromVortx(ReceivableAnticipationPartnerAcquisition receivableAnticipationPartnerAcquisition) {
        receivableAnticipationPartnerAcquisition.batchExternalIdentifier = RandomStringUtils.randomAlphanumeric(12)
        receivableAnticipationPartnerAcquisition.save(failOnError: true)
    }
}
