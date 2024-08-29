package com.asaas.service.payment.importdata

import com.asaas.base.importdata.ImportInconsistencyType
import com.asaas.base.importdata.ImportStatus
import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.importdata.PaymentImportGroup
import com.asaas.domain.payment.importdata.PaymentImportInconsistency
import com.asaas.domain.payment.importdata.PaymentImportItem
import com.asaas.exception.BusinessException
import com.asaas.importdata.ImportDataParser
import com.asaas.interestconfig.InterestPeriod
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentImportItemService {

    def importItemValidationService
    def installmentService
    def paymentDiscountConfigService
    def paymentService

    public void processWithNewTransaction(Long id) {
        Boolean succeeded = true
        Utils.withNewTransactionAndRollbackOnError({
            PaymentImportItem item = PaymentImportItem.get(id)
            if (item.group.isInterrupted()) return

            if (shouldIgnoreItem(item)) {
                succeeded = false
                return
            }

            Map paymentData = buildPaymentData(item)
            String attributeInconsistency = "payment"

            if (paymentData.installmentCount > 1) {
                Installment installment = installmentService.save(paymentData, true, false)
                if (installment.hasErrors()) {
                    importItemValidationService.saveProcessingInconsistencyWithNewTransaction(item, attributeInconsistency, installment.errors.allErrors)
                    succeeded = false
                    return
                }

                Payment paymentWithError = installment.payments.find { it.hasErrors() }
                if (paymentWithError) {
                    importItemValidationService.saveProcessingInconsistencyWithNewTransaction(item, attributeInconsistency, paymentWithError.errors.allErrors)
                    succeeded = false
                    return
                }

                item.installment = installment
            } else {
                Payment payment = paymentService.save(paymentData, false, true)
                if (payment.hasErrors()) {
                    importItemValidationService.saveProcessingInconsistencyWithNewTransaction(item, attributeInconsistency, payment.errors.allErrors)
                    succeeded = false
                    return
                }

                item.payment = payment
            }

            item.status = ImportStatus.IMPORTED
            item.save(failOnError: true)
        }, [onError: { succeeded = false }, logErrorMessage: "PaymentImportItemService.process >> Falha ao processar o item [${id}]"])

        if (!succeeded) {
            Utils.withNewTransactionAndRollbackOnError {
                PaymentImportItem item = PaymentImportItem.get(id)
                item.status = ImportStatus.FAILED_IMPORT
                item.save(failOnError: true, flush: true)
            }
        }
    }

    public PaymentImportItem save(Long groupId, Map params) {
        PaymentImportItem paymentImportItem = new PaymentImportItem()
        paymentImportItem.group = PaymentImportGroup.get(groupId)
        paymentImportItem.status = params.status ?: ImportStatus.PENDING_VALIDATION

        paymentImportItem.billingType = ImportDataParser.parseString(params.billingType, PaymentImportItem.constraints.billingType.maxSize)
        paymentImportItem.description = ImportDataParser.parseString(params.description, PaymentImportItem.constraints.description.maxSize)
        paymentImportItem.discountType = ImportDataParser.parseString(params.discountType, PaymentImportItem.constraints.discountType.maxSize)
        paymentImportItem.discountValue = Utils.toBigDecimal(params.discountValue)
        paymentImportItem.dueDate = ImportDataParser.parseDate(params.dueDate)
        paymentImportItem.fineType = ImportDataParser.parseString(params.fineType, PaymentImportItem.constraints.fineType.maxSize)
        paymentImportItem.fineValue = Utils.toBigDecimal(params.fineValue)

        paymentImportItem.identifier = ImportDataParser.parseString(params.identifier, PaymentImportItem.constraints.identifier.maxSize)
        paymentImportItem.cpfCnpj = ImportDataParser.parseString(params.cpfCnpj, PaymentImportItem.constraints.cpfCnpj.maxSize)

        paymentImportItem.installmentCount = Utils.toInteger(params.installmentCount)
        paymentImportItem.interestValue = Utils.toBigDecimal(params.interestValue)
        paymentImportItem.value = Utils.toBigDecimal(params.value)

        return paymentImportItem.save(failOnError: true)
    }

    public void saveItemWithExtractionInconsistency(Map invalidItem, Long groupId) {
        PaymentImportItem paymentImportItem = save(groupId, invalidItem.item)

        for (Map invalidInfo in invalidItem.invalidInfoList) {
            importItemValidationService.saveInconsistency(paymentImportItem, invalidInfo.field, invalidInfo.inconsistencyType, invalidInfo.inconsistencyType.getIsError(), null)
        }
    }

    public void validate(Long itemId) {
        PaymentImportItem item = PaymentImportItem.get(itemId)

        if (item.group.isFailedValidation()) throw new RuntimeException("O grupo falhou na validação de itens")

        try {
            importItemValidationService.validateBlankValue(item, "billingType")
            importItemValidationService.validateBlankValue(item, "value")
            importItemValidationService.validateBlankValue(item, "dueDate")
            importItemValidationService.validateDueDate(item)
            importItemValidationService.validateValue(item)
            importItemValidationService.validateCustomerAccount(item)
            validateInstallmentCount(item)
            if (item.value) {
                importItemValidationService.validateFineConfig(item)
                importItemValidationService.validateDiscountConfig(item)
            }
            importItemValidationService.validateInterestConfig(item)
            validateDailyPaymentsLimit(item)

            item.status = ImportStatus.AWAITING_IMPORT
            item.save(failOnError: true)
        } catch (Exception exception) {
            item.status = ImportStatus.FAILED_VALIDATION
            item.save(failOnError: true)

            AsaasLogger.error("PaymentImportItemService.validate >> Falha ao validar o item [${item.id}]", exception)
            throw exception
        }
    }

    public void validateInstallmentCount(PaymentImportItem item) {
        if (!item.installmentCount || !item.billingType) return

        BillingType billingType = ImportDataParser.parseBillingType(item.billingType)
        if (!billingType) throw new BusinessException("Forma de pagamento inválida")

        if ([BillingType.MUNDIPAGG_CIELO, BillingType.UNDEFINED].contains(billingType) && item.installmentCount > Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD) {
            importItemValidationService.saveInconsistency(item, "installmentCount", ImportInconsistencyType.ERROR_INSTALLMENT_COUNT_ABOVE_CREDIT_CARD_LIMIT, true, null)
            return
        }

        Integer maxInstallmentCount = Installment.getMaxInstallmentCount(item.group.customer)

        if (item.installmentCount > maxInstallmentCount) {
            ImportInconsistencyType importInconsistencyType

            if (maxInstallmentCount == Installment.MAX_INSTALLMENT_COUNT_FOR_APPROVED_PROVIDER) {
                importInconsistencyType = ImportInconsistencyType.ERROR_INSTALLMENT_COUNT_ABOVE_APPROVED_PROVIDER_LIMIT
            } else {
                importInconsistencyType = ImportInconsistencyType.ERROR_INSTALLMENT_COUNT_ABOVE_NOT_APPROVED_PROVIDER_LIMIT
            }

            importItemValidationService.saveInconsistency(item, "installmentCount", importInconsistencyType, true, null)
        }
    }

    public void validateDailyPaymentsLimit(PaymentImportItem item) {
        List<Integer> paymentCountList = PaymentImportItem.query([column: "installmentCount", group: item.group, status: ImportStatus.AWAITING_IMPORT]).list()

        if (!paymentCountList) return

        Integer paymentCount = paymentCountList.collect { Integer value ->
            if (value == null) return 1
            return value
        }.sum()

        if (!item.group.customer.canCreateMorePaymentsToday(paymentCount)) {
            importItemValidationService.saveInconsistency(item, "installmentCount", ImportInconsistencyType.ERROR_INSTALLMENT_COUNT_ABOVE_DAILY_LIMIT_AVAILABLE, true, null)
        }
    }

    public void deleteNotImportedFromGroupId(Long groupId) {
        List<Long> paymentImportItemIdList = PaymentImportItem.query([column: "id", groupId: groupId, "status[ne]": ImportStatus.IMPORTED]).list()
        Utils.forEachWithFlushSession(paymentImportItemIdList, 50, { Long id ->
            PaymentImportItem paymentImportItem = PaymentImportItem.get(id)
            paymentImportItem.deleted = true
            paymentImportItem.save(failOnError: true)
        })
    }

    private Map buildPaymentData(PaymentImportItem item) {
        Map paymentData = item.toMap()

        paymentData.billingType = ImportDataParser.parseBillingType(paymentData.billingType)
        paymentData.cycle = ImportDataParser.parseCycle(paymentData.billingType)

        if (item.discountValue && item.discountType && item.discountDueDateLimitDays >= 0) {
            paymentData.discount = [:]
            paymentData.discount.value = item.discountValue
            paymentData.discount.discountType = ImportDataParser.parseDiscountType(item.discountType)
            paymentData.discount.dueDateLimitDays = item.discountDueDateLimitDays
        } else {
            paymentData.discount = paymentDiscountConfigService.buildCustomerConfig(Utils.toBigDecimal(paymentData.value), paymentData.billingType, item.group.customer)
        }

        if (item.interestValue) {
            paymentData.interest = [:]
            paymentData.interest.value = item.interestValue
            paymentData.interest.interestPeriod = InterestPeriod.MONTHLY
        }

        if (item.fineValue && item.fineType) {
            paymentData.fine = [:]
            paymentData.fine.value = item.fineValue
            paymentData.fine.fineType = ImportDataParser.parseFineType(item.fineType)
        }

        Long customerAccountId = ExternalIdentifier.customerAccountImport(paymentData.identifier, item.group.customer, [column: "objectId"]).get()

        if (!customerAccountId) {
            customerAccountId = CustomerAccount.query([column: "id", cpfCnpj: paymentData.cpfCnpj, customer: item.group.customer]).get()
        }

        paymentData.customerAccount = CustomerAccount.get(customerAccountId)

        List<String> fieldToIgnoreList = PaymentImportInconsistency.query([distinct: "fieldName", isRequiredField: false, itemId: item.id, disableSort: true]).list()
        for (String field in fieldToIgnoreList) {
            paymentData."${field}" = null
        }

        return paymentData
    }

    private Boolean shouldIgnoreItem(PaymentImportItem item) {
        if (item.getInconsistencies().any { it.inconsistencyType.isError }) return true

        if (item.getInconsistencies() && !item.group.importItemsWithWarning) return true

        return false
    }
}
