package com.asaas.service.asaaserp

import com.asaas.asaaserp.AsaasErpActionType
import com.asaas.asyncaction.AsyncActionType
import com.asaas.cache.asaaserpcustomerconfig.AsaasErpCustomerConfigCacheVO
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.asaaserp.AsaasErpCustomerAccount
import com.asaas.integration.asaaserp.adapter.customeraccount.AsaasErpCustomerAccountAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasErpCustomerAccountService {

    def asaasErpCustomerAccountManagerService
    def asaasErpCustomerConfigCacheService
    def asyncActionService

    public void saveIfNecessary(CustomerAccount customerAccount, String asaasErpCustomerAccountExternalId, Boolean isRetroactive) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerAccount.provider.id)
        if (!asaasErpCustomerConfigCacheVO.id) return

        saveAsyncAction(null, customerAccount.id, asaasErpCustomerAccountExternalId, isRetroactive)
    }

    public void deleteIfNecessary(CustomerAccount customerAccount) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerAccount.provider.id)
        if (!asaasErpCustomerConfigCacheVO.id) return

        saveDeleteCustomerAsyncAction(customerAccount.id)
    }

    public void updateIfNecessary(CustomerAccount customerAccount, String asaasErpCustomerAccountExternalId) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerAccount.provider.id)
        if (!asaasErpCustomerConfigCacheVO.id) return

        AsaasErpCustomerAccount asaasErpCustomerAccount = AsaasErpCustomerAccount.query([customerAccount: customerAccount]).get()
        if (!asaasErpCustomerAccount) {
            AsaasLogger.warn("AsaasErpCustomerAccountService.updateIfNecessary >> Erro ao buscar o AsaasErpCustomerAccount do customerAccount [${customerAccount.id}]")
            return
        }

        saveAsyncAction(null, customerAccount.id,  asaasErpCustomerAccountExternalId, false)
    }

    public void saveForNotIntegrated(Long customerId) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerId)
        if (!asaasErpCustomerConfigCacheVO.id) return

        List<Long> customerAccountIdsList = CustomerAccount.query([column: "id", customerId: customerId, 'asaasErpCustomerAccount[notExists]': true, deleted: true, order: "asc", sort: "id"]).list()

        Utils.forEachWithFlushSession(customerAccountIdsList, 50, { Long customerAccountId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerAccount customerAccount = CustomerAccount.get(customerAccountId)
                saveIfNecessary(customerAccount, null, true)
            }, [onError: { Exception exception -> throw exception }])
        })
    }

    public void processCustomerAccountIntegrationQueue() {
        final Integer maxNumberOfAsyncActionPerExecution = 360

        List<Long> asyncActionIdPendingList = AsyncAction.oldestPending([column: "id", type: AsyncActionType.SEND_CUSTOMER_ACCOUNT_IN_ASAAS_ERP, disableSort: true, includeDeleted: true]).list(max: maxNumberOfAsyncActionPerExecution)

        processListWithThreads(asyncActionIdPendingList, false)
    }

    public void processCustomerAccountDeleteQueue() {
        final Integer maxNumberOfAsyncActionPerExecution = 360

        List<Long> asyncActionIdPendingList = AsyncAction.oldestPending([column: "id", type: AsyncActionType.SEND_DELETE_CUSTOMER_ACCOUNT_IN_ASAAS_ERP, disableSort: true, includeDeleted: true]).list(max: maxNumberOfAsyncActionPerExecution)

        processListWithThreads(asyncActionIdPendingList, false)
    }

    public void processCustomerAccountRetroactiveIntegrationQueue() {
        final Integer maxNumberOfAsyncActionPerExecution = 360

        List<Long> asyncActionIdPendingList = AsyncAction.oldestPending([column: "id", type: AsyncActionType.SEND_RETROACTIVE_ASAAS_ERP_CUSTOMER_ACCOUNT, disableSort: true, includeDeleted: true]).list(max: maxNumberOfAsyncActionPerExecution)

        processListWithThreads(asyncActionIdPendingList, true)
    }

    public AsaasErpCustomerAccount processCustomerAccountDeleteIntegration(AsyncAction asyncAction) {
        Map asyncActionData = asyncAction.getDataAsMap()
        CustomerAccount customerAccount = CustomerAccount.get(asyncActionData.customerAccountId)
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerAccount.provider.id)
        if (!asaasErpCustomerConfigCacheVO.id) return
        AsaasErpCustomerAccount asaasErpCustomerAccount = AsaasErpCustomerAccount.query([customerAccount: customerAccount]).get()
        if (!asaasErpCustomerAccount) return

        asaasErpCustomerAccount.deleted = true
        asaasErpCustomerAccount.lastSyncDate = new Date()

        asaasErpCustomerAccount.save(failOnError: true)
        return asaasErpCustomerAccount
    }

    public AsaasErpCustomerAccount processCustomerAccountIntegration(AsyncAction asyncAction, Boolean isRetroactive) {
        Map asyncActionData = asyncAction.getDataAsMap()
        AsaasErpCustomerConfig asaasErpCustomerConfig
        AsaasErpCustomerAccount asaasErpCustomerAccount

        if (asyncActionData.defaultAsaasCustomerAccountCustomerId) {
            asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customerId: Utils.toLong(asyncActionData.defaultAsaasCustomerAccountCustomerId)]).get()
            asaasErpCustomerAccount = AsaasErpCustomerAccount.query([asaasErpCustomerConfig: asaasErpCustomerConfig, isDefaultAsaasCustomerAccount: true]).get()
            if (!asaasErpCustomerAccount) asaasErpCustomerAccount = saveDefaultAsaasCustomerAccount(asaasErpCustomerConfig)
        } else {
            CustomerAccount customerAccount = CustomerAccount.get(asyncActionData.customerAccountId)
            asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customer: customerAccount.provider]).get()
            asaasErpCustomerAccount = AsaasErpCustomerAccount.query([customerAccount: customerAccount]).get()
            if (!asaasErpCustomerAccount) asaasErpCustomerAccount = save(customerAccount, asaasErpCustomerConfig, asyncActionData.asaasErpCustomerAccountExternalId, isRetroactive)
        }

        if (!asaasErpCustomerConfig) return
        if (asyncActionData.asaasErpCustomerAccountExternalId) return
        if (asaasErpCustomerAccount.deleted) return

        asaasErpCustomerAccount.lastSyncDate = new Date()

        asaasErpCustomerAccount.save(failOnError: true)
        return asaasErpCustomerAccount
    }

    public Boolean hasRetroactiveCustomerAccountToIntegrate(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        Boolean hasCustomerAccountRetroactiveToIntegrate = CustomerAccount.query([exists: true, customerId: asaasErpCustomerConfig.customerId, 'dateCreated[lt]': asaasErpCustomerConfig.dateCreated, 'asaasErpCustomerAccount[notExists]': true, deleted: true]).get().asBoolean()
        return hasCustomerAccountRetroactiveToIntegrate
    }

    public void setRetroactiveIntegrationAsFinishedIfNecessary(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        if (!asaasErpCustomerConfig.customerAccountIntegrated) return

        asaasErpCustomerAccountManagerService.setRetroactiveAsFinished(asaasErpCustomerConfig)
    }

    private void processListWithThreads(List<Long> asyncActionIdPendingList, Boolean isRetroactive) {
        final Integer maxItemsPerThread = 60

        ThreadUtils.processWithThreadsOnDemand(asyncActionIdPendingList, maxItemsPerThread, { List<Long> asyncActionIdList ->
            List<AsaasErpCustomerAccountAdapter> asaasErpCustomerAccountAdapterInProcessList = []
            List<Long> asyncActionErrorList = []

            final Integer batchSize = 10
            final Integer flushEvery = 10
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionIdList, batchSize, flushEvery, { Long asyncActionId ->
                try {
                    AsyncAction asyncAction = AsyncAction.read(asyncActionId)
                    AsaasErpActionType actionType = asyncAction.type.isSendDeleteCustomerAccountInAsaasErp() ? AsaasErpActionType.DELETED : AsaasErpActionType.CREATED

                    AsaasErpCustomerAccount asaasErpCustomerAccount

                    if (actionType.isDeleted()) {
                        asaasErpCustomerAccount = processCustomerAccountDeleteIntegration(asyncAction)
                    } else {
                        asaasErpCustomerAccount = processCustomerAccountIntegration(asyncAction, isRetroactive)
                    }

                    if (asaasErpCustomerAccount) {
                        AsaasErpCustomerAccountAdapter asaasErpCustomerAccountAdapter = new AsaasErpCustomerAccountAdapter(asaasErpCustomerAccount, actionType)
                        asaasErpCustomerAccountAdapterInProcessList.add(asaasErpCustomerAccountAdapter)
                    }
                } catch (Exception exception) {
                    if (!Utils.isLock(exception)) {
                        asyncActionErrorList.add(asyncActionId)
                        AsaasLogger.error("AsaasErpCustomerAccountService.processListWithThreads >>  Ocorreu um erro durante o processamento da asyncAction [${asyncActionId}]", exception)
                    }

                    throw exception
                }
            }, [logErrorMessage: "AsaasErpCustomerAccountService.processListWithThreads >> Falha ao sincronizar pagadores.",
                appendBatchToLogErrorMessage: true,
                logLockAsWarning: true,
                onEachTransactionEnd: { List<Long> batchAsyncActionIdList -> sendCustomerAccountBatch(asaasErpCustomerAccountAdapterInProcessList, batchAsyncActionIdList) }]
            )

            if (asyncActionErrorList) asyncActionService.setListAsError(asyncActionErrorList)
        })
    }

    private void saveAsyncAction(Long defaultAsaasCustomerAccountCustomerId, Long customerAccountId, String asaasErpCustomerAccountExternalId, Boolean isRetroactive) {
        Map asyncActionData = [:]
        if (defaultAsaasCustomerAccountCustomerId) {
            asyncActionData = [defaultAsaasCustomerAccountCustomerId: defaultAsaasCustomerAccountCustomerId]
        } else if (customerAccountId) {
            asyncActionData = [customerAccountId: customerAccountId, asaasErpCustomerAccountExternalId: asaasErpCustomerAccountExternalId]
        } else {
            throw new RuntimeException("AsaasErpCustomerAccountService.saveAsyncAction() >> Informe os atributos customerAccountId ou defaultAsaasCustomerAccountCustomerId para a criação do AsyncAction SEND_CUSTOMER_ACCOUNT_IN_ASAAS_ERP.")
        }

        if (isRetroactive) {
            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SEND_RETROACTIVE_ASAAS_ERP_CUSTOMER_ACCOUNT)) return
            asyncActionService.save(AsyncActionType.SEND_RETROACTIVE_ASAAS_ERP_CUSTOMER_ACCOUNT, asyncActionData)
        } else {
            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SEND_CUSTOMER_ACCOUNT_IN_ASAAS_ERP)) return
            asyncActionService.save(AsyncActionType.SEND_CUSTOMER_ACCOUNT_IN_ASAAS_ERP, asyncActionData)
        }
    }

    private void saveDeleteCustomerAsyncAction(Long customerAccountId) {
        Map asyncActionData = [customerAccountId: customerAccountId]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SEND_DELETE_CUSTOMER_ACCOUNT_IN_ASAAS_ERP)) return
        asyncActionService.save(AsyncActionType.SEND_DELETE_CUSTOMER_ACCOUNT_IN_ASAAS_ERP, asyncActionData)
    }

    private AsaasErpCustomerAccount saveDefaultAsaasCustomerAccount(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        AsaasErpCustomerAccount asaasErpCustomerAccount = new AsaasErpCustomerAccount()
        asaasErpCustomerAccount.asaasErpCustomerConfig = asaasErpCustomerConfig
        asaasErpCustomerAccount.isDefaultAsaasCustomerAccount = true
        asaasErpCustomerAccount.save(failOnError: true)
    }

    private AsaasErpCustomerAccount save(CustomerAccount customerAccount, AsaasErpCustomerConfig asaasErpCustomerConfig, String asaasErpCustomerAccountExternalId, Boolean isRetroactive) {
        AsaasErpCustomerAccount asaasErpCustomerAccount = new AsaasErpCustomerAccount()
        asaasErpCustomerAccount.asaasErpCustomerConfig = asaasErpCustomerConfig
        asaasErpCustomerAccount.customerAccount = customerAccount
        asaasErpCustomerAccount.isRetroactiveItem = isRetroactive
        if (asaasErpCustomerAccountExternalId) asaasErpCustomerAccount.externalId = asaasErpCustomerAccountExternalId
        asaasErpCustomerAccount.save(failOnError: true)

        return asaasErpCustomerAccount
    }

    private void sendCustomerAccountBatch(List<AsaasErpCustomerAccountAdapter> asaasErpCustomerAccountAdapterInProcessList, List<Long> batchAsyncActionIdList) {
        if (asaasErpCustomerAccountAdapterInProcessList) {
            List<AsaasErpCustomerAccountAdapter> asaasErpCustomerAccountAdapterToSendList = []
            asaasErpCustomerAccountAdapterToSendList.addAll(asaasErpCustomerAccountAdapterInProcessList)

            asaasErpCustomerAccountAdapterInProcessList.clear()
            asaasErpCustomerAccountManagerService.sendSqsMessageList(asaasErpCustomerAccountAdapterToSendList)
        }

        asyncActionService.deleteList(batchAsyncActionIdList)
    }
}
