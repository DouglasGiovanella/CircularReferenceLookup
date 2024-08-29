package com.asaas.service.receivablehub.outbox

import com.asaas.domain.customer.Customer
import com.asaas.domain.receivablehub.ReceivableHubCustomerOutbox
import com.asaas.receivablehub.dto.customer.ReceivableHubPublishCustomerDTO
import com.asaas.receivablehub.enums.ReceivableHubCustomerOutboxEventName
import com.asaas.service.featureflag.FeatureFlagService
import com.asaas.utils.GsonBuilderUtils
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class ReceivableHubCustomerOutboxService {

    FeatureFlagService featureFlagService

    public void saveCustomerUpdated(Customer customer) {
        if (!shouldSaveOutboxEvent()) return

        ReceivableHubPublishCustomerDTO requestDTO = new ReceivableHubPublishCustomerDTO(customer)

        save(customer.id, ReceivableHubCustomerOutboxEventName.CUSTOMER_UPDATED, requestDTO)
    }

    private void save(Long customerId, ReceivableHubCustomerOutboxEventName eventName, ReceivableHubPublishCustomerDTO payloadObject) {
        ReceivableHubCustomerOutbox receivableHubCustomerOutbox = new ReceivableHubCustomerOutbox()
        receivableHubCustomerOutbox.customerId = customerId
        receivableHubCustomerOutbox.eventName = eventName
        receivableHubCustomerOutbox.payload = GsonBuilderUtils.toJsonWithoutNullFields(payloadObject)
        receivableHubCustomerOutbox.save(failOnError: true)
    }

    private Boolean shouldSaveOutboxEvent() {
        if (!featureFlagService.isReceivableHubOutboxEnabled()) return false

        return true
    }
}
