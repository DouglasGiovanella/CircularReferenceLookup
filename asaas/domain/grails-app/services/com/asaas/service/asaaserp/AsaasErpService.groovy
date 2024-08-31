package com.asaas.service.asaaserp

import com.asaas.api.ApiBaseParser
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asaaserp.AsaasErpAccountingStatementStatus
import com.asaas.asaaserp.adapter.AsaasErpCustomerCreateAdapter
import com.asaas.asyncaction.AsyncActionType
import com.asaas.cache.asaaserpcustomerconfig.AsaasErpCustomerConfigCacheVO
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.asaaserp.AsaasErpAccountingStatement
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.asaaserp.AsaasErpUserConfig
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.user.Role
import com.asaas.domain.user.User
import com.asaas.domain.user.UserRole
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.asaaserp.adapter.accountingstatement.AsaasErpAccountingStatementAdapter
import com.asaas.log.AsaasLogger
import com.asaas.redis.RedissonProxy
import com.asaas.user.UserUtils
import com.asaas.user.adapter.UserAdapter
import com.asaas.userpermission.ModulePermission
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils

import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional
import org.redisson.api.RBucket

@Transactional
class AsaasErpService {

    def asaasErpAccountingStatementService
    def asaasErpAccountingStatementManagerService
    def asaasErpCustomerAccountService
    def asaasErpCustomerConfigCacheService
    def asaasErpCustomerConfigService
    def asaasErpCustomerManagerService
    def asaasErpFinancialTransactionNotificationService
    def asaasErpUserAsyncActionService
    def asyncActionService
    def createCampaignEventMessageService
    def customerAlertNotificationService
    def userApiKeyService
    def userService

