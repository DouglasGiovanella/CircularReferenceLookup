package com.asaas.service.asyncaction

import com.asaas.cache.nexinvoicecustomerconfig.NexinvoiceCustomerConfigCacheVO
import com.asaas.utils.Utils
import com.asaas.domain.asyncAction.NexinvoiceUserDisableAsyncAction
import com.asaas.domain.user.User
import grails.transaction.Transactional

@Transactional
class NexinvoiceUserDisableAsyncActionService {

    def baseAsyncActionService
    def nexinvoiceCustomerConfigCacheService
    def nexinvoiceCustomerManagerService
    def nexinvoiceUserConfigService

    public void deleteIfNecessary(User user) {
        NexinvoiceCustomerConfigCacheVO nexinvoiceCustomerConfig = nexinvoiceCustomerConfigCacheService.byCustomerId(user.customerId)

        Boolean isCustomerIntegrated = nexinvoiceCustomerConfig.isIntegrated.asBoolean()
        if (!isCustomerIntegrated) return

        final Map asyncActionData = [
            userId: user.id,
            nexinvoiceCustomerConfigPublicId: nexinvoiceCustomerConfig.publicId
        ]

        baseAsyncActionService.save(new NexinvoiceUserDisableAsyncAction(), asyncActionData)
    }

    public void process() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(NexinvoiceUserDisableAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return

        baseAsyncActionService.processListWithNewTransaction(NexinvoiceUserDisableAsyncAction, asyncActionDataList, { Map asyncActionData ->
            Long userId = Utils.toLong(asyncActionData.userId)
            String nexinvoiceCustomerConfigPublicId = asyncActionData.nexinvoiceCustomerConfigPublicId

            nexinvoiceCustomerManagerService.disableUser(userId, nexinvoiceCustomerConfigPublicId)
            nexinvoiceUserConfigService.deleteByUserId(userId)
        }, [
            logErrorMessage: "NexinvoiceUserDisableAsyncActionService.process >> Não foi possível processar a ação assíncrona [${asyncActionDataList}]",
        ])
    }
}
