package com.asaas.service.supportattendance

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.supportattendance.adapter.LinkProtocolListToAttendanceRequestAdapter
import com.asaas.integration.callcenter.supportattendance.adapter.SupportAttendanceAdapter

import grails.transaction.Transactional

@Transactional
class SupportAttendanceProtocolService {

    def asyncActionService

    def callCenterSupportAttendanceProtocolManagerService

    public void linkProtocolListToAttendance(LinkProtocolListToAttendanceRequestAdapter linkProtocolListToAttendanceRequestAdapter) {
        if (!linkProtocolListToAttendanceRequestAdapter.supportProtocolIdList) throw new BusinessException("É necessário informar pelo menos um protocolo para vínculo")

        callCenterSupportAttendanceProtocolManagerService.linkProtocolListToAttendance(linkProtocolListToAttendanceRequestAdapter)
    }

    public void saveSendSupportAttendanceEmailAsyncActionIfPossible(AccountManager accountManager, SupportAttendanceAdapter supportAttendanceAdapter) {
        if (accountManager.department.isOmbudsman()) return
        if (!supportAttendanceAdapter) return
        if (!supportAttendanceAdapter.asaasCustomerId) return

        asyncActionService.save(AsyncActionType.SEND_SUPPORT_ATTENDANCE_EMAIL, supportAttendanceAdapter.buildAsyncActionMap())
    }
}
