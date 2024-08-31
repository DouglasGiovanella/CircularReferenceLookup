package com.asaas.service.campaigneventmessage

import com.asaas.campaignevent.CampaignEventStatus
import com.asaas.domain.campaignevent.CampaignEventMessage
import com.asaas.integration.aws.managers.CampaignEventMessageSnsClientProxy
import com.asaas.integration.aws.managers.SnsManager
import com.asaas.log.AsaasLogger
import com.asaas.sns.SnsTopicArn

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CampaignEventMessageService {

    public void sendEvents() {
        final Integer maxPendingItems = 1000

        List<Map> campaignEventMessageList = CampaignEventMessage.isPending([columnList: ["id", "data", "eventName"]]).list(max: maxPendingItems)
        List<Long> eventMessageSuccessfullySentList = []
        List<Long> eventMessageNotSentSuccessfullyList = []
        SnsManager snsManager = new SnsManager(CampaignEventMessageSnsClientProxy.CLIENT_INSTANCE, SnsTopicArn.CAMPAIGN_EVENT_MESSAGE)

        for (Map campaignEventMessage : campaignEventMessageList) {
            try {
                snsManager.sendMessage(campaignEventMessage.data)
                eventMessageSuccessfullySentList.add(campaignEventMessage.id)
            } catch (Exception exception) {
                eventMessageNotSentSuccessfullyList.add(campaignEventMessage.id)
                AsaasLogger.error("CampaignEventMessageService.sendEvents >> Erro ao enviar evento ao SNS. CampaignEventMessageId: [${campaignEventMessage.id}]", exception)
            }
        }

        if (eventMessageSuccessfullySentList.size() > 0) CampaignEventMessage.where { "in"("id", eventMessageSuccessfullySentList) }.updateAll([status: CampaignEventStatus.DONE])
        if (eventMessageNotSentSuccessfullyList.size() > 0) CampaignEventMessage.executeUpdate("update CampaignEventMessage set attempt = attempt + 1, version = version + 1, status = :status where id in (:idList)", [status: CampaignEventStatus.PENDING, idList: eventMessageNotSentSuccessfullyList])
    }
}
