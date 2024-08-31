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
class ImportDataProcessingService {

    private final ImportStage IMPORT_STAGE = ImportStage.PROCESSING

    private final Integer MAX_PARALLEL_GROUP_TASKS = 3

    private final Integer MAX_PARALLEL_PROCESSING_TASKS = 3

    private final Integer MAX_ITEMS_PER_PROCESSING_TASK = 50

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
            importService.setAsProcessing(importGroup)
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
                    if (!itemIdList) throw new RuntimeException("Não há itens para importar")

                    List<Promise> promiseList = processItems(itemIdList, importGroup)
                    waitAll(promiseList)
                } catch (Exception exception) {
                    AsaasLogger.error("ImportDataProcessingService.processGroup >> Falha ao processar o grupo da fila [${importQueueId}]", exception)
                    importService.setAsFailedImport(importGroup)
                    importQueueService.setAsFailed(importQueue)
                }
            }

            Utils.withNewTransactionAndRollbackOnError {
                ImportQueue importQueue = ImportQueue.get(importQueueId)
                BaseImportGroup importGroup = importQueueService.getImportGroup(importQueue)

                try {
                    if (importGroup.countNotImportedItems() > 0) return
                    if (importGroup.isInterrupted()) {
                        importQueueService.setAsInterrupted(importQueue)
                        return
                    }

                    importService.setAsImported(importGroup)
                    importQueueService.sendToNextStageIfNecessary(importQueue)
                } catch (Exception exception) {
                    AsaasLogger.error("ImportDataProcessingService.processGroup >> Falha ao finalizar o processamento do grupo da fila [${importQueueId}]", exception)
                    importService.setAsFailedImport(importGroup)
                    importQueueService.setAsFailed(importQueue)
                }
            }
        }
    }

    private List<Long> getItemIdList(BaseImportGroup importGroup) {
        Map search = [column: "id", status: ImportStatus.AWAITING_IMPORT]
        Integer max = MAX_ITEMS_PER_PROCESSING_TASK * MAX_PARALLEL_PROCESSING_TASKS

        return importGroup.getItemList(search, max)
    }

    private List<Promise> processItems(List<Long> itemIdList, BaseImportGroup importGroup) {
        List<Promise> promiseList = []

        String domainName = importGroup.getClass().simpleName
        itemIdList.collate(MAX_ITEMS_PER_PROCESSING_TASK).each {
            List<Long> idList = it.collect()

            promiseList << task {
                for (Long id in idList) {
                    importService.processItemWithNewTransaction(domainName, id)
                }
            }
        }

        return promiseList
    }
}
