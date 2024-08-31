package com.asaas.service.receivableanticipationpartner

import com.asaas.domain.customer.Customer
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisitionItem
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerStatement
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItem
import com.asaas.receivableanticipationpartnerstatement.ReceivableAnticipationPartnerStatementType
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationPartnerStatementService {

    public void saveExternalTransfer(BigDecimal transferValue, String batchExternalIdentifier) {
        save(transferValue, ReceivableAnticipationPartnerStatementType.EXTERNAL_TRANSFER, null, batchExternalIdentifier)
    }

    public void saveAnticipationCredit(ReceivableAnticipationPartnerAcquisition partnerAcquisition) {
        for (ReceivableAnticipationPartnerAcquisitionItem item in partnerAcquisition.getPartnerAcquisitionItemList()) {
            BigDecimal value = (item.valueRequestToPartner * -1)
            save(value, ReceivableAnticipationPartnerStatementType.ANTICIPATION_CREDIT, partnerAcquisition.customer, partnerAcquisition.batchExternalIdentifier)
        }
    }

    public void saveAnticipationSettlement(List<Long> settlementItemList) {
        BigDecimal transferValue = 0
        String batchExternalIdentifier

        Utils.forEachWithFlushSession(settlementItemList, 50, { Long settlementItemId ->
            ReceivableAnticipationPartnerSettlementItem settlementItem = ReceivableAnticipationPartnerSettlementItem.get(settlementItemId)
            if (!batchExternalIdentifier) batchExternalIdentifier = settlementItem.partnerSettlement.partnerAcquisition.batchExternalIdentifier

            ReceivableAnticipationPartnerAcquisition partnerAcquisition = settlementItem.partnerSettlement.partnerAcquisition
            transferValue += settlementItem.value
            save(settlementItem.value, ReceivableAnticipationPartnerStatementType.ANTICIPATION_SETTLEMENT, partnerAcquisition.customer, batchExternalIdentifier)
        })

        save((transferValue * -1), ReceivableAnticipationPartnerStatementType.EXTERNAL_TRANSFER, null, batchExternalIdentifier)
    }

    private void save(BigDecimal value, ReceivableAnticipationPartnerStatementType type, Customer customer, String batchExternalIdentifierBase64) {
        ReceivableAnticipationPartnerStatement receivableAnticipationPartnerStatement = new ReceivableAnticipationPartnerStatement()
        receivableAnticipationPartnerStatement.value = value
        receivableAnticipationPartnerStatement.type = type
        receivableAnticipationPartnerStatement.customer = customer
        receivableAnticipationPartnerStatement.decodedBatchExternalIdentifier = new String(batchExternalIdentifierBase64.decodeBase64(), "UTF8")
        receivableAnticipationPartnerStatement.save(failOnError: true)
    }
}
