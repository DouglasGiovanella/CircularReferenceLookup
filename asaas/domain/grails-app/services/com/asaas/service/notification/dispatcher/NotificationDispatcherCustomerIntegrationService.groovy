package com.asaas.service.notification.dispatcher

import com.asaas.domain.notification.NotificationDispatcherCustomer
import com.asaas.notification.dispatcher.NotificationDispatcherCustomerStatus
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherCustomerIntegrationService {

    def notificationDispatcherCustomerService
    def notificationDispatcherManagerService

    public void processFullyIntegratedCustomers() {
        List<Long> customerIdList = NotificationDispatcherCustomer.withoutPendingCustomerAccountMigration([
            column: "customer.id",
            status: NotificationDispatcherCustomerStatus.SYNCHRONIZING,
            manualMigration: false,
            sort: "id",
            order: "asc"
        ]).list(max: 500)

        if (!customerIdList) return

        NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO requestDTO = new NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO(customerIdList)
        Boolean sent = notificationDispatcherManagerService.requestEnableCustomerIntegrationList(requestDTO)

        if (!sent) return

        for (Long customerId : customerIdList) {
            notificationDispatcherCustomerService.saveFullyIntegreated(customerId)
        }
    }

    public void processCustomerEnabledList(List<Long> customerIdList, Boolean enabled) {
        Boolean sent = false
        NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO requestDTO = new NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO(customerIdList)

        if (enabled) sent = notificationDispatcherManagerService.requestEnableCustomerIntegrationList(requestDTO)
        else sent = notificationDispatcherManagerService.requestDisableCustomerIntegrationList(requestDTO)

        if (!sent) return

        for (Long customerId : customerIdList) {
            notificationDispatcherCustomerService.setCustomerEnabled(customerId, enabled)
        }
    }
}
