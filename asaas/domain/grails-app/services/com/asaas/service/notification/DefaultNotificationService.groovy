package com.asaas.service.notification

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.asyncaction.builder.AsyncActionDataDeserializer
import com.asaas.defaultnotificationasyncactionprocessingpriority.DefaultNotificationAsyncActionProcessingPriority
import com.asaas.domain.asyncAction.DefaultNotificationAsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.notification.Notification
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class DefaultNotificationService {

    def asyncActionService
    def baseAsyncActionService
    def customerInteractionService
    def dataSource
    def grailsApplication
    def notificationService

    public List<Map> getCustomerAccountNotificationListMap(CustomerAccount customerAccount) {
        List<Notification> customerAccountNotificationList = Notification.query([customerAccount: customerAccount, disableSort: true]).list()
        List<Map> customerAccountNotificationListMap = []

        for (Notification notification : customerAccountNotificationList) {
            customerAccountNotificationListMap.add([
                event: notification.trigger.event,
                schedule: notification.trigger.schedule,
                scheduleOffset: notification.trigger.scheduleOffset,
                smsEnabledForCustomer: notification.smsEnabledForCustomer,
                smsEnabledForProvider: notification.smsEnabledForProvider,
                emailEnabledForCustomer: notification.emailEnabledForCustomer,
                emailEnabledForProvider: notification.emailEnabledForProvider,
                phoneCallEnabledForCustomer: notification.phoneCallEnabledForCustomer,
                whatsappEnabledForCustomer: notification.whatsappEnabledForCustomer
            ])
        }

        return customerAccountNotificationListMap
    }

    public void saveDefaultNotificationConfigAsyncAction(CustomerAccount baseCustomerAccount) {
        final Long maxCustomerAccountsToConfigure = 60000
        final Long maxCustomerAccountsToProcessDuringBusinessHours = 30000
        final String queuesSchemaName = grailsApplication.config.asaas.database.schema.queues.name

        User currentUser = UserUtils.getCurrentUser()

        if (!AdminUserPermissionUtils.canReplicateNotificationsRulesToCustomerAccounts(currentUser)) {
            throw new BusinessException("Usuário sem permissão para alterar notificações dos pagadores.")
        }

        if (DefaultNotificationAsyncAction.query([groupId: baseCustomerAccount.provider.id, status: AsyncActionStatus.PENDING, exists: true]).get().asBoolean()) {
            throw new BusinessException("Já existe uma padronização solicitada para este cliente.")
        }

        Long numberOfCustomerAccountsToConfigure = CustomerAccount.query([customer: baseCustomerAccount.provider]).count()

        if (numberOfCustomerAccountsToConfigure >= maxCustomerAccountsToConfigure) {
            String errorMessage = "o cliente [${baseCustomerAccount.provider.id}] possui mais de ${maxCustomerAccountsToConfigure} pagadores: total de ${numberOfCustomerAccountsToConfigure}"
            AsaasLogger.warn("DefaultNotificationService.saveDefaultNotificationConfigAsyncAction : padronização bloqueada : ${errorMessage}")
            throw new BusinessException("Não é possível executar a pronização: ${errorMessage}")
        }

        List<Long> customerAccountsIdsList = CustomerAccount.query([customer: baseCustomerAccount.provider, column: 'id', 'id[ne]': baseCustomerAccount.id, disableSort: true]).list()

        String customerInteractionDescription = "Iniciado: redefinição de configurações de notificações de ${customerAccountsIdsList.size()} pagadores, baseado no pagador ${baseCustomerAccount.name} [${baseCustomerAccount.id}]"
        customerInteractionService.save(baseCustomerAccount.provider, customerInteractionDescription)

        DefaultNotificationAsyncActionProcessingPriority processingPriority = DefaultNotificationAsyncActionProcessingPriority.MEDIUM
        if (customerAccountsIdsList.size() > maxCustomerAccountsToProcessDuringBusinessHours) {
            processingPriority = DefaultNotificationAsyncActionProcessingPriority.LOW
        }

        List<Map> dataToInsert = customerAccountsIdsList.collect { Long customerAccountId ->
            Map actionData = [customerAccountId: customerAccountId, baseCustomerAccountId: baseCustomerAccount.id]
            String jsonActionData = AsyncActionDataBuilder.parseToJsonString(actionData)

            return [
                "action_data": jsonActionData,
                "action_data_hash": AsyncActionDataBuilder.buildHash(jsonActionData),
                "date_created": new Date(),
                "deleted": 0,
                "last_updated": new Date(),
                "group_id": baseCustomerAccount.provider.id,
                "status": AsyncActionStatus.PENDING.toString(),
                "version": "0",
                "priority": processingPriority.toString()
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "${queuesSchemaName}.default_notification_async_action", dataToInsert)
    }

    public void processPendingAsyncActions() {
        final Integer maxPendingAsyncActions = 450
        final Integer minItemsPerThread = 150
        final Integer lowPriorityProcessingHourStart = 20
        final Integer lowPriorityProcessingHourFinish = 6

        DefaultNotificationAsyncActionProcessingPriority priority = DefaultNotificationAsyncActionProcessingPriority.MEDIUM
        Integer nowHour = CustomDateUtils.getHourOfDate(new Date())
        if (nowHour >= lowPriorityProcessingHourStart || nowHour <= lowPriorityProcessingHourFinish) priority = null

        Map searchMap = [status: AsyncActionStatus.PENDING]
        if (priority) {
            searchMap << [priority: priority]
        }
        DefaultNotificationAsyncAction pendingAsyncAction = DefaultNotificationAsyncAction.query(searchMap).get()

        if (!pendingAsyncAction) return

        Map asyncActionData = AsyncActionDataDeserializer.buildDataMap(pendingAsyncAction.actionData, pendingAsyncAction.id)

        Customer customer = Customer.read(pendingAsyncAction.groupId)
        CustomerAccount baseCustomerAccount = CustomerAccount.read(asyncActionData.baseCustomerAccountId as Long)

        List<Map> notificationConfigMapList = getCustomerAccountNotificationListMap(baseCustomerAccount)
        List<Map> pendingCustomerAccountAsyncActionDataList = baseAsyncActionService.listPendingData(DefaultNotificationAsyncAction, [groupId: customer.id], maxPendingAsyncActions)

        ThreadUtils.processWithThreadsOnDemand(pendingCustomerAccountAsyncActionDataList, minItemsPerThread, { List<Map> customerAccountAsyncActionDataList ->
            List<Long> customerAccountIdList = customerAccountAsyncActionDataList.collect { it.customerAccountId as Long }

            notificationService.configureBatchCustomerAccountNotifications(customerAccountIdList, notificationConfigMapList)

            baseAsyncActionService.deleteList(DefaultNotificationAsyncAction, customerAccountAsyncActionDataList.collect { it.asyncActionId })
        })

        finishGroupProcessingIfNecessary(customer.id, baseCustomerAccount.id)
    }

    private void finishGroupProcessingIfNecessary(Long customerId, Long baseCustomerAccountId) {
        Utils.withNewTransactionAndRollbackOnError ({
            if (DefaultNotificationAsyncAction.query([exists: true, groupId: customerId, status: AsyncActionStatus.PENDING]).get().asBoolean()) return

            Customer customer = Customer.read(customerId)
            CustomerAccount baseCustomerAccount = CustomerAccount.read(baseCustomerAccountId)

            String customerInteractionDescription = "Finalizado: redefinição de configurações de notificações dos pagadores do cliente ${customer.id} com base no pagador ${baseCustomerAccount.name} [${baseCustomerAccount.id}]"
            AsaasLogger.info("DefaultNotificationService.processPendingAsyncActions: ${customerInteractionDescription}")
            customerInteractionService.save(customer, customerInteractionDescription)
        }, [logErrorMessage: "DefaultNotificationService.finishGroupProcessingIfNecessary() -> Erro ao finalizar processamento do grupo [${customerId}]"])
    }
}
