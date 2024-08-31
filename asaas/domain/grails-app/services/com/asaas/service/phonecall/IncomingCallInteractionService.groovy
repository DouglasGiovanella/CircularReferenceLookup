package com.asaas.service.phonecall

import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.integration.callcenter.phonecall.adapter.FinishIncomingCallAttendanceAdapter
import com.asaas.integration.callcenter.supportattendance.adapter.SupportAttendanceAdapter

import grails.transaction.Transactional

@Transactional
class IncomingCallInteractionService {

    def callCenterIncomingCallManagerService
    def customerInteractionService
    def supportAttendanceProtocolService

    public void finishAttendance(FinishIncomingCallAttendanceAdapter finishIncomingCallAttendanceAdapter) {
        SupportAttendanceAdapter supportAttendanceAdapter = callCenterIncomingCallManagerService.finishAttendance(finishIncomingCallAttendanceAdapter)

        AccountManager accountManager = AccountManager.read(finishIncomingCallAttendanceAdapter.accountManagerId)
        supportAttendanceProtocolService.saveSendSupportAttendanceEmailAsyncActionIfPossible(accountManager, supportAttendanceAdapter)

        String description = "Realizado contato na ligação do tipo \"${finishIncomingCallAttendanceAdapter.phoneCallType.getLabel()}\""
        description += "\nObservações: ${finishIncomingCallAttendanceAdapter.observations ?: 'Nenhuma'}."

        saveCustomerInteractionIfNecessary(finishIncomingCallAttendanceAdapter, description)
    }

    public void finishAttendanceWhenConnectionDropped(FinishIncomingCallAttendanceAdapter finishIncomingCallAttendanceAdapter) {
        callCenterIncomingCallManagerService.finishAttendanceWhenConnectionDropped(finishIncomingCallAttendanceAdapter)

        String description = "Cliente ligou para ${finishIncomingCallAttendanceAdapter.phoneCallType.getLabel()} mas a ligação caiu."
        description += "\nObservações: ${finishIncomingCallAttendanceAdapter.observations ?: 'Nenhuma'}."

        saveCustomerInteractionIfNecessary(finishIncomingCallAttendanceAdapter, description)
    }

    private void saveCustomerInteractionIfNecessary(FinishIncomingCallAttendanceAdapter finishIncomingCallAttendanceAdapter, String description) {
        if (!finishIncomingCallAttendanceAdapter.asaasCustomerId) return

        Customer customer = Customer.read(finishIncomingCallAttendanceAdapter.asaasCustomerId)
        User user = User.read(finishIncomingCallAttendanceAdapter.asaasUserId)
        customerInteractionService.save(customer, description, user)
    }
}
