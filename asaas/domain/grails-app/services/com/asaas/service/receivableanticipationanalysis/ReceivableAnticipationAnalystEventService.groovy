package com.asaas.service.receivableanticipationanalysis

import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalyst
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalystEvent
import com.asaas.receivableanticipationanalysis.ReceivableAnticipationAnalystEventType
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationAnalystEventService {

    public void save(Long analystId, ReceivableAnticipationAnalystEventType eventType) {
        ReceivableAnticipationAnalystEvent event = new ReceivableAnticipationAnalystEvent()
        event.analyst = ReceivableAnticipationAnalyst.get(analystId)
        event.type = eventType
        event.save(failOnError: true)
    }
}
