package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.BaseAsyncAction
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.asyncaction.builder.AsyncActionDataDeserializer
import com.asaas.domain.accountsecurityevent.AccountSecurityEventAsyncAction
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class BaseAsyncActionService {

    public BaseAsyncAction save(BaseAsyncAction baseAsyncAction, Map actionData) {
        validate(baseAsyncAction, actionData)
        if (baseAsyncAction.hasErrors()) throw new ValidationException("Não foi possível salvar a ação assíncrona com o conteúdo ${actionData}", baseAsyncAction.errors)

        baseAsyncAction.actionData = AsyncActionDataBuilder.parseToJsonString(actionData)
        baseAsyncAction.actionDataHash = AsyncActionDataBuilder.buildHash(baseAsyncAction.actionData)

        return baseAsyncAction.save(failOnError: true)
    }

    public Map getPending(Class<? extends BaseAsyncAction> domainClass, Map additionalSearchFilters) {
        Map search = additionalSearchFilters + [columnList: ["id", "actionData"]]

        Map asyncActionInfoMap = domainClass.oldestPending(search).get()
        if (!asyncActionInfoMap) return [:]

        return AsyncActionDataDeserializer.buildDataMap(asyncActionInfoMap.actionData, asyncActionInfoMap.id)
    }

    public List<Map> listPendingData(Class<? extends BaseAsyncAction> domainClass, Map additionalSearchFilters, Integer max) {
        Map search = additionalSearchFilters + [columnList: ["id", "actionData"]]

        List<Map> asyncActionInfoList = domainClass.oldestPending(search).list(max: max)
        if (!asyncActionInfoList) return []

        return AsyncActionDataDeserializer.buildDataMapList(asyncActionInfoList)
    }

    public void deleteList(Class<? extends BaseAsyncAction> domainClass, List<Long> idList) {
        if (!idList) return

        try {
            domainClass.executeUpdate("""
                DELETE FROM ${domainClass.simpleName}
                WHERE id IN (:idList)""",
                [idList: idList])
        } catch (Exception exception) {
            AsaasLogger.error("BaseAsyncActionService.deleteList >> Não foi possível deletar as ${domainClass.simpleName} [${idList}]", exception)
        }
    }

    public Boolean updateStatus(Class<? extends BaseAsyncAction> domainClass, List<Long> idList, AsyncActionStatus status) {
        if (!idList) return true

        try {
            domainClass.executeUpdate("""
                UPDATE ${domainClass.simpleName}
                    SET version = version + 1, last_updated = :now, status = :status
                WHERE id IN (:idList)""",
                [status: status, now: new Date(), idList: idList])

            return true
        } catch (Exception exception) {
            AsaasLogger.error("BaseAsyncActionService.updateStatus >> Não foi possível atualizar o status das ${domainClass.simpleName} para ${status} [${idList}]", exception)
            return false
        }
    }

    public void processListWithNewTransaction(Class<? extends BaseAsyncAction> domainClass, List<Map> asyncActionDataList, Closure closure) {
        processListWithNewTransaction(domainClass, asyncActionDataList, closure, [:])
    }

    public void processListWithNewTransaction(Class<? extends BaseAsyncAction> domainClass, List<Map> asyncActionDataList, Closure closure, Map options) {
        if (!asyncActionDataList) return

        Boolean updatedToProcessingSuccessfully = updateStatus(domainClass, asyncActionDataList.collect { it.asyncActionId }, AsyncActionStatus.PROCESSING)
        if (!updatedToProcessingSuccessfully) return

        List<Long> successfulIdList = []
        List<Long> errorIdList = []
        List<Long> retryIdList = []

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                closure.call(asyncActionData)
                successfulIdList.add(asyncActionData.asyncActionId)
            }, [logErrorMessage: options.logErrorMessage ?: "BaseAsyncActionService.processListWithNewTransactionAndRollbackOnError >> Não foi possível processar a ${domainClass.simpleName} [${asyncActionData.asyncActionId}]",
                onError: { Exception exception ->
                    if (options.shouldRetryOnErrorClosure && options.shouldRetryOnErrorClosure.call(exception)) {
                        retryIdList.add(asyncActionData.asyncActionId)
                    } else {
                        errorIdList.add(asyncActionData.asyncActionId)
                    }
                }
            ])
        }

        deleteList(domainClass, successfulIdList)
        updateStatus(domainClass, retryIdList, AsyncActionStatus.PENDING)
        updateStatus(domainClass, errorIdList, AsyncActionStatus.ERROR)
    }

    public Boolean hasAsyncActionPendingWithSameParameters(Class<? extends BaseAsyncAction> domainClass, Map actionData) {
        String actionDataJson = AsyncActionDataBuilder.parseToJsonString(actionData)
        String actionDataHash = AsyncActionDataBuilder.buildHash(actionDataJson)

        return domainClass.query([exists: true, actionDataHash: actionDataHash, status: AsyncActionStatus.PENDING]).get().asBoolean()
    }

    public void setAsErrorWithNewTransaction(List<Long> asyncActionIdList) {
        Utils.withNewTransactionAndRollbackOnError({
            updateStatus(AccountSecurityEventAsyncAction, asyncActionIdList, AsyncActionStatus.ERROR)
        }, [logErrorMessage: "BaseAsyncActionService.setAsErrorWithNewTransaction >> Não foi possível registrar o status de erro para as AsyncActions"])
    }

    private void validate(BaseAsyncAction baseAsyncAction, Map actionData) {
        if (!actionData) {
            DomainUtils.addError(baseAsyncAction, "O conteúdo da ação assíncrona não pode estar vazio")
        }

        if (!baseAsyncAction.allowDuplicate() && hasAsyncActionPendingWithSameParameters(baseAsyncAction.class, actionData)) {
            DomainUtils.addError(baseAsyncAction, "O conteúdo da ação assíncrona já existe na fila")
        }
    }
}
