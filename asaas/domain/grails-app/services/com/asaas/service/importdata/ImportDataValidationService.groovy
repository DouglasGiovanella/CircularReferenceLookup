package com.asaas.service.importdata

import com.asaas.base.importdata.BaseImportGroup
import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.importdata.ImportQueue
import com.asaas.importdata.ImportQueueStatus
import com.asaas.importdata.ImportStage
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.async.Promise
import grails.transaction.Transactional

import static grails.async.Promises.task
import static grails.async.Promises.waitAll

@Transactional
class ImportDataValidationService {

    private final ImportStage IMPORT_STAGE = ImportStage.VALIDATION

    private final Integer MAX_PARALLEL_GROUP_TASKS = 3

    private final Integer MAX_PARALLEL_VALIDATION_TASKS = 3

    private final Integer MAX_ITEMS_PER_VALIDATION_TASK = 5000

    def importQueueService

    def importService

    public void setNextActiveImports() {
        Integer currentProcessing = ImportQueue.processing([exists: true, stage: IMPORT_STAGE]).count()
        if (currentProcessing >= MAX_PARALLEL_GROUP_TASKS) return

        Integer groupTasksAvailable = MAX_PARALLEL_GROUP_TASKS - currentProcessing
        for (Integer i = 0; i < groupTasksAvailable; i++) {
            ImportQueue importQueue = ImportQueue.nextImport([stage: IMPORT_STAGE]).get()
            if (!importQueue) break

            importQueueService.setAsProcessing(importQueue)

            BaseImportGroup importGroup = importQueueService.getImportGroup(importQueue)
            importService.setAsValidating(importGroup)
        }
    }

    public void processActiveImports() {
        List<Promise> promiseList = []

        List<Long> importQueueIdList = ImportQueue.query([column: "id", stage: IMPORT_STAGE, status: ImportQueueStatus.PROCESSING]).list()
        if (!importQueueIdList) return

        for (Long importQueueId in importQueueIdList) {
            promiseList << processGroup(importQueueId)
        }

        waitAll(promiseList)
    }

    private Promise processGroup(Long importQueueId) {
        return task {
            Utils.withNewTransactionAndRollbackOnError {
                ImportQueue importQueue = ImportQueue.get(importQueueId)
                BaseImportGroup importGroup = importQueueService.getImportGroup(importQueue)

                try {
                    List<Long> itemIdList = getItemIdList(importGroup)
                    if (itemIdList) {
                        List<Promise> promiseList = validateItems(itemIdList, importGroup)
                        waitAll(promiseList)
                    }
                } catch (Exception exception) {
                    AsaasLogger.error("ImportDataValidationService.processGroup >> Falha ao validar o grupo da fila [${importQueueId}]", exception)
                    importService.setAsFailedValidation(importGroup)
                    importQueueService.setAsFailed(importQueue)
                }
            }

            Utils.withNewTransactionAndRollbackOnError {
                ImportQueue importQueue = ImportQueue.get(importQueueId)
                BaseImportGroup importGroup = importQueueService.getImportGroup(importQueue)

                try {
                    if (!importGroup.countNotImportedItems()) throw new RuntimeException("Todos os itens do grupo falharam na validação")

                    if (importGroup.countItemsWithInconsistency() > 0 || importGroup.countFailedValidationItems() > 0) {
                        importService.setAsInterrupted(importGroup)
                        importQueueService.setAsInterrupted(importQueue)
                        return
                    }

                    importService.setAsAwaitingImport(importGroup)
                    importQueueService.sendToNextStageIfNecessary(importQueue)
                } catch (Exception exception) {
                    AsaasLogger.error("ImportDataValidationService.processGroup >> Falha ao enviar o grupo da fila [${importQueueId}] para a fila de processamento", exception)
                    importService.setAsFailedValidation(importGroup)
                    importQueueService.setAsFailed(importQueue)
                }
            }
        }
    }

    private List<Long> getItemIdList(BaseImportGroup importGroup) {
        Map search = [column: "id", status: ImportStatus.PENDING_VALIDATION]
        Integer max = MAX_ITEMS_PER_VALIDATION_TASK * MAX_PARALLEL_VALIDATION_TASKS

        return importGroup.getItemList(search, max)
    }

    private List<Promise> validateItems(List<Long> itemIdList, BaseImportGroup importGroup) {
        List<Promise> promiseList = []

        Class domainClass = importGroup.getClass()
        Long importGroupId = importGroup.id
        itemIdList.collate(MAX_ITEMS_PER_VALIDATION_TASK).each {
            List<Long> idList = it.collect()

            promiseList << task {
                Boolean hasFailedValidation = false
                for (Long id in idList) {
                    if (hasFailedValidation) break

                    Utils.withNewTransactionAndRollbackOnError {
                        try {
                            importService.validateItem(domainClass.simpleName, id)
                        } catch (Exception exception) {
                            BaseImportGroup group = domainClass.get(importGroupId)
                            importService.setAsFailedValidation(group)
                            hasFailedValidation = true
                        }
                    }
                }
            }
        }

        return promiseList
    }
}
