package com.asaas.service.asyncaction

import com.asaas.domain.asyncAction.NexinvoiceCustomerDisableAsyncAction
import grails.transaction.Transactional

@Transactional
class NexinvoiceCustomerDisableAsyncActionService {

    def nexinvoiceCustomerManagerService
    def baseAsyncActionService
    def userService

    public void save(String nexinvoiceCustomerConfigPublicId) {
        final Map asyncActionData = [
            publicId: nexinvoiceCustomerConfigPublicId
        ]

        baseAsyncActionService.save(new NexinvoiceCustomerDisableAsyncAction(), asyncActionData)
    }

    public void process() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(NexinvoiceCustomerDisableAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return

        baseAsyncActionService.processListWithNewTransaction(NexinvoiceCustomerDisableAsyncAction, asyncActionDataList, { Map asyncActionData ->
            nexinvoiceCustomerManagerService.disableCustomer(asyncActionData.publicId)
        }, [
            logErrorMessage: "NexinvoiceCustomerDisableAsyncActionService.process >> Não foi possível processar a ação assíncrona [${asyncActionDataList}]",
        ])
    }
}
