package com.asaas.service.importdata

import com.asaas.base.importdata.BaseImportGroup
import com.asaas.base.importdata.BaseImportTemplate
import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportGroup
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.importdata.ImportQueue
import com.asaas.domain.payment.importdata.PaymentImportGroup
import com.asaas.domain.subscription.importdata.SubscriptionImportGroup
import com.asaas.exception.BusinessException
import com.asaas.export.customeraccount.CustomerAccountExporter
import com.asaas.export.payment.PaymentExporter
import com.asaas.exportdata.ExportDataFieldUtils
import com.asaas.importdata.CsvImporter
import com.asaas.importdata.ExcelImporter
import com.asaas.importdata.FileImporter
import com.asaas.importdata.ImportQueueStatus
import com.asaas.importdata.ImportStage
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.async.Promise
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

import static grails.async.Promises.task
import static grails.async.Promises.waitAll

@Transactional
class ImportDataFileExtractionService {

    private final ImportStage IMPORT_STAGE = ImportStage.FILE_EXTRACTION

    private final Integer MAX_COLLATED_ITEMS_PER_TASK = 5000

    private final Integer MAX_PARALLEL_GROUP_TASKS = 3

    private final Long MAX_FILE_SIZE_IN_MEGABYTES = 5

    private final Long MAX_FILE_SIZE_IN_BYTES = FileUtils.convertMegabyteToByte(MAX_FILE_SIZE_IN_MEGABYTES)

    private final String ASAAS_IMPORT_TEMPLATE_NAME = "Asaas importação"

    private final String ASAAS_EXPORT_TEMPLATE_NAME = "Asaas exportação"

    def fileService