    public void integrateCustomer(Long customerId) {
        Utils.withNewTransactionAndRollbackOnError({
            createCustomer(customerId)
        }, [onError: { Exception exception -> throw exception }])

        Map asyncActionData = [customerId: customerId]
        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.CREATE_RETROACTIVE_ITEMS_TO_ASAAS_ERP)) return
        asyncActionService.save(AsyncActionType.CREATE_RETROACTIVE_ITEMS_TO_ASAAS_ERP, asyncActionData)
    }

    public void createRetroactiveItems(Long customerId) {
        asaasErpCustomerAccountService.saveForNotIntegrated(customerId)
        asaasErpAccountingStatementService.saveForNotIntegrated(customerId)
        asaasErpUserAsyncActionService.saveForNotIntegrated(customerId)
        setCustomersAsIntegratedIfPossible(customerId)
    }

    public void createCustomer(Long customerId) {
        Customer customer = Customer.get(customerId)
        if (!customer) throw new ResourceNotFoundException("Cliente ${customerId} não foi encontrado.")

        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerId)
        if (asaasErpCustomerConfigCacheVO.id) throw new BusinessException("Cliente já cadastrado.")

        User user = createIntegrationUser(customer)

        String accessToken = userApiKeyService.generateAccessToken(user, AsaasErpCustomerConfig.ASAAS_ERP_DEVICE)

        AsaasErpCustomerCreateAdapter asaasErpCustomerCreateAdapter = new AsaasErpCustomerCreateAdapter(customer)
        Map createdCustomer = asaasErpCustomerManagerService.create(asaasErpCustomerCreateAdapter, user.username, accessToken)

        asaasErpCustomerConfigService.save(customer, createdCustomer.externalId, createdCustomer.apiKey)
    }

    public User createIntegrationUser(Customer customer) {
        final String integrationUserName = "Integração ERP"

        UserAdapter userAdapter = new UserAdapter()
        userAdapter.customer = customer
        userAdapter.password = UserUtils.generateRandomPassword()
        userAdapter.username = getIntegrationUsername(customer)
        userAdapter.name = integrationUserName
        userAdapter.authority = RoleAuthority.ROLE_USER_FINANCIAL
        userAdapter.disableNewUserNotification = true
        userAdapter.disableAlertNotificationAboutUserCreation = true

        User user = userService.save(userAdapter)
        if (user.hasErrors()) {
            AsaasLogger.error("AsaasErpService.createIntegrationUser >> Erro ao criar usuário de integração para o Base ERP. Customer: [${customer.id}] Username: [${userAdapter.username}] Erros: ${user.errors}")
            throw new BusinessException("Erro ao criar usuário de integração para o Base ERP")
        }

        UserRole.create(user, Role.findByAuthority("ROLE_ASAAS_ERP_APPLICATION"), true)

        return user
    }

    public void processAccountingStatementQueue() {
        final Integer itemsToProcess = 360
        final Integer actionsPerThread = 60

        asaasErpAccountingStatementService.processAccountingStatementQueueWithThread(itemsToProcess, actionsPerThread, AsyncActionType.SEND_ASAAS_ERP_ACCOUNTING_STATEMENT)
    }

    public void processRetroactiveAccountingStatements() {
        final Integer itemsToProcess = 330
        final Integer actionsPerThread = 55

        asaasErpAccountingStatementService.processAccountingStatementQueueWithThread(itemsToProcess, actionsPerThread, AsyncActionType.SEND_RETROACTIVE_ASAAS_ERP_ACCOUNTING_STATEMENT)
    }

    public void processAccountingStatementDeleteQueue() {
        final Integer maxNumberOfBillToReceiveDelete = 10

        List<Long> asyncActionIdList = AsyncAction.oldestPending([column: "id", type: AsyncActionType.SEND_DELETE_ASAAS_ERP_ACCOUNTING_STATEMENT]).list(max: maxNumberOfBillToReceiveDelete)
        if (!asyncActionIdList) return

        final Integer batchSize = 10
        final Integer flushEvery = 10
        List<Long> asyncActionErrorList = []
        List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterList = []

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionIdList, batchSize, flushEvery, { Long asyncActionId ->
            try {
                AsyncAction asyncAction = AsyncAction.read(asyncActionId)
                Map asyncActionData = asyncAction.getDataAsMap()

                if (!asaasErpAccountingStatementService.isAsyncActionOriginDeleted(asyncActionData.paymentId, asyncActionData.accountingStatementId)) {
                    throw new RuntimeException("AsyncActionData não está com a origem deletada. [${asyncActionData.asyncActionId}].")
                }

                AsaasErpAccountingStatement asaasErpAccountingStatement = AsaasErpAccountingStatement.get(asyncActionData.accountingStatementId)
                asaasErpAccountingStatement.status = AsaasErpAccountingStatementStatus.REMOVED
                asaasErpAccountingStatement.save(failOnError: true)

                if (!asyncActionData.bypassSynchronization && asaasErpAccountingStatement.type.isCredit()) {
                    AsaasErpAccountingStatementAdapter asaasErpAccountingStatementAdapter = new AsaasErpAccountingStatementAdapter(asaasErpAccountingStatement, null)
                    asaasErpAccountingStatementAdapterList.add(asaasErpAccountingStatementAdapter)
                }
            } catch (Exception exception) {
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("AsaasErpService.processAccountsReceivableDeleteQueue  >> Ocorreu um Lock durante o processamento da asyncAction [${asyncActionId}]", exception)
                } else {
                    asyncActionErrorList.add(asyncActionId)
                    AsaasLogger.error("AsaasErpService.processAccountsReceivableDeleteQueue  >>  Ocorreu um erro durante o processamento da asyncAction [${asyncActionId}]", exception)
                }

                throw exception
            }
        }, [logErrorMessage: "AsaasErpService.processAccountsReceivableDeleteQueue >> Falha ao processar remoção de contas a receber/pagar.",
            appendBatchToLogErrorMessage: true,
            onEachTransactionEnd: { List<Long> batchAsyncActionIdList ->
                if (asaasErpAccountingStatementAdapterList) asaasErpAccountingStatementManagerService.delete(asaasErpAccountingStatementAdapterList)

                asyncActionService.deleteList(batchAsyncActionIdList)
            }]
        )

        if (asyncActionErrorList) asyncActionService.setListAsError(asyncActionErrorList)
    }

    public String buildAsaasErpLoginUrl(Long customerId, Long userId, Boolean userHasFinancialModulePermission) {
        AsaasErpUserConfig asaasErpUserConfig = AsaasErpUserConfig.query([userId: userId]).get()
        String apiKey = asaasErpUserConfig?.getDecryptedApiKey()

        if (!apiKey && userHasFinancialModulePermission) {
            AsaasLogger.info("AsaasErpService.buildAsaasErpLoginUrl >> Usuário[${userId}] sem chave de api configurada. Utilizando a chave do cliente[${customerId}].")

            AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customerId: customerId]).get()
            apiKey = asaasErpCustomerConfig?.getDecryptedApiKey()
        }

        if (!apiKey) throw new BusinessException("Usuário não vinculado ao ERP!")

        String asaasErpUrl = " ${AsaasApplicationHolder.getConfig().asaaserp.baseUrl}/#/pages/auth/login?x-api-key=${apiKey}"
        return asaasErpUrl
    }

    public void setCustomersAsIntegratedIfEndedIntegrationProcess() {
        final Integer maxNonIntegratedCustomersPerExecution = 100
        List<Long> nonIntegratedCustomerIdList = AsaasErpCustomerConfig.query([fullyIntegrated: false, column: "customer.id"]).list(max:maxNonIntegratedCustomersPerExecution)

        for (Long nonIntegratedCustomerId in nonIntegratedCustomerIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                setCustomersAsIntegratedIfPossible(nonIntegratedCustomerId)
            }, [logErrorMessage: "AsaasErpService.setCustomersAsIntegratedIfEndedIntegrationProcess >> Erro ao marcar AsaasErpCustomerConfig como integrado id[${nonIntegratedCustomerId}]",
                logLockAsWarning: true])
        }
    }

    public void processCustomerIntegrationToAsaasErp() {
        for (Map asyncActionData in asyncActionService.listPendingSendCustomerToAsaasErp()) {
            Utils.withNewTransactionAndRollbackOnError ({
                integrateCustomer(Utils.toLong(asyncActionData.customerId))
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AsaasErpService.processCustomerIntegrationToAsaasErp >> Erro na integração do cliente [${asyncActionData.customerId}] para o AsaasERP. ID: [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }

    public void createCustomerRetroactiveItems() {
        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.CREATE_RETROACTIVE_ITEMS_TO_ASAAS_ERP, null)) {
            Utils.withNewTransactionAndRollbackOnError ({
                createRetroactiveItems(Utils.toLong(asyncActionData.customerId))
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AsaasErpService.createCustomerRetroactiveItems >> Erro na criação das filas de items retroativos do cliente [${asyncActionData.customerId}] para o AsaasERP. ID: [${asyncActionData.asyncActionId}]",
                logLockAsWarning: true,
                onError: { Exception exception -> if (!Utils.isLock(exception)) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }

    public Boolean isAsaasErpRequest() {
        if (!ApiBaseParser.isAsaasErp()) return false

        String userApiKeyId = RequestUtils.getParameter("userApiKeyId")

        if (!userApiKeyId) return false

        UserApiKey userApiKey = UserApiKey.read(Utils.toLong(userApiKeyId))

        return isAsaasErpRequest(userApiKey)
    }

    public Boolean isAsaasErpRequest(UserApiKey userApiKey) {
        if (!ApiBaseParser.isAsaasErp()) return false
        if (userApiKey?.device != AsaasErpCustomerConfig.ASAAS_ERP_DEVICE) return false

        return true
    }

    public Boolean canActivateErpIntegration() {
        User currentUser = UserUtils.getCurrentUser()

        if (SpringSecurityUtils.ifAllGranted('ROLE_SYSADMIN')) return true

        if (AsaasEnvironment.isSandbox()) return false

        if (!ModulePermission.admin()) return false

        if (CustomerPartnerApplication.hasBradesco(currentUser.customer.id)) return false

        if (!currentUser.customer.isLegalPerson()) return false

        if (currentUser.customer.isNotFullyApproved()) return false

        if (!currentUser.customer.hasReceivedPayments()) return false

        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(currentUser.customer.id)
        if (asaasErpCustomerConfigCacheVO.fullyIntegrated) return false

        return true
    }

    public Boolean isAsaasErpMaintenance() {
        RBucket<Boolean> isEnabledBucket = RedissonProxy.instance.getBucket("AsaasErpMaintenance:isEnabled", Boolean)
        return isEnabledBucket?.get().asBoolean()
    }

    private void setCustomersAsIntegratedIfPossible(Long customerId) {
        AsaasErpCustomerConfig nonIntegratedCustomer = AsaasErpCustomerConfig.query([customerId: customerId]).get()

        if (!nonIntegratedCustomer.paymentIntegrated) {
            Boolean hasRetroactivePaymentsToIntegrate = asaasErpAccountingStatementService.hasRetroactivePaymentsToIntegrate(nonIntegratedCustomer)
            nonIntegratedCustomer.paymentIntegrated = !hasRetroactivePaymentsToIntegrate
            asaasErpAccountingStatementService.setRetroactiveIntegrationAsFinishedIfNecessary(nonIntegratedCustomer)
        }

        if (!nonIntegratedCustomer.customerAccountIntegrated) {
            Boolean hasRetroactiveCustomerAccountToIntegrate = asaasErpCustomerAccountService.hasRetroactiveCustomerAccountToIntegrate(nonIntegratedCustomer)
            nonIntegratedCustomer.customerAccountIntegrated = !hasRetroactiveCustomerAccountToIntegrate
            asaasErpCustomerAccountService.setRetroactiveIntegrationAsFinishedIfNecessary(nonIntegratedCustomer)
        }

        nonIntegratedCustomer.fullyIntegrated = nonIntegratedCustomer.customerAccountIntegrated && nonIntegratedCustomer.paymentIntegrated
        nonIntegratedCustomer.save(failOnError: true)

        if (nonIntegratedCustomer.fullyIntegrated) {
            asaasErpCustomerConfigCacheService.evict(customerId)

            FinancialTransaction firstFinancialTransaction = FinancialTransaction.query([customerId: customerId, sort: "id", order: "asc"]).get()
            if (firstFinancialTransaction) {
                asaasErpFinancialTransactionNotificationService.save(nonIntegratedCustomer, firstFinancialTransaction)
            } else {
                asaasErpFinancialTransactionNotificationService.sendNotificationForCustomerWithoutFinancialTransaction(nonIntegratedCustomer)
            }

            createCampaignEventMessageService.saveForCustomerIntegratedWithErp(customerId)

            customerAlertNotificationService.notifyBaseErpIntegrated(nonIntegratedCustomer.customer)
        }
    }

    private String getIntegrationUsername(Customer customer) {
        String username = User.admin(customer, [:]).get().username
        String user = username.split("@")[0]
        String domain = username.split("@")[1]

        return "${user}+${customer.id}+asaaserp@${domain}"
    }
}
