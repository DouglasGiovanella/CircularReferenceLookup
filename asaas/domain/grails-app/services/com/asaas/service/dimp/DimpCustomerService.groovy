package com.asaas.service.dimp

import com.asaas.customer.CustomerStatus
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.dimp.DimpStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.dimp.DimpCustomer
import com.asaas.domain.postalcode.PostalCode
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class DimpCustomerService {
    def dimpFileService

    public Boolean createDimpCustomers(Date startDate) {
        Map queryParams = [:]
        queryParams.column = "id"
        queryParams.includeDeleted = true
        queryParams."cpfCnpj[isNotNull]" = true
        queryParams."validDimpCustomer[notExists]" = true
        queryParams.customerRegisterStatus = ["generalApproval[ne]": GeneralApprovalStatus.REJECTED]
        queryParams.status = CustomerStatus.ACTIVE
        queryParams."lastUpdated[ge]" = startDate
        queryParams.disableSort = true

        final Integer customersLimit = 1000
        List<Long> customerIdList = Customer.query(queryParams).list(max: customersLimit)
        if (!customerIdList) return false

        createOrUpdateDimpCustomers(customerIdList)

        Utils.withNewTransactionAndRollbackOnError({
            dimpFileService.createDimpBatchFileForPendingDimpCustomers()
        }, [logErrorMessage: "DimpCustomerService.createDimpCustomers() >> Erro ao salvar o dimpBatchFile para os DimpCustomers pendentes"])

        return true
    }

    public void createOrUpdateDimpCustomers(List<Long> customerIdList) {
        final Integer batchSize = 50
        final Integer flushEvery = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            Customer customer = Customer.read(customerId)
            save(customer)
        }, [logErrorMessage: "DimpCustomerService.createOrUpdateDimpCustomers() >> Erro ao salvar lote de DimpCustomer: ${customerIdList}",
            appendBatchToLogErrorMessage: true])
    }

    public Boolean shouldBeIgnored(DimpCustomer dimpCustomer) {
        if (!dimpCustomer.cpfCnpj || !CpfCnpjUtils.validate(dimpCustomer.cpfCnpj)) return true
        if (!dimpCustomer.name || !isNameValid(dimpCustomer.name)) return true
        if (!dimpCustomer.phone || !PhoneNumberUtils.validatePhone(dimpCustomer.phone)) return true
        if (!dimpCustomer.email || !Utils.emailIsValid(dimpCustomer.email)) return true
        if (!dimpCustomer.responsibleName) return true
        if (!dimpCustomer.address) return true
        if (!dimpCustomer.city) return true
        if (!dimpCustomer.state) return true
        if (!dimpCustomer.postalCode || !PostalCode.validate(dimpCustomer.postalCode)) return true
        if (!dimpCustomer.province || dimpCustomer.province.length() < 3) return true
        if (!dimpCustomer.ibgeCode) return true

        return false
    }

    public Boolean hasValidDimpCustomer(String cpfCnpj) {
        return DimpCustomer.query("status[in]": [DimpStatus.PENDING, DimpStatus.DONE], cpfCnpj: cpfCnpj, column: "id").get().asBoolean()
    }

    private DimpCustomer save(Customer customer) {
        PostalCode customerPostalCode = customer.postalCode ? PostalCode.find(customer.postalCode) : null

        DimpCustomer dimpCustomer = DimpCustomer.query(cpfCnpj: customer.cpfCnpj).get() ?: new DimpCustomer()
        if (dimpCustomer.id && customer.dateCreated > dimpCustomer.relationshipStartDate) {
            dimpCustomer.lastUpdatedDateFromCustomerWithSameCpfCnpj = new Date()
            dimpCustomer.save(failOnError: true)
            return dimpCustomer
        }

        dimpCustomer.cpfCnpj = customer.cpfCnpj

        if (customer.isLegalPerson()) {
            dimpCustomer.name = sanitizeString(customer.getRevenueServiceRegister()?.corporateName)
        } else {
            dimpCustomer.name = sanitizeString(customer.name)
        }

        dimpCustomer.phone = PhoneNumberUtils.removeBrazilAreaCode(PhoneNumberUtils.sanitizeNumber(customer.mobilePhone ?: customer.phone))
        dimpCustomer.email = customer.email
        dimpCustomer.relationshipStartDate = customer.dateCreated
        dimpCustomer.stateInscription = customer.inscricaoEstadual
        dimpCustomer.responsibleName = sanitizeString(customer.responsibleName ?: customer.getProviderName())
        dimpCustomer.address = sanitizeString(buildAddressString(customer.address, customer.addressNumber))
        dimpCustomer.city = customerPostalCode?.city?.name
        dimpCustomer.state = customerPostalCode?.city?.state
        dimpCustomer.ibgeCode = customerPostalCode?.city?.ibgeCode
        dimpCustomer.postalCode = customerPostalCode?.postalCode
        dimpCustomer.province = customerPostalCode?.province
        dimpCustomer.addressComplement = sanitizeString(customer.complement)

        DimpStatus dimpStatus = validateDimpStatus(dimpCustomer)

        if (dimpCustomer.id && !dimpCustomer.status.isIgnored() && dimpStatus.isIgnored()) {
            dimpCustomer.refresh()
            dimpCustomer.lastUpdatedDateFromCustomerWithSameCpfCnpj = new Date()
            dimpCustomer.save(failOnError: true)

            return dimpCustomer
        }

        dimpCustomer.lastUpdatedDateFromCustomerWithSameCpfCnpj = new Date()
        dimpCustomer.status = dimpStatus

        dimpCustomer.save(failOnError: true)

        return dimpCustomer
    }

    private DimpStatus validateDimpStatus(DimpCustomer dimpCustomer) {
        if (shouldBeIgnored(dimpCustomer)) return DimpStatus.IGNORED

        return DimpStatus.PENDING
    }

    private String buildAddressString(String address, String addressNumber) {
        if (!address) return null
        if (addressNumber) return "${address}, ${addressNumber}"

        return address
    }

    private Boolean isNameValid(String name) {
        if (Utils.emailIsValid(name)) return false
        if (!name.matches(".*[a-zA-Z]+.*")) return false

        return true
    }

    private sanitizeString(String field) {
        if (field) return field.replaceAll("[\\|\n\r]", "")
        return null
    }
}
