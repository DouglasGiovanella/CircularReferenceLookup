package com.asaas.service.importdata

import com.asaas.base.importdata.BaseImportGroup
import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportGroup
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportTemplate
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.importdata.PaymentImportGroup
import com.asaas.domain.payment.importdata.PaymentImportTemplate
import com.asaas.domain.subscription.importdata.SubscriptionImportGroup
import com.asaas.domain.subscription.importdata.SubscriptionImportTemplate
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.web.context.request.RequestContextHolder
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class ImportService {

    def customerAccountImportItemService
    def customerMessageService
    def paymentImportItemService
    def subscriptionImportItemService

    public Class getTemplateClass(String domainName) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                return CustomerAccountImportTemplate
            case PaymentImportGroup.simpleName:
                return PaymentImportTemplate
            case SubscriptionImportGroup.simpleName:
                return SubscriptionImportTemplate
            default:
                throw new NotImplementedException()
        }
    }

    public Class getImportGroupInstance(String domainName) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                return CustomerAccountImportGroup
            case PaymentImportGroup.simpleName:
                return PaymentImportGroup
            case SubscriptionImportGroup.simpleName:
                return SubscriptionImportGroup
            default:
                throw new NotImplementedException()
        }
    }

    public void processItemWithNewTransaction(String domainName, Long itemId) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                customerAccountImportItemService.processWithNewTransaction(itemId)
                break
            case PaymentImportGroup.simpleName:
                paymentImportItemService.processWithNewTransaction(itemId)
                break
            case SubscriptionImportGroup.simpleName:
                subscriptionImportItemService.processWithNewTransaction(itemId)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public void saveItemWithExtractionInconsistency(String domainName, Long groupId, Map item) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                customerAccountImportItemService.saveItemWithExtractionInconsistency(item, groupId)
                break
            case PaymentImportGroup.simpleName:
                paymentImportItemService.saveItemWithExtractionInconsistency(item, groupId)
                break
            case SubscriptionImportGroup.simpleName:
                subscriptionImportItemService.saveItemWithExtractionInconsistency(item, groupId)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public BaseImportGroup saveGroup(Class<BaseImportGroup> importGroupClass, Customer customer, AsaasFile file) {
        BaseImportGroup importGroup = importGroupClass.newInstance()
        importGroup.createdBy = UserUtils.getCurrentUser(RequestContextHolder?.getRequestAttributes()?.getSession())
        importGroup.customer = customer
        importGroup.file = file
        importGroup.name = file.originalName
        importGroup.status = ImportStatus.AWAITING_FILE_EXTRACTION
        importGroup.save(failOnError: true)

        return importGroup
    }

    public void saveItem(String domainName, Map item, Long groupId) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                customerAccountImportItemService.save(groupId, item)
                break
            case PaymentImportGroup.simpleName:
                paymentImportItemService.save(groupId, item)
                break
            case SubscriptionImportGroup.simpleName:
                subscriptionImportItemService.save(groupId, item)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public void deleteItemsIfNecessary(String domainName, Long groupId) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                customerAccountImportItemService.deleteNotImportedFromGroupId(groupId)
                break
            case PaymentImportGroup.simpleName:
                paymentImportItemService.deleteNotImportedFromGroupId(groupId)
                break
            case SubscriptionImportGroup.simpleName:
                subscriptionImportItemService.deleteNotImportedFromGroupId(groupId)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public void setAsAwaitingImport(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.AWAITING_IMPORT
        importGroup.save(failOnError: true)
    }

    public void setAsExtractingFile(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.EXTRACTING_FILE
        importGroup.save(failOnError: true)
    }

    public void setAsFailedFileExtraction(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.FAILED_FILE_EXTRACTION
        importGroup.save(failOnError: true)
    }

    public void setAsInterrupted(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.INTERRUPTED
        importGroup.save(failOnError: true)
    }

    public void setAsFailedValidation(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.FAILED_VALIDATION
        importGroup.save(failOnError: true)
    }

    public void setAsFailedImport(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.FAILED_IMPORT
        importGroup.save(failOnError: true)
    }

    public void setAsImported(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.IMPORTED
        importGroup.save(failOnError: true)
    }

    public void setAsPendingValidation(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.PENDING_VALIDATION
        importGroup.save(failOnError: true)
    }

    public void setAsProcessing(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.PROCESSING
        importGroup.save(failOnError: true)
    }

    public void setAsValidating(BaseImportGroup importGroup) {
        importGroup.status = ImportStatus.VALIDATING
        importGroup.save(failOnError: true)
    }

    public void saveImportItemsWithWarning(BaseImportGroup importGroup, Boolean importItemsWithWarning) {
        importGroup.importItemsWithWarning = importItemsWithWarning
        importGroup.save(failOnError: true)
    }

    public void validateItem(String domainName, Long itemId) {
        switch (domainName) {
            case CustomerAccountImportGroup.simpleName:
                customerAccountImportItemService.validate(itemId)
                break
            case PaymentImportGroup.simpleName:
                paymentImportItemService.validate(itemId)
                break
            case SubscriptionImportGroup.simpleName:
                subscriptionImportItemService.validate(itemId)
                break
            default:
                throw new NotImplementedException()
        }
    }

    public Boolean notifyCustomerAboutImportFinished(BaseImportGroup group) {
        try {
            String entityName = Utils.getMessageProperty(getDomainLabelCode(group))
            String emailSubject = getEmailSubjectText(group, entityName)

            Map emailBodyParams = [group: group, entityName: entityName, importFinishedWithFailures: group.status == ImportStatus.FAILED_IMPORT]

            customerMessageService.notifyAboutImportFinishedByEmail(group.customer, emailSubject, emailBodyParams)
            customerMessageService.sendSmsText(group.customer, group.customer.mobilePhone, getSmsText(group, entityName), false)

            return true
        } catch (Exception exception) {
            AsaasLogger.error("ImportService.notifyCustomerAboutImportFinished >> Falha ao notificar sobre finalização da importação", exception)
            return false
        }
    }

    private String getDomainLabelCode(BaseImportGroup group) {
        return "${getDomainName(group)}.label"
    }

    private String getDomainName(BaseImportGroup group) {
        String className = group.getClass().simpleName.toLowerCase()
        String domainName = ""

        if (className.contains("customer")) {
            domainName = "customerAccountImport"
        } else if (className.contains("payment")) {
            domainName = "paymentImport"
        } else if (className.contains("subscription")) {
            domainName = "subscriptionImport"
        }

        return domainName
    }

    private String getSmsText(BaseImportGroup group, String entityName) {
        String smsText = Utils.getMessageProperty("baseImport.notifyAboutImportStatus.smsText.validated", [entityName, group.name])

        if (group.status == ImportStatus.IMPORTED) {
            smsText = Utils.getMessageProperty("baseImport.notifyAboutImportStatus.smsText.imported", [entityName, group.name])
        }

        if (group.status == ImportStatus.FAILED_IMPORT) {
            smsText = Utils.getMessageProperty("baseImport.notifyAboutImportStatus.smsText.failedImport", [entityName, group.name])
        }

        return smsText
    }

    private String getEmailSubjectText(BaseImportGroup group, String entityName) {
        String emailSubject = Utils.getMessageProperty("baseImport.notifyAboutImportStatus.emailText.validated", [entityName, group.name])

        if (group.status == ImportStatus.IMPORTED) {
            emailSubject = Utils.getMessageProperty("baseImport.notifyAboutImportStatus.emailText.imported", [entityName, group.name])
        }

        if (group.status == ImportStatus.FAILED_IMPORT) {
            emailSubject = Utils.getMessageProperty("baseImport.notifyAboutImportStatus.emailText.failedImport", [entityName, group.name])
        }

        return emailSubject
    }
}
