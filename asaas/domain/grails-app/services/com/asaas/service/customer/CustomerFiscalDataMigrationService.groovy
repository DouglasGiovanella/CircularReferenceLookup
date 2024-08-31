package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.customer.CustomerFiscalConfig
import grails.transaction.Transactional

@Transactional
class CustomerFiscalDataMigrationService {

    def customerInteractionService
    def customerInvoiceService
    def customerFiscalInfoService
    def customerFiscalConfigService

    public void executeMigration(Customer customer) {
        Boolean hasCustomerFiscalInfo = CustomerFiscalInfo.query([exists: true, customerId: customer.id]).get().asBoolean()
        if (hasCustomerFiscalInfo) throw new RuntimeException("O cliente já possui dados fiscais cadastrados.")

        CustomerFiscalInfo customerFiscalInfoWithSameCnpj = CustomerFiscalInfo.query(["customerId[ne]": customer.id, cpfCnpj: customer.cpfCnpj]).get()
        if (!customerFiscalInfoWithSameCnpj) throw new RuntimeException("Não foi possível encontrar os dados fiscais do cliente com o mesmo CNPJ.")

        customerFiscalInfoService.delete(customerFiscalInfoWithSameCnpj.id)
        Long toCustomerFiscalInfoId = migrateCustomerFiscalInfo(customerFiscalInfoWithSameCnpj, customer)

        CustomerFiscalConfig customerFiscalConfigWithSameCnpj = CustomerFiscalConfig.query(["customerId": customerFiscalInfoWithSameCnpj.customer.id]).get()
        if (customerFiscalConfigWithSameCnpj) {
            migrateCustomerFiscalConfig(customerFiscalConfigWithSameCnpj, customer)
            customerFiscalConfigService.delete(customerFiscalConfigWithSameCnpj.id)
        }

        customerInvoiceService.cancelScheduledInvoices(customerFiscalInfoWithSameCnpj.customer)

        customerInteractionService.saveCustomerFiscalDataMigrationRequest(customer, customerFiscalInfoWithSameCnpj.id, toCustomerFiscalInfoId)
    }

    private Long migrateCustomerFiscalInfo(CustomerFiscalInfo fromCustomerFiscalInfo, Customer toCustomer) {
        CustomerFiscalInfo toCustomerFiscalInfo = new CustomerFiscalInfo()
        toCustomerFiscalInfo.customer = toCustomer
        toCustomerFiscalInfo.externalId = fromCustomerFiscalInfo.externalId
        toCustomerFiscalInfo.municipalInscription = fromCustomerFiscalInfo.municipalInscription
        toCustomerFiscalInfo.simpleNational = fromCustomerFiscalInfo.simpleNational
        toCustomerFiscalInfo.rpsSerie = fromCustomerFiscalInfo.rpsSerie
        toCustomerFiscalInfo.rpsNumber = fromCustomerFiscalInfo.rpsNumber
        toCustomerFiscalInfo.loteNumber = fromCustomerFiscalInfo.loteNumber
        toCustomerFiscalInfo.invoiceProviderUser = fromCustomerFiscalInfo.invoiceProviderUser
        toCustomerFiscalInfo.synchronizationDisabled = fromCustomerFiscalInfo.synchronizationDisabled
        toCustomerFiscalInfo.certificateName = fromCustomerFiscalInfo.certificateName
        toCustomerFiscalInfo.accessTokenSent = fromCustomerFiscalInfo.accessTokenSent
        toCustomerFiscalInfo.userPasswordSent = fromCustomerFiscalInfo.userPasswordSent
        toCustomerFiscalInfo.specialTaxRegime = fromCustomerFiscalInfo.specialTaxRegime
        toCustomerFiscalInfo.email = fromCustomerFiscalInfo.email
        toCustomerFiscalInfo.serviceListItem = fromCustomerFiscalInfo.serviceListItem
        toCustomerFiscalInfo.nationalEconomicActivityCode = fromCustomerFiscalInfo.nationalEconomicActivityCode
        toCustomerFiscalInfo.culturalProjectsPromoter = fromCustomerFiscalInfo.culturalProjectsPromoter
        toCustomerFiscalInfo.operationNature = fromCustomerFiscalInfo.operationNature
        toCustomerFiscalInfo.save(failOnError: true)

        return toCustomerFiscalInfo.id
    }

    private void migrateCustomerFiscalConfig(CustomerFiscalConfig fromCustomerFiscalConfig, Customer toCustomer) {
        CustomerFiscalConfig toCustomerFiscalConfig = new CustomerFiscalConfig()
        toCustomerFiscalConfig.customer = toCustomer
        toCustomerFiscalConfig.invoiceEstimatedTaxesType = fromCustomerFiscalConfig.invoiceEstimatedTaxesType
        toCustomerFiscalConfig.invoiceEstimatedTaxesPercentage = fromCustomerFiscalConfig.invoiceEstimatedTaxesPercentage
        toCustomerFiscalConfig.invoiceNecessaryExpression = fromCustomerFiscalConfig.invoiceNecessaryExpression
        toCustomerFiscalConfig.invoiceRetainedIrDescription = fromCustomerFiscalConfig.invoiceRetainedIrDescription
        toCustomerFiscalConfig.invoiceRetainedCsrfDescription = fromCustomerFiscalConfig.invoiceRetainedCsrfDescription
        toCustomerFiscalConfig.invoiceRetainedInssDescription = fromCustomerFiscalConfig.invoiceRetainedInssDescription
        toCustomerFiscalConfig.includeInterestValue = fromCustomerFiscalConfig.includeInterestValue
        toCustomerFiscalConfig.save(failOnError: true)
    }
}
