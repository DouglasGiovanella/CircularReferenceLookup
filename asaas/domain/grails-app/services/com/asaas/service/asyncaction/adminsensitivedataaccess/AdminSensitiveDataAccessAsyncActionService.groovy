package com.asaas.service.asyncaction.adminsensitivedataaccess

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.domain.asyncAction.SaveAdminSensitiveDataAccessAsyncAction
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sauron.adapter.adminsensitivedataaccess.AdminSensitiveDataAccessAdapter
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.SensitiveDataUtils

import grails.transaction.Transactional

import groovy.json.JsonOutput

@Transactional
class AdminSensitiveDataAccessAsyncActionService {

    def baseAsyncActionService
    def sauronAdminSensitiveDataAccessManagerService

    public void trackListAccessIfNecessary(SequencedResultList sequencedResultList, User user, Map params) {
        for (Object object : sequencedResultList.list) {
            trackObjectAccessIfNecessary(object, user, params)
        }
    }

    public void trackObjectAccessIfNecessary(Object object, User user, Map params) {
        if (!AsaasEnvironment.isProduction()) return

        if (!SensitiveDataUtils.containsSensitiveData(object)) return

        Boolean canUserViewSensitiveData = SensitiveDataUtils.canViewSensitiveData(user, params)
        if (!canUserViewSensitiveData) return

        AdminSensitiveDataAccessAdapter adminSensitiveDataAccessAdapter = AdminSensitiveDataAccessAdapter.buildTrack(object, user.id, params.controller, params.action)

        save(adminSensitiveDataAccessAdapter)
    }

    public void process() {
        final Integer maxItemsPerCycle = 10

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(SaveAdminSensitiveDataAccessAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return

        List<Long> asyncActionIdList = asyncActionDataList.collect { it.asyncActionId }
        baseAsyncActionService.updateStatus(SaveAdminSensitiveDataAccessAsyncAction, asyncActionIdList, AsyncActionStatus.PROCESSING)

        List<AdminSensitiveDataAccessAdapter> adapterList = []
        for (Map asyncActionData : asyncActionDataList) {
            AdminSensitiveDataAccessAdapter adminSensitiveDataAccessAdapter = AdminSensitiveDataAccessAdapter.fromAsyncActionData(asyncActionData)
            adapterList.add(adminSensitiveDataAccessAdapter)
        }

        try {
            sauronAdminSensitiveDataAccessManagerService.saveList(adapterList)
        } catch (Exception exception) {
            baseAsyncActionService.updateStatus(SaveAdminSensitiveDataAccessAsyncAction, asyncActionIdList, AsyncActionStatus.PENDING)
        }

        baseAsyncActionService.deleteList(SaveAdminSensitiveDataAccessAsyncAction, asyncActionIdList)
    }

    private void save(AdminSensitiveDataAccessAdapter adminSensitiveDataAccessAdapter) {
        try {
            String actionDataJson = JsonOutput.toJson(adminSensitiveDataAccessAdapter)

            Integer actionDataMaxSize = SaveAdminSensitiveDataAccessAsyncAction.constraints.actionData.getMaxSize()
            if (actionDataJson.length() > actionDataMaxSize) {
                AsaasLogger.warn("AdminSensitiveDataAccessAsyncActionService.saveAsyncAction >> O campo ActionData não pode conter mais que ${actionDataMaxSize} caracteres")
                return
            }

            baseAsyncActionService.save(new SaveAdminSensitiveDataAccessAsyncAction(), adminSensitiveDataAccessAdapter.toAsyncActionData())
        } catch (Exception exception) {
            AsaasLogger.error("AdminSensitiveDataAccessAsyncActionService.saveAsyncAction >> Erro ao salvar AsyncAction.", exception)
        }
    }
}
