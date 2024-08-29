package com.asaas.service.connectedaccount

import com.asaas.domain.user.User
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.integration.sauron.enums.ConnectedAccountEvent
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import grails.transaction.Transactional

@Transactional
class ConnectedAccountInfoHandlerService {

    def asyncActionService
    def connectedAccountEventAsyncActionService

    public void saveLoginInfoIfPossible(User user, String remoteIp, String cookie) {
        if (user.sysAdmin()) return

        ConnectedAccountInfoAdapter connectedAccountInfoAdapter = new ConnectedAccountInfoAdapter(user, remoteIp, cookie)
        saveInfoIfPossible(connectedAccountInfoAdapter)
    }

    public void saveInfoIfPossible(ConnectedAccountInfoAdapter infoUpdated) {
        saveInfoIfPossible(infoUpdated, new ConnectedAccountInfoAdapter())
    }

    public void saveInfoIfPossible(ConnectedAccountInfoAdapter infoUpdated, ConnectedAccountInfoAdapter infoToBeCompared) {
        try {
            Map eventMap = [:]

            Map phoneNumberEvent = buildPhoneEventIfPossible(infoUpdated, infoUpdated.phoneNumber, infoToBeCompared.phoneNumber)
            if (phoneNumberEvent) eventMap.phoneNumberEvent = phoneNumberEvent

            Map mobilePhoneNumberEvent = buildPhoneEventIfPossible(infoUpdated, infoUpdated.mobilePhoneNumber, infoToBeCompared.mobilePhoneNumber)
            if (mobilePhoneNumberEvent) eventMap.mobilePhoneNumberEvent = mobilePhoneNumberEvent

            Map cpfEvent = buildCpfEventIfPossible(infoUpdated, infoUpdated.cpf, infoToBeCompared.cpf)
            if (cpfEvent) eventMap.cpfEvent = cpfEvent

            Map loginEvent = buildLoginEventIfPossible(infoUpdated)
            if (loginEvent) eventMap.loginEvent = loginEvent

            if (!eventMap) return

            connectedAccountEventAsyncActionService.saveIfPossible(eventMap)
        } catch (Exception exception) {
            AsaasLogger.error("ConnectedAccountInfoHandlerService.saveInfoIfPossible >> Params: [${infoUpdated.properties}]", exception)
        }
    }

    private Map buildPhoneEventIfPossible(ConnectedAccountInfoAdapter infoUpdated, String phoneNumber, String oldPhoneNumber) {
        if (!phoneNumber) return null
        if (phoneNumber == oldPhoneNumber) return null

        Map eventMap = [
            customerId: infoUpdated.customerId,
            userId: infoUpdated.userId,
            type: ConnectedAccountEvent.HANDLE_PHONE_NUMBER.toString(),
            eventData: [ phoneNumber: phoneNumber, origin: infoUpdated.origin.toString() ]]

        return eventMap
    }

    private Map buildCpfEventIfPossible(ConnectedAccountInfoAdapter infoUpdated, String cpf, String oldCpf) {
        if (!cpf) return null
        if (cpf == oldCpf) return null
        if (!CpfCnpjUtils.isCpf(cpf)) return null

        Map eventMap = [
            customerId: infoUpdated.customerId,
            userId: infoUpdated.userId,
            type: ConnectedAccountEvent.HANDLE_CPF.toString(),
            eventData: [ cpf: cpf, origin: infoUpdated.origin.toString() ]]

        return eventMap
    }

    private Map buildLoginEventIfPossible(ConnectedAccountInfoAdapter infoAdapter) {
        if (!infoAdapter.remoteIp && !infoAdapter.cookie) return null

        Map eventMap = [
                customerId: infoAdapter.customerId,
                userId: infoAdapter.userId,
                type: ConnectedAccountEvent.USER_LOGIN.toString(),
                eventData: [ remoteIp: infoAdapter.remoteIp, cookie: infoAdapter.cookie ]]

        return eventMap
    }
}
