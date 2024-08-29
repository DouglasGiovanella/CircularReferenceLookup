package com.asaas.service.customeraccount.importdata

import com.asaas.base.importdata.ImportInconsistencyType
import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.city.City
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportGroup
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportInconsistency
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportItem
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.domain.postalcode.PostalCode
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.importdata.ImportDataParser
import com.asaas.log.AsaasLogger
import com.asaas.state.State
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerAccountImportItemService {

    def customerAccountService

    def externalIdentifierService

    def importItemValidationService

    public void processWithNewTransaction(Long id) {
        Boolean succeeded = true
        Utils.withNewTransactionAndRollbackOnError({
            CustomerAccountImportItem item = CustomerAccountImportItem.get(id)
            if (item.group.isInterrupted()) return

            if (shouldIgnoreItem(item)) {
                succeeded = false
                return
            }

            Map customerAccountDataMap = buildCustomerAccountDataMap(item)
            CustomerAccount customerAccount = customerAccountService.save(item.group.customer, null, false, customerAccountDataMap)
            if (customerAccount.hasErrors()) {
                importItemValidationService.saveProcessingInconsistencyWithNewTransaction(item, "customerAccount", customerAccount.errors.allErrors)
                succeeded = false
                return
            }

            customerAccountService.createDefaultNotifications(customerAccount)

            if (customerAccountDataMap.identifier) {
                externalIdentifierService.save(customerAccount, ExternalApplication.OTHER, ExternalResource.IMPORT, customerAccountDataMap.identifier, item.group.customer)
            }

            item.customerAccount = customerAccount
            item.status = ImportStatus.IMPORTED
            item.save(failOnError: true)
        }, [onError: { succeeded = false }, logErrorMessage: "CustomerAccountImportItemService.process >> Falha ao processar o item [${id}]"])

        if (!succeeded) {
            Utils.withNewTransactionAndRollbackOnError {
                CustomerAccountImportItem item = CustomerAccountImportItem.get(id)
                item.status = ImportStatus.FAILED_IMPORT
                item.save(failOnError: true)
            }
        }
    }

    public CustomerAccountImportItem save(Long groupId, Map params) {
        CustomerAccountImportItem customerAccountImportItem = new CustomerAccountImportItem()
        customerAccountImportItem.group = CustomerAccountImportGroup.get(groupId)
        customerAccountImportItem.createdBy = customerAccountImportItem.group.createdBy
        customerAccountImportItem.status = params.status ?: ImportStatus.PENDING_VALIDATION

        customerAccountImportItem.addressNumber = ImportDataParser.parseString(params.addressNumber, CustomerAccountImportItem.constraints.addressNumber.maxSize)
        customerAccountImportItem.company = ImportDataParser.parseString(params.company, CustomerAccountImportItem.constraints.company.maxSize)
        customerAccountImportItem.complement = ImportDataParser.parseString(params.complement, CustomerAccountImportItem.constraints.complement.maxSize)
        customerAccountImportItem.cpfCnpj = ImportDataParser.parseNumeric(params.cpfCnpj)
        customerAccountImportItem.email = ImportDataParser.parseString(params.email, CustomerAccountImportItem.constraints.email.maxSize)
        customerAccountImportItem.groupName = ImportDataParser.parseString(params.groupName, CustomerAccountImportItem.constraints.groupName.maxSize)
        customerAccountImportItem.identifier = ImportDataParser.parseString(params.identifier, CustomerAccountImportItem.constraints.identifier.maxSize)
        customerAccountImportItem.mobilePhone = ImportDataParser.parsePhone(params.mobilePhone)
        customerAccountImportItem.name = ImportDataParser.parseString(params.name, CustomerAccountImportItem.constraints.name.maxSize)
        customerAccountImportItem.notificationDisabled = ImportDataParser.parseString(params.notificationDisabled, CustomerAccountImportItem.constraints.notificationDisabled.maxSize)
        customerAccountImportItem.phone = ImportDataParser.parsePhone(params.phone)

        if (params.additionalEmails) customerAccountImportItem.additionalEmails = CustomerAccount.trimAdditionalEmails(ImportDataParser.parseString(params.additionalEmails, CustomerAccountImportItem.constraints.additionalEmails.maxSize))

        PostalCode postalCode
        if (params.postalCode) {
            customerAccountImportItem.postalCode = ImportDataParser.parseNumeric(params.postalCode)
            postalCode = PostalCode.query([postalCode: customerAccountImportItem.postalCode]).get()
        }

        final String genericPostalCodeSuffix = "000"
        Boolean shouldUsePostalCodeData = !customerAccountImportItem.postalCode?.endsWith(genericPostalCodeSuffix) && postalCode

        customerAccountImportItem.address = shouldUsePostalCodeData ? postalCode.address : ImportDataParser.parseString(params.address, CustomerAccountImportItem.constraints.address.maxSize)
        customerAccountImportItem.province = shouldUsePostalCodeData && postalCode?.province ? postalCode.province : ImportDataParser.parseString(params.province, CustomerAccountImportItem.constraints.province.maxSize)
        customerAccountImportItem.cityString = shouldUsePostalCodeData ? postalCode.city.name: ImportDataParser.parseString(params.cityString, CustomerAccountImportItem.constraints.cityString.maxSize)
        customerAccountImportItem.state = shouldUsePostalCodeData ? postalCode.city.state : ImportDataParser.parseString(params.state, CustomerAccountImportItem.constraints.state.maxSize)

        customerAccountImportItem.save(failOnError: true)
        return customerAccountImportItem
    }

    public void saveItemWithExtractionInconsistency(Map invalidItem, Long groupId) {
        CustomerAccountImportItem customerAccountImportItem = save(groupId, invalidItem.item)

        for (Map invalidInfo in invalidItem.invalidInfoList) {
            importItemValidationService.saveInconsistency(customerAccountImportItem, invalidInfo.field, invalidInfo.inconsistencyType, invalidInfo.inconsistencyType.getIsError(), null)
        }
    }

    public void validate(Long itemId) {
        CustomerAccountImportItem item = CustomerAccountImportItem.get(itemId)

        if (item.group.isFailedValidation()) throw new RuntimeException("O grupo falhou na validação de itens")

        try {
            importItemValidationService.validateBlankValue(item, "name")
            importItemValidationService.validateBlankValue(item, "cpfCnpj")
            importItemValidationService.validateBlankValue(item, "identifier")
            validateDuplicateIdentifier(item)
            validateEmail(item)
            validateCpfCnpj(item)
            validatePhone(item)
            validateMobilePhone(item)
            validateAdditionalEmails(item)
            validatePostalCodeAndCleanAddressIfNecessary(item)
            validateState(item)
            validateNotificationDisabled(item)

            if (!PostalCode.validate(item.postalCode)) {
                setCityIfCityStringIsPresent(item)
            }

            item.status = ImportStatus.AWAITING_IMPORT
            item.save(failOnError: true)
        } catch (Exception exception) {
            item.status = ImportStatus.FAILED_VALIDATION
            item.save(failOnError: true)

            AsaasLogger.error("CustomerAccountImportItemService.validate >> Falha ao validar o item [${item.id}]", exception)
        }
    }

    public void deleteNotImportedFromGroupId(Long groupId) {
        List<Long> customerAccountImportItemIdList = CustomerAccountImportItem.query([column: "id", groupId: groupId, "status[ne]": ImportStatus.IMPORTED]).list()
        Utils.forEachWithFlushSession(customerAccountImportItemIdList, 50, { Long id ->
            CustomerAccountImportItem customerAccountImportItem = CustomerAccountImportItem.get(id)
            customerAccountImportItem.deleted = true
            customerAccountImportItem.save(failOnError: true)
        })
    }

    private Map buildCustomerAccountDataMap(CustomerAccountImportItem item) {
        Map customerAccountDataMap = item.toMap()
        customerAccountDataMap.notificationDisabled = ImportDataParser.parseBoolean(item.notificationDisabled)
        customerAccountDataMap.import = true

        List<String> fieldToIgnoreList = CustomerAccountImportInconsistency.query([distinct: "fieldName", isRequiredField: false, itemId: item.id, disableSort: true]).list()
        for (String field in fieldToIgnoreList) {
            customerAccountDataMap."${field}" = null
        }

        return customerAccountDataMap
    }

    private void validateDuplicateIdentifier(CustomerAccountImportItem item) {
        if (CustomerAccountImportItem.query([column: "identifier", identifier: item.identifier, group: item.group, notId: item.id]).get()) {
            importItemValidationService.saveInconsistency(item, "identifier", ImportInconsistencyType.ERROR_IDENTIFIER_DUPLICATED_SPREADSHEET, true, null)
        }

        if (ExternalIdentifier.query([column: "id", customer: item.group.customer, object: CustomerAccount.simpleName, application: ExternalApplication.OTHER, resource: ExternalResource.IMPORT, externalId: item.identifier]).get()) {
            importItemValidationService.saveInconsistency(item, "identifier", ImportInconsistencyType.ERROR_IDENTIFIER_DUPLICATED, true, null)
        }
    }

    private void validateEmail(CustomerAccountImportItem item) {
        if (item.email && !Utils.emailIsValid(item.email)) {
            importItemValidationService.saveInconsistency(item, "email", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
        }
    }

    private void validateCpfCnpj(CustomerAccountImportItem item) {
        if (!CpfCnpjUtils.validate(item.cpfCnpj)) importItemValidationService.saveInconsistency(item, "cpfCnpj", ImportInconsistencyType.ERROR_INVALID_FORMAT, true, null)
    }

    private void validatePhone(CustomerAccountImportItem item) {
        if (item.phone && !PhoneNumberUtils.validateMinSize(item.phone)) {
            importItemValidationService.saveInconsistency(item, "phone", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
        }
    }

    private void validateMobilePhone(CustomerAccountImportItem item) {
        if (!item.mobilePhone) return

        if (!PhoneNumberUtils.validateMinSize(item.mobilePhone)) {
            importItemValidationService.saveInconsistency(item, "mobilePhone", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
        } else if (!PhoneNumberUtils.validateMobilePhone(item.mobilePhone)) {
            importItemValidationService.saveInconsistency(item, "mobilePhone", ImportInconsistencyType.WARNING_NOT_MOBILEPHONE, false, null)
        }
    }

    private void validateAdditionalEmails(CustomerAccountImportItem item) {
        if (item.additionalEmails) {
            List<String> emailsList = item.additionalEmails.toString().tokenize(CustomerAccount.ADDITIONAL_EMAILS_SEPARATOR)

            if (emailsList.any { String email -> !Utils.emailIsValid(email) }) {
                importItemValidationService.saveInconsistency(item, "additionalEmails", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
            }

            if (emailsList.any { String email -> email == item.email.toString() }) {
                importItemValidationService.saveInconsistency(item, "additionalEmails", ImportInconsistencyType.WARNING_ADDITIONAL_EMAIL_EQUAL_MAIN_EMAIL, false, null)
            }
        }
    }

    private void validatePostalCodeAndCleanAddressIfNecessary(CustomerAccountImportItem item) {
        if (item.postalCode && !PostalCode.validate(item.postalCode)) {
            importItemValidationService.saveInconsistency(item, "postalCode", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
            item.cityString = null
            item.state = null
        }
    }

    private void validateState(CustomerAccountImportItem item) {
        if (!item.postalCode && item.state && !State.validate(item.state)) {
            importItemValidationService.saveInconsistency(item, "state", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
        }
    }

    private void setCityIfCityStringIsPresent(CustomerAccountImportItem item) {
        if (item.cityString) {
            item.city = City.query([name: item.cityString, district: item.cityString]).get()
        }
    }

    private void validateNotificationDisabled(CustomerAccountImportItem item) {
        if (item.notificationDisabled && !isValidBooleanValue(item.notificationDisabled)) {
            importItemValidationService.saveInconsistency(item, "notificationDisabled", ImportInconsistencyType.WARNING_INVALID_FORMAT, false, null)
        }
    }

    private Boolean shouldIgnoreItem(CustomerAccountImportItem item) {
        if (item.inconsistencies.any { it.inconsistencyType.isError }) return true

        if (item.inconsistencies && !item.group.importItemsWithWarning) return true

        return false
    }

    private static Boolean isValidBooleanValue(booleanValue) {
        if (booleanValue instanceof String) {
            booleanValue = Utils.sanitizeString(booleanValue.toLowerCase())
            if (["sim", "nao"].contains(booleanValue)) return true
        }

        return Utils.isValidBooleanValue(booleanValue)
    }
}
