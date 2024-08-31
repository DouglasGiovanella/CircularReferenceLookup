package com.asaas.service.subscription.importdata

import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.domain.subscription.importdata.SubscriptionImportGroup
import com.asaas.domain.subscription.importdata.SubscriptionImportItem
import com.asaas.domain.subscription.importdata.SubscriptionImportInconsistency
import com.asaas.importdata.ImportDataParser
import com.asaas.interestconfig.InterestPeriod
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class SubscriptionImportItemService {

    def importItemValidationService
    def paymentDiscountConfigService
    def subscriptionService

    public void processWithNewTransaction(Long id) {
        Boolean succeeded = true
        Utils.withNewTransactionAndRollbackOnError({
            SubscriptionImportItem item = SubscriptionImportItem.get(id)
            if (item.group.isInterrupted()) return

            if (shouldIgnoreItem(item)) {
                succeeded = false
                return
            }

            Map subscriptionData = buildSubscriptionData(item)
            def subscription = subscriptionService.save(subscriptionData.customerAccount, subscriptionData, false)
            if (subscription.hasErrors()) {
                importItemValidationService.saveProcessingInconsistencyWithNewTransaction(item, "subscription", subscription.errors.allErrors)
                succeeded = false
                return
            }

            item.subscription = subscription
            item.status = ImportStatus.IMPORTED
            item.save(failOnError: true)
        }, [onError: { succeeded = false }, logErrorMessage: "PaymentImportItemService.process >> Falha ao processar o item [${id}]"])

        if (!succeeded) {
            Utils.withNewTransactionAndRollbackOnError {
                SubscriptionImportItem item = SubscriptionImportItem.get(id)
                item.status = ImportStatus.FAILED_IMPORT
                item.save(failOnError: true)
            }
        }
    }

    public SubscriptionImportItem save(Long groupId, Map params) {
        SubscriptionImportItem subscriptionImportItem = new SubscriptionImportItem()
        subscriptionImportItem.group = SubscriptionImportGroup.get(groupId)
        subscriptionImportItem.status = params.status ?: ImportStatus.PENDING_VALIDATION

        subscriptionImportItem.billingType = ImportDataParser.parseString(params.billingType, SubscriptionImportItem.constraints.billingType.maxSize)
        subscriptionImportItem.cycle = ImportDataParser.parseString(params.cycle, SubscriptionImportItem.constraints.cycle.maxSize)
        subscriptionImportItem.description = ImportDataParser.parseString(params.description, SubscriptionImportItem.constraints.description.maxSize)
        subscriptionImportItem.discountType = ImportDataParser.parseString(params.discountType, SubscriptionImportItem.constraints.discountType.maxSize)
        subscriptionImportItem.discountValue = Utils.toBigDecimal(params.discountValue)
        subscriptionImportItem.dueDate = ImportDataParser.parseDate(params.dueDate)
        subscriptionImportItem.fineType = ImportDataParser.parseString(params.fineType, SubscriptionImportItem.constraints.fineType.maxSize)
        subscriptionImportItem.fineValue = Utils.toBigDecimal(params.fineValue)

        subscriptionImportItem.identifier = ImportDataParser.parseString(params.identifier, SubscriptionImportItem.constraints.identifier.maxSize)
        subscriptionImportItem.cpfCnpj = ImportDataParser.parseString(params.cpfCnpj, SubscriptionImportItem.constraints.cpfCnpj.maxSize)

        subscriptionImportItem.interestValue = Utils.toBigDecimal(params.interestValue)
        subscriptionImportItem.value = Utils.toBigDecimal(params.value)

        subscriptionImportItem.save(failOnError: true)

        return subscriptionImportItem
    }

    public void saveItemWithExtractionInconsistency(Map invalidItem, Long groupId) {
        SubscriptionImportItem subscriptionImportItem = save(groupId, invalidItem.item)

        for (Map invalidInfo in invalidItem.invalidInfoList) {
            importItemValidationService.saveInconsistency(subscriptionImportItem, invalidInfo.field, invalidInfo.inconsistencyType, invalidInfo.inconsistencyType.getIsError(), null)
        }
    }

    public void validate(Long itemId) {
        SubscriptionImportItem item = SubscriptionImportItem.get(itemId)

        if (item.group.isFailedValidation()) throw new RuntimeException("O grupo falhou na validação de itens")

        try {
            importItemValidationService.validateBlankValue(item, "billingType")
            importItemValidationService.validateBlankValue(item, "cycle")
            importItemValidationService.validateBlankValue(item, "value")
            importItemValidationService.validateBlankValue(item, "dueDate")
            importItemValidationService.validateDueDate(item)
            importItemValidationService.validateValue(item)
            importItemValidationService.validateCustomerAccount(item)

            if (item.value) {
                importItemValidationService.validateFineConfig(item)
                importItemValidationService.validateDiscountConfig(item)
            }
            importItemValidationService.validateInterestConfig(item)

            item.status = ImportStatus.AWAITING_IMPORT
            item.save(failOnError: true)
        } catch (Exception exception) {
            item.status = ImportStatus.FAILED_VALIDATION
            item.save(failOnError: true)

            AsaasLogger.error("SubscriptionImportItemService.validate >> Falha ao validar o item [${item.id}]", exception)
            throw exception
        }
    }

    public void deleteNotImportedFromGroupId(Long groupId) {
        List<Long> subscriptionImportItemIdList = SubscriptionImportItem.query([column: "id", groupId: groupId, "status[ne]": ImportStatus.IMPORTED]).list()
        Utils.forEachWithFlushSession(subscriptionImportItemIdList, 50, { Long id ->
            SubscriptionImportItem subscriptionImportItem = SubscriptionImportItem.get(id)
            subscriptionImportItem.deleted = true
            subscriptionImportItem.save(failOnError: true)
        })
    }

    private Map buildSubscriptionData(SubscriptionImportItem item) {
        Map subscriptionData = item.toMap()

        subscriptionData.billingType = ImportDataParser.parseBillingType(subscriptionData.billingType)
        subscriptionData.subscriptionCycle = ImportDataParser.parseCycle(subscriptionData.cycle)

        if (item.discountValue && item.discountType && item.discountDueDateLimitDays >= 0) {
            subscriptionData.discount = [:]
            subscriptionData.discount.value = item.discountValue
            subscriptionData.discount.discountType = ImportDataParser.parseDiscountType(item.discountType)
            subscriptionData.discount.dueDateLimitDays = item.discountDueDateLimitDays
        } else {
            subscriptionData.discount = paymentDiscountConfigService.buildCustomerConfig(Utils.toBigDecimal(subscriptionData.value), subscriptionData.billingType, item.group.customer)
        }

        if (item.interestValue) {
            subscriptionData.interest = [:]
            subscriptionData.interest.value = item.interestValue
            subscriptionData.interest.interestPeriod = InterestPeriod.MONTHLY
        }

        if (item.fineValue && item.fineType) {
            subscriptionData.fine = [:]
            subscriptionData.fine.value = item.fineValue
            subscriptionData.fine.fineType = ImportDataParser.parseFineType(item.fineType)
        }

        Long customerAccountId = ExternalIdentifier.customerAccountImport(subscriptionData.identifier, item.group.customer, [column: "objectId"]).get()

        if (!customerAccountId) {
            customerAccountId = CustomerAccount.query([column: "id", cpfCnpj: subscriptionData.cpfCnpj, customer: item.group.customer]).get()
        }

        subscriptionData.customerAccount = CustomerAccount.get(customerAccountId)

        List<String> fieldToIgnoreList = SubscriptionImportInconsistency.query([distinct: "fieldName", isRequiredField: false, itemId: item.id, disableSort: true]).list()
        for (String field in fieldToIgnoreList) {
            subscriptionData."${field}" = null
        }

        return subscriptionData
    }

    private Boolean shouldIgnoreItem(SubscriptionImportItem item) {
        if (item.inconsistencies.any { it.inconsistencyType.isError }) return true

        if (item.inconsistencies && !item.group.importItemsWithWarning) return true

        return false
    }
}