    def grailsApplication

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
            importService.setAsExtractingFile(importGroup)
        }
    }

    public void processActiveImports() {
        List<Promise> promiseList = []

        List<ImportQueue> importQueueList = ImportQueue.query([stage: IMPORT_STAGE, status: ImportQueueStatus.PROCESSING]).list(readOnly: true)
        if (!importQueueList) return

        for (ImportQueue importQueue in importQueueList) {
            BaseImportGroup importGroup = importQueueService.getImportGroup(importQueue)
            importService.deleteItemsIfNecessary(importQueue.object, importGroup.id)

            promiseList << processGroup(importQueue.id)
        }

        waitAll(promiseList)
    }

    public BaseImportGroup upload(Class<BaseImportGroup> importGroupClass, Customer customer, CommonsMultipartFile file, String customerEmail) {
        BaseImportGroup interruptedImportGroup = importGroupClass.query([customer: customer, status: ImportStatus.INTERRUPTED, name: file.getOriginalFilename()]).get()
        if (interruptedImportGroup) return interruptedImportGroup

        BusinessValidation validatedBusiness = validateUpload(customer, file, customerEmail)
        if (!validatedBusiness.isValid()) return DomainUtils.addError(importGroupClass.newInstance(), validatedBusiness.getFirstErrorMessage())

        AsaasFile asaasFile = fileService.saveFile(customer, file, null)

        BaseImportGroup importGroup = importService.saveGroup(importGroupClass, customer, asaasFile)

        ImportQueue importQueue = importQueueService.save(importGroup)
        if (importQueue.hasErrors()) throw new BusinessException(importQueue.errors.allErrors[0].defaultMessage)

        return importGroup
    }

    private Promise processGroup(Long importQueueId) {
        return task {
            Utils.withNewTransactionAndRollbackOnError({
                ImportQueue importQueue = ImportQueue.get(importQueueId)
                BaseImportGroup importGroup = importQueueService.getImportGroup(importQueue)

                try {
                    AsaasFile asaasFile = importGroup.file
                    String domainName = importQueue.object

                    FileImporter fileImporter
                    if (asaasFile.isCsvExtension()) {
                        fileImporter = new CsvImporter(asaasFile)
                    } else {
                        fileImporter = new ExcelImporter(asaasFile)
                    }

                    List headerUnmapped = fileImporter.getHeaderUnmapped()
                    BaseImportTemplate importTemplate = getTemplate(headerUnmapped, domainName)
                    if (!importTemplate) throw new RuntimeException("Template [${headerUnmapped}] não reconhecido")

                    fileImporter.setColumnNames(importTemplate.getColumnMap())

                    Map headerMapped = fileImporter.getRowMapped(headerUnmapped)
                    if (hasHeader(headerMapped, domainName)) fileImporter.startAtRow(1)

                    processItems(fileImporter, importGroup, domainName)

                    importService.setAsPendingValidation(importGroup)
                    importQueueService.sendToNextStageIfNecessary(importQueue)
                } catch (Exception exception) {
                    AsaasLogger.error("ImportDataFileExtractionService.processGroup >> Falha ao ler o grupo da fila [${importQueueId}]", exception)
                    importQueueService.setAsFailed(importQueue)
                    importService.setAsFailedFileExtraction(importGroup)
                }
            }, [logErrorMessage: "ImportDataFileExtractionService.processGroup >> Erro inesperado ao ler a importação [${importQueueId}]"])
        }
    }

    private BaseImportTemplate getTemplate(List firstRow, String domainName) {
        if (!firstRow) return null

        String templateName = getTemplateNameBasedOnFirstRow(firstRow, domainName)
        Class templateClass = importService.getTemplateClass(domainName)
        BaseImportTemplate template = templateClass.query([name: templateName]).get()
        return template
    }

    private void processItems(FileImporter fileImporter, BaseImportGroup importGroup, String domainName) {
        List<Promise> promiseList = []
        fileImporter.forEachRowInBatch(MAX_COLLATED_ITEMS_PER_TASK) { List<Map> itemList ->
            Long groupId = importGroup.id
            promiseList << task {
                try {
                    Utils.forEachWithFlushSession(itemList, 50, { Map fileRow ->
                        List<Map> invalidInfoList = validateFormatFields(fileRow, importGroup)

                        if (invalidInfoList) {
                            Map invalidItem = [item: fileRow, invalidInfoList: invalidInfoList]
                            importService.saveItemWithExtractionInconsistency(domainName, groupId, invalidItem)
                        } else {
                            importService.saveItem(domainName, fileRow, groupId)
                        }
                    })
                } catch (Exception exception) {
                    AsaasLogger.error("ImportDataFileExtractionService.processItems >> Falha ao salvar os itens do grupo [${groupId}]", exception)
                }
            }
        }

        waitAll(promiseList)
    }

    private BusinessValidation validateUpload(Customer customer, CommonsMultipartFile file, String customerEmail) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (file.empty) {
            validatedBusiness.addError("baseImport.error.fileEmpty")
            return validatedBusiness
        }

        if (UserUtils.isReauthenticatedAdminUser() && customer.email != customerEmail) {
            validatedBusiness.addError("baseImport.error.emailIsNotEqualUserEmail")
            return validatedBusiness
        }

        if (file.getSize() > MAX_FILE_SIZE_IN_BYTES) {
            validatedBusiness.addError("baseImport.error.fileExceedMaxSize", [MAX_FILE_SIZE_IN_MEGABYTES])
            return validatedBusiness
        }

        String fileExtension = FileUtils.getFileExtension(file.getOriginalFilename())
        List<String> supportedFileExtensionList = grailsApplication.config.asaas.importData.supportedFileExtensionList
        if (!supportedFileExtensionList.contains(fileExtension)) {
            validatedBusiness.addError("baseImport.error.unsupportedExtension", supportedFileExtensionList.collect { it.toUpperCase() })
            return validatedBusiness
        }

        return validatedBusiness
    }

    private List<Map> validateFormatFields(Map fileRow, BaseImportGroup importGroup) {
        List<Map> invalidInfoList = []
        importGroup.buildValidationConfig().each { String fieldName, Map config ->
            if (!config.validation.call(fileRow."${fieldName}")) invalidInfoList << [field: fieldName, inconsistencyType: config.inconsistencyType]
        }

        return invalidInfoList
    }

    private Boolean hasHeader(Map fileRow, String domainName) {
        if (domainName == CustomerAccountImportGroup.simpleName && CpfCnpjUtils.validate(fileRow.cpfCnpj)) return false

        if (fileRow.any { it.toString().matches(".*[0-9]+.*") }) return false

        return true
    }

    private String getTemplateNameBasedOnFirstRow(List firstRow, String domainName) {
        List<String> firstRowList = firstRow.collect { it.toString() }

        List<String> exportFieldList = []
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                exportFieldList = CustomerAccountExporter.buildHeader()
                break
            case PaymentImportGroup.simpleName:
                exportFieldList = PaymentExporter.buildHeader()
                break
            case SubscriptionImportGroup.simpleName:
                exportFieldList = ExportDataFieldUtils.getSubscriptionFields()
                    .values()
                    .collect { Map field -> field.label.toString() }
                break
        }

        if (firstRowList == exportFieldList) {
            AsaasLogger.info("ImportDataFileExtractionService.getTemplateNameBasedOnFirstRow >> Utilizando template [$ASAAS_EXPORT_TEMPLATE_NAME] para importação de [$domainName].")
            return ASAAS_EXPORT_TEMPLATE_NAME
        }

        return ASAAS_IMPORT_TEMPLATE_NAME
    }
}
