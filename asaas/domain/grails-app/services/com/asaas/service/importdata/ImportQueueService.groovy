package com.asaas.service.importdata

import com.asaas.base.importdata.BaseImportGroup
import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.importdata.ImportQueue
import com.asaas.exception.BusinessException
import com.asaas.importdata.ImportQueueStatus
import com.asaas.importdata.ImportStage
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ImportQueueService {

    def importService

    public BaseImportGroup getImportGroup(ImportQueue importQueue) {
        BaseImportGroup importGroupInstance = importService.getImportGroupInstance(importQueue.object).newInstance()
        return importGroupInstance.get(importQueue.objectId)
    }

    public ImportQueue resumeGroupProcessing(BaseImportGroup importGroup, Boolean importItemsWithWarning) {
        Map search = [object: importGroup.getClass().simpleName, objectId: importGroup.id, status: ImportQueueStatus.INTERRUPTED]
        ImportQueue importQueue = ImportQueue.query(search).get()

        try {
            BusinessValidation validatedBusiness = validateResumeGroupProcessing(importGroup)
            if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

            importService.setAsAwaitingImport(importGroup)
            importService.saveImportItemsWithWarning(importGroup, importItemsWithWarning)
            sendToNextStageIfNecessary(importQueue)

            return importQueue
        } catch (BusinessException businessException) {
            importService.setAsFailedImport(importGroup)
            setAsFailed(importQueue)

            return DomainUtils.addError(new ImportQueue(), businessException.getMessage())
        }
    }

    public ImportQueue save(BaseImportGroup importGroup) {
        ImportStage stage = getImportStage(importGroup.status)

        BusinessValidation validatedBusiness = validateSave(importGroup, stage)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new ImportQueue(), validatedBusiness.getFirstErrorMessage())

        ImportQueue importQueue = new ImportQueue()
        importQueue.customer = importGroup.customer
        importQueue.object = importGroup.class.simpleName
        importQueue.objectId = importGroup.id
        importQueue.stage = stage
        importQueue.save(failOnError: true)

        return importQueue
    }

    public void sendToNextStageIfNecessary(ImportQueue importQueue) {
        BaseImportGroup importGroup = getImportGroup(importQueue)

        if (importGroup.isFailed()) {
            setAsFailed(importQueue)
            return
        }

        ImportStage stage = getImportStage(importGroup.status)
        if (!stage) {
            setAsFinished(importQueue)
            return
        }

        importQueue.stage = stage
        importQueue.status = ImportQueueStatus.PENDING
        importQueue.save(failOnError: true)
    }

    public void setAsFailed(ImportQueue importQueue) {
        importQueue.status = ImportQueueStatus.FAILED
        importQueue.save(failOnError: true)
    }

    public void setAsFinished(ImportQueue importQueue) {
        importQueue.status = ImportQueueStatus.FINISHED
        importQueue.save(failOnError: true)
    }

    public void setAsInterrupted(ImportQueue importQueue) {
        importQueue.status = ImportQueueStatus.INTERRUPTED
        importQueue.save(failOnError: true)
    }

    public void setAsProcessing(ImportQueue importQueue) {
        importQueue.status = ImportQueueStatus.PROCESSING
        importQueue.save(failOnError: true)
    }

    public ImportQueue stopGroupProcessing(BaseImportGroup importGroup) {
        Map search = [object: importGroup.getClass().simpleName, objectId: importGroup.id, "status[in]": [ImportQueueStatus.PENDING, ImportQueueStatus.PROCESSING]]
        ImportQueue importQueue = ImportQueue.query(search).get()

        try {
            BusinessValidation validatedBusiness = validateStopGroupProcessing(importGroup)
            if (!validatedBusiness.isValid()) return DomainUtils.addError(new ImportQueue(), validatedBusiness.getFirstErrorMessage())

            importService.setAsInterrupted(importGroup)
            setAsInterrupted(importQueue)

            return importQueue
        } catch (BusinessException businessException) {
            AsaasLogger.error("ImportQueueService.stopGroupProcessing >> Falha ao interromper o processamento do grupo [${importGroup.id}]", businessException)
        }
    }

    private ImportStage getImportStage(ImportStatus status) {
        switch (status) {
            case ImportStatus.AWAITING_FILE_EXTRACTION:
                return ImportStage.FILE_EXTRACTION
            case ImportStatus.PENDING_VALIDATION:
                return ImportStage.VALIDATION
            case ImportStatus.AWAITING_IMPORT:
                return ImportStage.PROCESSING
        }

        return null
    }

    private BusinessValidation validateResumeGroupProcessing(BaseImportGroup importGroup) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!importGroup.isInterrupted()) {
            validatedBusiness.addError("baseImport.error.groupIsNotInterrupted")
            return validatedBusiness
        }

        if (!importGroup.countNotImportedItems()) {
            validatedBusiness.addError("baseImport.error.noDataToImport")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation validateStopGroupProcessing(BaseImportGroup importGroup) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!importGroup.status.isInterruptible()) {
            validatedBusiness.addError("baseImport.error.interruptGroup")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation validateSave(BaseImportGroup importGroup, ImportStage stage) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!stage) {
            validatedBusiness.addError("importQueue.error.invalidStage")
            return validatedBusiness
        }

        Boolean isImportGroupOnQueueAlready = ImportQueue.query([exists: true, object: importGroup.class.simpleName, objectId: importGroup.id, "status[in]": [ImportQueueStatus.PENDING, ImportQueueStatus.PROCESSING]]).get().asBoolean()
        if (isImportGroupOnQueueAlready) {
            validatedBusiness.addError("importQueue.error.importGroupOnQueueAlready")
            return validatedBusiness
        }

        return validatedBusiness
    }
}
