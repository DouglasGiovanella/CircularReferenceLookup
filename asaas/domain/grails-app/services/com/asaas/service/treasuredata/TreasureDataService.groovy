package com.asaas.service.treasuredata

import com.asaas.domain.customer.Customer
import com.asaas.domain.treasuredata.TreasureDataEvent
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.treasuredata.TreasureDataEventStatus
import com.asaas.treasuredata.TreasureDataEventType
import grails.transaction.Transactional
import grails.util.Environment
import groovy.json.JsonBuilder

@Transactional
class TreasureDataService {

    def springSecurityService

    public void track(Customer provider, TreasureDataEventType eventType, Map extraInfo = null) {
        track(springSecurityService.currentUser, provider, eventType, extraInfo)
    }

    public void track(User user, Customer provider, TreasureDataEventType eventType, Map extraInfo = null) {
        try {
            if (!isAsaasTeamUser(user)) return

            TreasureDataEvent eventData = new TreasureDataEvent()

            eventData.eventType = eventType
            eventData.provider = provider
            eventData.status = TreasureDataEventStatus.PENDING
            eventData.user = user

            if (extraInfo) {
                eventData.extraInfo = new JsonBuilder(extraInfo).toString()
            }

            eventData.save(failOnError: true)
        } catch (Exception exception) {
            AsaasLogger.error("Ocorreu um erro no método 'track' do TreasureDataService. user[${user.id}], provider[${provider.id}], eventType[${eventType}], extraInfo[${extraInfo}]", exception)
        }
    }

    private Boolean isAsaasTeamUser(User user) {
        if (!user) return false

        if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return true

        if (user.username.contains('@asaas.com')) return true

        return false
    }
}
