package com.asaas.service.asyncaction

import com.asaas.domain.asyncAction.NexinvoiceCustomerUpdateAsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class NexinvoiceCustomerUpdateAsyncActionService {

    def nexinvoiceCustomerConfigCacheService
    def baseAsyncActionService
    def nexinvoiceCustomerManagerService

    public void saveIfNecessary(Long customerId) {
        Boolean isCustomerIntegrated = nexinvoiceCustomerConfigCacheService.byCustomerId(customerId).isIntegrated.asBoolean()
        if (!isCustomerIntegrated) return

        final Map asyncActionData = [
            customerId: customerId,
        ]

        baseAsyncActionService.save(new NexinvoiceCustomerUpdateAsyncAction(), asyncActionData)
    }

    public Boolean process() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(NexinvoiceCustomerUpdateAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return false

        baseAsyncActionService.processListWithNewTransaction(NexinvoiceCustomerUpdateAsyncAction, asyncActionDataList, { Map asyncActionData ->
            Customer customer = Customer.read(Utils.toLong(asyncActionData.customerId))

            String nexinvoiceCustomerConfigPublicId = nexinvoiceCustomerConfigCacheService.byCustomerId(customer.id).publicId
            String jwtToken = nexinvoiceCustomerManagerService.getJwtToken(customer.email, nexinvoiceCustomerConfigPublicId)

            nexinvoiceCustomerManagerService.updateCustomer(customer.name, jwtToken)
        }, [
            logErrorMessage: "NexinvoiceCustomerUpdateAsyncActionService.process >> Não foi possível processar a ação assíncrona [${asyncActionDataList}]",
        ])

        return true
    }
}
