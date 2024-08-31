package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.service.notification.NotificationDispatcherCustomerOutboxService
import com.asaas.service.receivablehub.outbox.ReceivableHubCustomerOutboxService
import com.asaas.service.sage.SageAccountService
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerEventListenerService {

    NotificationDispatcherCustomerOutboxService notificationDispatcherCustomerOutboxService
    ReceivableHubCustomerOutboxService receivableHubCustomerOutboxService
    SageAccountService sageAccountService

    public void onCommercialInfoUpdated(Customer customer, List<String> customerUpdatedFieldList) {
        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)
        sageAccountService.onCustomerUpdated(customer, customerUpdatedFieldList)
        receivableHubCustomerOutboxService.saveCustomerUpdated(customer)
    }

    public void onStatusUpdated(Customer customer) {
        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)
        receivableHubCustomerOutboxService.saveCustomerUpdated(customer)
    }
}
