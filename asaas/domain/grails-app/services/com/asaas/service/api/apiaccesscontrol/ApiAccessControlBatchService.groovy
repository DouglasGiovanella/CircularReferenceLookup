package com.asaas.service.api.apiaccesscontrol

import com.asaas.api.apiaccesscontrol.adapter.ApiAccessControlSaveAdapter
import com.asaas.api.apiaccesscontrol.adapter.ApiAccessControlSaveBatchAdapter
import com.asaas.api.apiaccesscontrol.adapter.ApiAccessControlUpdateAdapter
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class ApiAccessControlBatchService {

    def apiAccessControlService
    def criticalActionService

    public void processBatch(Customer customer, ApiAccessControlSaveBatchAdapter adapter) {
        String hash = buildCriticalActionHash(customer, adapter)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, adapter.criticalActionGroupId, adapter.criticalActionToken, CriticalActionType.API_ALLOWED_IPS_CHANGED, hash)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        if (adapter.deleteList) {
            for (Long id : adapter.deleteList) {
                apiAccessControlService.delete(customer, id)
            }
        }

        if (adapter.saveList) {
            for (ApiAccessControlSaveAdapter saveAdapter : adapter.saveList) {
                apiAccessControlService.save(customer, saveAdapter)
            }
        }

        if (adapter.updateList) {
            for (ApiAccessControlUpdateAdapter updateAdapter : adapter.updateList) {
                apiAccessControlService.update(customer, updateAdapter)
            }
        }
    }

    public CriticalActionGroup requestCriticalActionToken(Customer customer, ApiAccessControlSaveBatchAdapter adapter) {
        String hash = buildCriticalActionHash(customer, adapter)

        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.API_ALLOWED_IPS_CHANGED, hash)
    }

    private String buildCriticalActionHash(Customer customer, ApiAccessControlSaveBatchAdapter adapter) {
        String operation = ""
        operation += customer.id.toString()
        operation += JsonOutput.toJson(adapter.saveList + adapter.deleteList + adapter.updateList)

        return operation.encodeAsMD5()
    }
}
