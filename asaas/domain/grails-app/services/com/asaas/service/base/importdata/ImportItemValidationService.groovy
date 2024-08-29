package com.asaas.service.base.importdata

import com.asaas.base.importdata.ImportInconsistencyType
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportInconsistency
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportItem
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.domain.payment.importdata.PaymentImportInconsistency
import com.asaas.domain.payment.importdata.PaymentImportItem
import com.asaas.domain.subscription.importdata.SubscriptionImportInconsistency
import com.asaas.domain.subscription.importdata.SubscriptionImportItem
import com.asaas.interestconfig.InterestPeriod
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException
import org.springframework.validation.ObjectError

@Transactional
class ImportItemValidationService {

    def paymentFineConfigService
    def paymentDiscountConfigService
    def paymentInterestConfigService

    public void validateBlankValue(importItem, String fieldName) {
        if (!fieldName) throw new RuntimeException("ImportItemValidationService.validateBlankValue(): o parâmetro [fieldName] é obrigatório para validação.")

        if (!importItem."${fieldName}") {
            saveInconsistency(importItem, fieldName, ImportInconsistencyType.ERROR_BLANK, true, null)
        }
    }

    public void validateCustomerAccount(importItem) {
        CustomerAccount customerAccount

        if (importItem.identifier) {
            Long customerAccountId = ExternalIdentifier.customerAccountImport(importItem.identifier, importItem.group.customer, [column: "objectId"]).get()

            if (!customerAccountId) {
                saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_CUSTOMER_ACCOUNT_NOT_REGISTERED, true, null)
                return
            }

            customerAccount = CustomerAccount.get(customerAccountId)
            if (!customerAccount.cpfCnpj) {
                saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_CUSTOMER_ACCOUNT_WITHOUT_CPF_CNPJ, true, null)
                return
            }
        } else if (importItem.cpfCnpj) {
            List<Long> customerAccountIdList = CustomerAccount.query([column: "id", cpfCnpj: importItem.cpfCnpj, customer: importItem.group.customer, deleted: true, disableSort: true]).list()

            if (!customerAccountIdList) {
                saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_CUSTOMER_ACCOUNT_NOT_REGISTERED, true, null)
                return
            }

            if (customerAccountIdList.size() > 1) {
                saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_CUSTOMER_ACCOUNT_DUPLICATED_CPF_CNPJ, true, null)
                return
            }

            customerAccount = CustomerAccount.get(customerAccountIdList.first())
        } else {
            saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_BLANK, true, null)
            return
        }

        if (customerAccount.deleted) {
            saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_CUSTOMER_ACCOUNT_DELETED, true, null)
            return
        }

        if (!CpfCnpjUtils.validate(customerAccount.cpfCnpj)) {
            saveInconsistency(importItem, "customerAccount", ImportInconsistencyType.ERROR_CUSTOMER_ACCOUNT_INVALID_CPF_CNPJ, true, null)
        }
    }

    public void validateDiscountConfig(importItem) {
        if (!importItem.discountType && !importItem.discountValue) return

        if (!importItem.discountType || !importItem.discountValue) {
            saveInconsistency(importItem, "discountConfig", ImportInconsistencyType.ERROR_INVALID_DISCOUNT_CONFIG, true, null)
            return
        }

        BusinessValidation businessValidation = paymentDiscountConfigService.validateObjectValue(importItem)
        if (!businessValidation.isValid()) {
            saveInconsistency(importItem, "discountConfig", ImportInconsistencyType.ERROR_INVALID_FORMAT, true, businessValidation.getFirstErrorMessage())
        }
    }

    public void validateDueDate(importItem) {
        if (importItem.dueDate < new Date().clearTime()) {
            saveInconsistency(importItem, "dueDate", ImportInconsistencyType.ERROR_INVALID_DATE, true, null)
        }
    }

    public void validateFineConfig(importItem) {
        if (!importItem.fineValue && !importItem.fineType) return

        if (!importItem.fineType || !importItem.fineValue) {
            saveInconsistency(importItem, "fineConfig", ImportInconsistencyType.ERROR_INVALID_FINE_CONFIG, true, null)
            return
        }

        BusinessValidation businessValidation = paymentFineConfigService.validateObjectValue(importItem)
        if (!businessValidation.isValid()) {
            saveInconsistency(importItem, "fineConfig", ImportInconsistencyType.ERROR_INVALID_FORMAT, true, businessValidation.getFirstErrorMessage())
        }
    }

    public void validateInterestConfig(importItem) {
        if (!importItem.interestValue) return

        BusinessValidation businessValidation =  paymentInterestConfigService.validateCustomerConfig([interestPeriod: InterestPeriod.MONTHLY, value: importItem.interestValue])
        if (!businessValidation.isValid()) {
            saveInconsistency(importItem, "interestConfig", ImportInconsistencyType.ERROR_INVALID_FORMAT, true, businessValidation.getFirstErrorMessage())
        }
    }

    public void validateValue(importItem) {
        if (!importItem.value?.toDouble()) {
            saveInconsistency(importItem, "value", ImportInconsistencyType.WARNING_INVALID_FORMAT, true, null)
        }
    }

    public void saveInconsistency(importItem, String attribute, ImportInconsistencyType inconsistencyType, Boolean isRequiredField, String domainError) {
        Class importInconsistencyClass = getImportInconsistencyClass(importItem)
        def importInconsistency = importInconsistencyClass.newInstance()

        importInconsistency.item = importItem
        importInconsistency.fieldName = attribute
        importInconsistency.inconsistencyType = inconsistencyType
        importInconsistency.isRequiredField = isRequiredField
        importInconsistency.domainError = domainError
        importInconsistency.save(failOnError: true)
    }

    public void saveProcessingInconsistencyWithNewTransaction(importItem, String attribute, List<ObjectError> errorList) {
        for (ObjectError error in errorList) {
            Utils.withNewTransactionAndRollbackOnError {
                saveInconsistency(importItem, attribute, ImportInconsistencyType.ERROR_SAVING_ITEM, true, Utils.getMessageProperty(error))
            }
        }
    }

    private Class getImportInconsistencyClass(importItem) {
        if (importItem instanceof PaymentImportItem) return PaymentImportInconsistency
        if (importItem instanceof SubscriptionImportItem) return SubscriptionImportInconsistency
        if (importItem instanceof CustomerAccountImportItem) return CustomerAccountImportInconsistency

        throw new NotImplementedException()
    }
}
