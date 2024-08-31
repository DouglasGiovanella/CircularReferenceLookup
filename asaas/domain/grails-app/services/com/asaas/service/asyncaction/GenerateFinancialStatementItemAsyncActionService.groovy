package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.builder.AsyncActionDataDeserializer
import com.asaas.domain.asyncAction.FinancialTransactionAfterSaveAsyncAction
import com.asaas.domain.asyncAction.GenerateFinancialStatementItemAsyncAction
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import groovy.json.JsonSlurper
import org.hibernate.SQLQuery
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class GenerateFinancialStatementItemAsyncActionService {

    def baseAsyncActionService
    def financialStatementItemForDomainObjectService
    def grailsApplication

    public void save(Object domainObject, FinancialStatementType financialStatementType, Date statementDate, Long bankId) {
        GenerateFinancialStatementItemAsyncAction asyncAction = new GenerateFinancialStatementItemAsyncAction()
        baseAsyncActionService.save(asyncAction, [
            domainObjectType: domainObject.class.simpleName,
            domainObjectId: domainObject.id,
            financialStatementType: financialStatementType,
            statementDate: CustomDateUtils.fromDate(statementDate),
            bankId: bankId
        ])
        AsaasLogger.info("GenerateFinancialStatementItemAsyncActionService.save >> asyncAction: [${asyncAction.id}] domainObjectType: [${domainObject.class.simpleName}] domainObjectId: [${domainObject.id}] financialStatementType: [${financialStatementType}] statementDate: [${statementDate}] bankId: [${bankId}]")
    }

    public void processPendingAsyncAction() {
        final Integer maxNumberOfAsyncActions = 4800
        List<Map> generateFinancialStatementItemAsyncActionMapList = getPendingActionList(maxNumberOfAsyncActions)

        if (!generateFinancialStatementItemAsyncActionMapList) return

        JsonSlurper jsonSlurper = new JsonSlurper()
        List<Map> parsedAsyncActionMapList = generateFinancialStatementItemAsyncActionMapList.collect { AsyncActionDataDeserializer.buildDataMap(jsonSlurper, it.actionData, Utils.toLong(it.id)) }

        final Integer minItemsPerThread = 300
        final Integer flushEvery = 100
        final Integer batchSize = 100

        ThreadUtils.processWithThreadsOnDemand(parsedAsyncActionMapList, minItemsPerThread, { List<Map> items ->
            List<Long> asyncActionToDeleteList = []
            List<Long> asyncActionErrorList = []

            Utils.forEachWithFlushSessionAndNewTransactionInBatch(items, batchSize, flushEvery, { Map generateFinancialStatementItemAsyncActionMap ->
                try {
                    Long domainObjectId = generateFinancialStatementItemAsyncActionMap.domainObjectId
                    Object domainObject = null

                    switch (generateFinancialStatementItemAsyncActionMap.domainObjectType) {
                        case "FinancialTransaction":
                            domainObject = FinancialTransaction.load(domainObjectId)
                            break
                    }

                    if (!domainObject) throw new RuntimeException("GenerateFinancialStatementItemAsyncActionService.processPendingAsyncAction >> Não foi possível obter o domainObject da asyncAction. AsyncAction: [${generateFinancialStatementItemAsyncActionMap.asyncActionId}] domainObject: [${generateFinancialStatementItemAsyncActionMap.domainObjectType}] domainObjectId: [${generateFinancialStatementItemAsyncActionMap.domainObjectId}]")

                    FinancialStatementType financialStatementType = generateFinancialStatementItemAsyncActionMap.financialStatementType
                    Date statementDate = CustomDateUtils.fromString(generateFinancialStatementItemAsyncActionMap.statementDate)

                    if (financialStatementType) {
                        saveFinancialStatementItem(domainObject, financialStatementType, statementDate, generateFinancialStatementItemAsyncActionMap.bankId)
                    } else {
                        saveFinancialStatementItemForDomainObject(domainObject)
                    }

                    asyncActionToDeleteList.add(generateFinancialStatementItemAsyncActionMap.asyncActionId)
                } catch (Exception exception) {
                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("GenerateFinancialStatementItemAsyncActionService.processPendingAsyncAction >> Ocorreu um Lock durante o processamento da asyncAction [${generateFinancialStatementItemAsyncActionMap.asyncActionId}]", exception)
                    } else {
                        asyncActionErrorList.add(generateFinancialStatementItemAsyncActionMap.asyncActionId)
                        AsaasLogger.error("GenerateFinancialStatementItemAsyncActionService.processPendingAsyncAction >> asyncAction: [${generateFinancialStatementItemAsyncActionMap.asyncActionId}]", exception)
                    }
                }
            }, [logErrorMessage: "GenerateFinancialStatementItemAsyncActionService.processPendingAsyncAction >> Erro ao processar asyncActions em lote", appendBatchToLogErrorMessage: true])

            final Integer idListOperationCollateSize = 500
            for (List<Long> asyncActionErrorPartialList : asyncActionErrorList.collate(idListOperationCollateSize)) {
                baseAsyncActionService.updateStatus(GenerateFinancialStatementItemAsyncAction, asyncActionErrorPartialList, AsyncActionStatus.ERROR)
            }

            for (List<Long> asyncActionIdProcessedPartialList : asyncActionToDeleteList.collate(idListOperationCollateSize)) {
                baseAsyncActionService.deleteList(GenerateFinancialStatementItemAsyncAction, asyncActionIdProcessedPartialList)
            }
        })
    }

    private List<Map> getPendingActionList(Integer limit) {
        FinancialTransactionAfterSaveAsyncAction.withSession { session ->
            final String queuesSchemaName = grailsApplication.config.asaas.database.schema.queues.name

            String sql = "SELECT id, action_data as actionData FROM ${queuesSchemaName}.generate_financial_statement_item_async_action gfsiaa FORCE INDEX(primary) WHERE gfsiaa.status = :status and gfsiaa.deleted = false LIMIT :limit"
            SQLQuery query = session.createSQLQuery(sql)
            query.setString("status", AsyncActionStatus.PENDING.toString())
            query.setLong("limit", limit)
            query.setResultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
            return query.list()
        }
    }

    private void saveFinancialStatementItem(Object domainObject, FinancialStatementType financialStatementType, Date statementDate, Long bankId) {
        Bank bank = null
        if (bankId) bank = Bank.load(bankId)

        financialStatementItemForDomainObjectService.save(domainObject, financialStatementType, statementDate, bank)
    }

    private void saveFinancialStatementItemForDomainObject(Object domainObject) {
        switch (domainObject) {
            case { it instanceof FinancialTransaction }:
                financialStatementItemForDomainObjectService.saveForFinancialTransaction(domainObject)
                break
            default:
                throw new RuntimeException("GenerateFinancialStatementItemAsyncActionService.saveFinancialStatementItemForDomainObject >> Não foi possível gerar lançamento para o tipo de domainObject: [${domainObject.class.simpleName}]")
        }
    }
}
