package com.asaas.service.asaaserp

import com.asaas.asaaserp.AsaasErpAccountingStatementAccountingType
import com.asaas.asaaserp.AsaasErpAccountingStatementBuilder
import com.asaas.asyncaction.AsyncActionType
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.cache.asaaserpcustomerconfig.AsaasErpCustomerConfigCacheVO
import com.asaas.domain.api.ApiRequestLogOriginEnum
import com.asaas.domain.asaaserp.AsaasErpAccountingStatement
import com.asaas.domain.asaaserp.AsaasErpCustomerAccount
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.exception.AsaasErpUndefinedErrorException
import com.asaas.integration.asaaserp.adapter.accountingstatement.AsaasErpAccountingStatementAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasErpAccountingStatementService {

    def asaasErpAccountingStatementManagerService
    def asaasErpFinancialTransactionNotificationService
    def asaasErpCustomerConfigCacheService
    def asyncActionService

    public void processAccountingStatementQueueWithThread(Integer maxNumberOfGroupIdPerExecution, Integer itemsPerThread, AsyncActionType asyncActionType) {
        List<String> groupIdList = listPendingAccountingStatementAsyncActionGroupId(asyncActionType, maxNumberOfGroupIdPerExecution)

        ThreadUtils.processWithThreadsOnDemand(groupIdList, itemsPerThread, { List<String> subGroupIdList ->
            List<Long> asyncActionIdList = AsyncAction.oldestPending([column: "id", "groupId[in]": subGroupIdList, type: asyncActionType]).list(max: itemsPerThread)
            if (!asyncActionIdList) return

            final Integer batchSize = 10
            final Integer flushEvery = 10
            List<Long> asyncActionErrorList = []
            List<Long> asyncActionToReprocessList = []
            List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterInProcessList = []
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionIdList, batchSize, flushEvery, { Long asyncActionId ->
                try {
                    AsyncAction asyncAction = AsyncAction.read(asyncActionId)
                    Map asyncActionData = asyncAction.getDataAsMap()

                    Boolean paymentAlreadyProcessed = asaasErpAccountingStatementAdapterInProcessList.find { it.paymentId == asyncActionData.paymentId.toString() }.asBoolean()
                    if (paymentAlreadyProcessed) return

                    List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterSubList = processAccountingStatementIntegration(asyncActionType, asyncActionData)
                    if (asaasErpAccountingStatementAdapterSubList) asaasErpAccountingStatementAdapterInProcessList.addAll(asaasErpAccountingStatementAdapterSubList)
                } catch (AsaasErpUndefinedErrorException asaasErpUndefinedErrorException) {
                    final Integer currentAttemptCount = incrementProcessingAttemptsCounter(asyncActionId)
                    if (!shouldReprocessAsyncAction(currentAttemptCount)) {
                        AsaasLogger.warn("AsaasErpAccountingStatementService.processAccountingStatementQueueWithThread  >> A asyncAction atingiu o número máximo de tentativas de processamento [asyncActionId: ${asyncActionId}].", asaasErpUndefinedErrorException)
                        asyncActionErrorList.add(asyncActionId)

                        throw asaasErpUndefinedErrorException
                    }

                    asyncActionToReprocessList.add(asyncActionId)
                } catch (Exception exception) {
                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("AsaasErpAccountingStatementService.processAccountingStatementQueueWithThread  >> Ocorreu um Lock durante o processamento da asyncAction [${asyncActionId}]", exception)
                    } else {
                        asyncActionErrorList.add(asyncActionId)
                        AsaasLogger.error("AsaasErpAccountingStatementService.processAccountingStatementQueueWithThread  >>  Ocorreu um erro durante o processamento da asyncAction [${asyncActionId}]", exception)
                    }

                    throw exception
                }
            }, [ignoreStackTrace: true,
                onEachTransactionEnd: { List<Long> batchAsyncActionIdList -> sendAccountingStatementBatch(asaasErpAccountingStatementAdapterInProcessList, batchAsyncActionIdList, asyncActionToReprocessList) }])

            if (asyncActionErrorList) asyncActionService.setListAsError(asyncActionErrorList)
        })
    }

    public void onFinancialTransactionCreate(FinancialTransaction financialTransaction) {
        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customer: financialTransaction.provider]).get()
        if (!asaasErpCustomerConfig) return

        if (asaasErpCustomerConfig.fullyIntegrated) asaasErpFinancialTransactionNotificationService.save(asaasErpCustomerConfig, financialTransaction)
    }

    public void onPaymentCreate(Customer customer, Payment payment, String asaasErpAccountingStatementExternalId) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customer.id)
        if (!asaasErpCustomerConfigCacheVO.id) return

        saveAsyncActionToSendItemToAsaasErp([paymentId: payment.id, paymentStatus: payment.status, asaasErpAccountingStatementExternalId: asaasErpAccountingStatementExternalId], payment.id.toString())
    }

    public void onPaymentUpdate(Customer customer, Payment payment) {
        onPaymentUpdate(customer, payment, null, false)
    }

    public void onPaymentUpdate(Customer customer, Payment payment, String asaasErpAccountingStatementExternalId, Boolean isRecover) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customer.id)
        if (!asaasErpCustomerConfigCacheVO.id) return

        AsaasErpAccountingStatement accountingStatement = AsaasErpAccountingStatement.query([payment: payment]).get()
        if (!accountingStatement) {
            onPaymentCreate(customer, payment, asaasErpAccountingStatementExternalId)
            return
        }

        Boolean isAccountingStatementReceivedAlreadyIntegrated = payment.status.isReceived() && accountingStatement.status.isReceived()
        if (isAccountingStatementReceivedAlreadyIntegrated) return

        Boolean originAsaasErp = ApiRequestLogOriginEnum.convert(RequestUtils.getRequestOrigin())?.isAsaasErp()

        saveAsyncActionToSendItemToAsaasErp([paymentId: payment.id, paymentStatus: payment.status, asaasErpAccountingStatementExternalId: asaasErpAccountingStatementExternalId, bypassSynchronization : originAsaasErp, isRecover: isRecover], payment.id.toString())
    }

    public void onPaymentDelete(Customer customer, Payment payment) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customer.id)
        if (!asaasErpCustomerConfigCacheVO.id) return

        AsaasErpAccountingStatement accountingStatement = AsaasErpAccountingStatement.query([payment: payment]).get()
        if (!accountingStatement) return

        Boolean originAsaasErp = ApiRequestLogOriginEnum.convert(RequestUtils.getRequestOrigin())?.isAsaasErp()

        Map asyncActionData = [accountingStatementId: accountingStatement.id, bypassSynchronization: originAsaasErp]
        AsyncActionType asyncActionType = AsyncActionType.SEND_DELETE_ASAAS_ERP_ACCOUNTING_STATEMENT

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void saveForNotIntegrated(Long customerId) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerId)
        if (!asaasErpCustomerConfigCacheVO.id) return

        List<Long> paymentIdListToIntegrate = Payment.query([column: "id", customerId: customerId, 'asaasErpAccountingStatement[notExists]': true, order: "asc", sort: "id"]).list()
        saveForNotIntegratedPayments(paymentIdListToIntegrate)
    }

    public Boolean hasRetroactivePaymentsToIntegrate(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        Boolean hasRetroactivePaymentsToIntegrate = Payment.query([ exists: true, customerId: asaasErpCustomerConfig.customerId, 'dateCreated[lt]': asaasErpCustomerConfig.dateCreated,  'asaasErpAccountingStatement[notExists]': true]).get().asBoolean()
        return hasRetroactivePaymentsToIntegrate
    }

    public Boolean isAsyncActionOriginDeleted(Long paymentId, Long accountingStatementId) {
        def domainInstance
        if (paymentId) {
            domainInstance = Payment.get(paymentId)
        } else if (accountingStatementId) {
            AsaasErpAccountingStatement accountingStatement = AsaasErpAccountingStatement.get(accountingStatementId)
            if (accountingStatement.payment) domainInstance = accountingStatement.payment
        } else {
            return true
        }

        if (!domainInstance) return true
        if (domainInstance.deleted) return true

        return false
    }

    public void setRetroactiveIntegrationAsFinishedIfNecessary(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        if (!asaasErpCustomerConfig.paymentIntegrated) return

        asaasErpAccountingStatementManagerService.setRetroactiveAsFinished(asaasErpCustomerConfig)
    }

    private List<String> listPendingAccountingStatementAsyncActionGroupId(AsyncActionType asyncActionType, Integer max) {
        return AsyncAction.oldestPending([distinct: "groupId", "groupId[isNotNull]": true, type: asyncActionType, disableSort: true, includeDeleted: true]).list(max: max)
    }

    private Integer incrementProcessingAttemptsCounter(Long asyncActionId) {
        AsyncAction asyncAction = AsyncAction.get(asyncActionId)
        Map actionData = asyncAction.getDataAsMap()
        actionData.attempt = actionData.attempt ? actionData.attempt + 1 : 1
        asyncAction.actionData = AsyncActionDataBuilder.parseToJsonString(actionData)
        asyncAction.save(failOnError: true)

        return actionData.attempt
    }

    private Boolean shouldReprocessAsyncAction(Integer currentAttemptCount) {
        final Integer maxAttempts = 3
        Boolean shouldReprocessAsyncAction = currentAttemptCount < maxAttempts

        return shouldReprocessAsyncAction
    }

    private List<AsaasErpAccountingStatementAdapter> processAccountingStatementIntegration(AsyncActionType asyncActionType, Map asyncActionData) {
        if (isAsyncActionOriginDeleted(asyncActionData.paymentId, asyncActionData.accountingStatementId)) return

        if (asyncActionData.financialTransactionId) throw new AsaasErpUndefinedErrorException("AsyncActionData possui financialTransactionId.  [${asyncActionData}].")

        List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterList = processWithPayment(asyncActionData.paymentId, asyncActionData.asaasErpAccountingStatementExternalId, asyncActionData.bypassSynchronization, asyncActionData.isRecover, asyncActionType.isSendRetroactiveAsaasErpAccountingStatement())

        return asaasErpAccountingStatementAdapterList
    }

    private List<AsaasErpAccountingStatementAdapter> processWithPayment(Long paymentId, String asaasErpAccountingStatementExternalId, Boolean bypassSynchronization, Boolean isRecover, Boolean isRetroactiveItem) {
        List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterList = []
        Payment payment = Payment.get(paymentId)
        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customer: payment.provider]).get()
        if (!asaasErpCustomerConfig) return

        AsaasErpCustomerAccount asaasErpCustomerAccount = AsaasErpCustomerAccount.query([asaasErpCustomerConfig: asaasErpCustomerConfig, customerAccount: payment.customerAccount, includeDeleted: true]).get()
        if (!asaasErpCustomerAccount) throw new AsaasErpUndefinedErrorException("Aguardando integração do pagador ${payment.customerAccount.id}.")

        AsaasErpAccountingStatement paymentStatement = AsaasErpAccountingStatement.query([payment: payment]).get()
        if (paymentStatement?.isRetroactiveItem && isRetroactiveItem) return

        paymentStatement = AsaasErpAccountingStatementBuilder.buildWithPayment(payment, asaasErpCustomerConfig, asaasErpCustomerAccount, paymentStatement, asaasErpAccountingStatementExternalId, isRecover, isRetroactiveItem)

        if (!paymentStatement) throw new AsaasErpUndefinedErrorException("Não foi criado registro de AsaasErpAccountingStatement.")

        Boolean shouldSendToAsaasErp = false
        if (!asaasErpAccountingStatementExternalId && !bypassSynchronization) {
            if (paymentStatement.type.isCredit()) shouldSendToAsaasErp = true
            paymentStatement.lastSyncDate = new Date()
        }

        if (shouldCreateAccountingStatementChild(payment)) {
            BigDecimal paidValueDifference = payment.value - payment.originalValue
            AsaasErpAccountingStatementAccountingType asaasErpAccountingStatementAccountingType = paidValueDifference > 0 ? AsaasErpAccountingStatementAccountingType.INTEREST_RECEIVED : AsaasErpAccountingStatementAccountingType.DISCOUNTS_GIVEN
            AsaasErpAccountingStatement statementChild = createAccountingStatementChild(paymentStatement, asaasErpAccountingStatementAccountingType, BigDecimalUtils.abs(paidValueDifference), payment.paymentDate, isRetroactiveItem)
            if (statementChild) {
                AsaasErpAccountingStatementAdapter asaasErpAccountingStatementAdapterChild = new AsaasErpAccountingStatementAdapter(statementChild, payment.publicId)
                asaasErpAccountingStatementAdapterList.add(asaasErpAccountingStatementAdapterChild)
            }
        }

        if (payment.status.isRefunded() || payment.status.isRefundRequested()) {
            AsaasErpAccountingStatementAdapter asaasErpAccountingStatementAdapterChild  = refundAccountingStatementChild(payment, paymentStatement, isRetroactiveItem)
            if (asaasErpAccountingStatementAdapterChild) asaasErpAccountingStatementAdapterList.add(asaasErpAccountingStatementAdapterChild)
        }

        paymentStatement.save(failOnError: true)

        if (shouldSendToAsaasErp) {
            AsaasErpAccountingStatementAdapter asaasErpAccountingStatementAdapter = new AsaasErpAccountingStatementAdapter(paymentStatement, null)
            asaasErpAccountingStatementAdapterList.add(asaasErpAccountingStatementAdapter)
        }

        return asaasErpAccountingStatementAdapterList
    }

    private void sendAccountingStatementBatch(List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterInProcessList, List<Long> batchAsyncActionIdList, List<Long> asyncActionToReprocessList) {
        if (asaasErpAccountingStatementAdapterInProcessList) {
            List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterToSendList = []
            asaasErpAccountingStatementAdapterToSendList.addAll(asaasErpAccountingStatementAdapterInProcessList)

            asaasErpAccountingStatementAdapterInProcessList.clear()

            asaasErpAccountingStatementManagerService.createOrUpdate(asaasErpAccountingStatementAdapterToSendList)
        }

        batchAsyncActionIdList.removeAll(asyncActionToReprocessList)
        asyncActionToReprocessList.clear()

        asyncActionService.deleteList(batchAsyncActionIdList)
    }

    private Boolean shouldCreateAccountingStatementChild(Payment payment) {
        if (!payment.originalValue) return false
        if (payment.originalValue == payment.value) return false
        if (payment.isReceived()) return true
        if (payment.isDunningReceived() && payment.getDunning().type.isCreditBureau()) return true

        return false
    }

    private void saveAsyncActionToSendItemToAsaasErp(Map asyncActionData, String asyncActionGroupId) {
        AsyncActionType asyncActionType = AsyncActionType.SEND_ASAAS_ERP_ACCOUNTING_STATEMENT
        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionGroupId, asyncActionData, [:])
    }

    private AsaasErpAccountingStatement createAccountingStatementChild(AsaasErpAccountingStatement accountingStatement, AsaasErpAccountingStatementAccountingType accountingType, BigDecimal value, Date accountStatementDate, Boolean isRetroactiveItem) {
        AsaasErpAccountingStatement statementChild = AsaasErpAccountingStatementBuilder.buildAccountingStatementChild(accountingStatement, accountingType, value, accountStatementDate, isRetroactiveItem)
        statementChild.lastSyncDate = new Date()
        statementChild.save(failOnError: true)

        accountingStatement.childAsaasErpAccountingStatement = statementChild

        if (!statementChild.type.isCredit()) return

        return statementChild
    }

    private AsaasErpAccountingStatementAdapter refundAccountingStatementChild(Payment payment, AsaasErpAccountingStatement accountingStatement, Boolean isRetroactiveItem) {
        if (!accountingStatement.childAsaasErpAccountingStatement) return

        AsaasErpAccountingStatement statementChild
        if (accountingStatement.childAsaasErpAccountingStatement.type.isCredit()) {
            Long statementChildId = accountingStatement.childAsaasErpAccountingStatement.id

            statementChild = AsaasErpAccountingStatement.get(statementChildId)
            statementChild.status = AsaasErpAccountingStatementBuilder.getAccountingStatementStatus(payment)
            statementChild.lastSyncDate = new Date()
            statementChild.save(failOnError: true)
        } else {
            statementChild = createAccountingStatementChild(accountingStatement.childAsaasErpAccountingStatement, AsaasErpAccountingStatementAccountingType.REVERSAL_OF_DISCOUNTS_GIVEN, accountingStatement.childAsaasErpAccountingStatement.value, payment.refundedDate, isRetroactiveItem)
            accountingStatement.childAsaasErpAccountingStatement.save(failOnError: true)
        }

        AsaasErpAccountingStatementAdapter asaasErpAccountingStatementAdapterChild = new AsaasErpAccountingStatementAdapter(statementChild, payment.publicId)

        return asaasErpAccountingStatementAdapterChild
    }

    private void saveForNotIntegratedPayments(List<Long> paymentIdListToIntegrate) {
        Utils.forEachWithFlushSession(paymentIdListToIntegrate, 50, { notIntegratedPaymentId ->
            Utils.withNewTransactionAndRollbackOnError({
                asyncActionService.save(AsyncActionType.SEND_RETROACTIVE_ASAAS_ERP_ACCOUNTING_STATEMENT, notIntegratedPaymentId.toString(), [paymentId: notIntegratedPaymentId], [allowDuplicatePendingWithSameParameters: true])
            }, [logErrorMessage: "processAccountingStatementIntegration >> saveForNotIntegratedPayments >> Falha ao processar saveForNotIntegrated do paymentId ${notIntegratedPaymentId}"])
        })
    }
}
