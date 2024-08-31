package com.asaas.service.customerfiscalinfo

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceAuthorizationRequest
import com.asaas.domain.invoice.InvoiceCityConfig
import com.asaas.exception.BusinessException
import com.asaas.invoice.builder.CustomerInvoiceFiscalInfoBuilder
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class CustomerFiscalInfoService {

    def customerFeatureService
    def customerFiscalConfigService
    def customerFiscalInfoSynchronizeService
    def customerInvoiceConfigService
    def customerInteractionService
    def customerProductService

    public CustomerFiscalInfo save(Customer customer, Map params) {
        CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.query([customerId: customer.id]).get()
        if (!customerFiscalInfo) customerFiscalInfo = new CustomerFiscalInfo()

        String previousRpsNumber = customerFiscalInfo.rpsNumber
        String previousRpsSerie = customerFiscalInfo.rpsSerie

        customerFiscalInfo.customer = customer
        if (params.containsKey("municipalInscription")) customerFiscalInfo.municipalInscription = params.municipalInscription
        if (params.containsKey("simpleNational")) customerFiscalInfo.simpleNational = Boolean.valueOf(params.simpleNational)
        if (params.containsKey("rpsSerie")) customerFiscalInfo.rpsSerie = params.rpsSerie
        if (params.containsKey("rpsNumber")) customerFiscalInfo.rpsNumber = params.rpsNumber
        if (params.containsKey("loteNumber")) customerFiscalInfo.loteNumber = params.loteNumber
        if (params.containsKey("invoiceProviderUser")) customerFiscalInfo.invoiceProviderUser = params.invoiceProviderUser
        if (params.containsKey("specialTaxRegime")) customerFiscalInfo.specialTaxRegime = params.specialTaxRegime?.toString()
        if (params.containsKey("serviceListItem")) customerFiscalInfo.serviceListItem = params.serviceListItem?.toString()
        if (params.containsKey("nationalEconomicActivityCode")) customerFiscalInfo.nationalEconomicActivityCode = params.nationalEconomicActivityCode?.toString()
        if (params.containsKey("culturalProjectsPromoter")) customerFiscalInfo.culturalProjectsPromoter = Boolean.valueOf(params.culturalProjectsPromoter)
        if (params.containsKey("aedf")) customerFiscalInfo.aedf = params.aedf

        if (params.containsKey("nationalPortalTaxCalculationRegime")) {
            if (!InvoiceCityConfig.findNationalPortalTaxCalculationRegimeListFromCity(customer.city)) {
                throw new BusinessException(Utils.getMessageProperty("customerFiscalInfo.nationalPortalTaxCalculationRegime.disabled"))
            }

            customerFiscalInfo.nationalPortalTaxCalculationRegime = params.nationalPortalTaxCalculationRegime?.toString()
        }

        customerFiscalInfo.email = params.containsKey("email") ? params.email : customer.getFiscalInfoEmail()

        Boolean synchronizeRps = customerFiscalInfo.isDirty("rpsNumber") || !previousRpsNumber
        if (!synchronizeRps) synchronizeRps = customerFiscalInfo.isDirty("rpsSerie") || !previousRpsSerie

        customerFiscalInfo.save(flush: true)
        if (customerFiscalInfo.hasErrors()) return customerFiscalInfo

        Map customerInvoiceCredentials = CustomerInvoiceFiscalInfoBuilder.buildCustomerInvoiceCredentials(params)
        Map responseMap = synchronize(customerFiscalInfo, customerInvoiceCredentials, synchronizeRps)
        if (!responseMap.success) {
            DomainUtils.addError(customerFiscalInfo, responseMap.error)
            return customerFiscalInfo
        }

        return customerFiscalInfo
    }

    public Map synchronize(CustomerFiscalInfo customerFiscalInfo, Map customerInvoiceCredentials, Boolean synchronizeRps) {
        Map response = customerFiscalInfoSynchronizeService.synchronizeCustomerFiscalInfo(customerFiscalInfo.id, customerInvoiceCredentials, synchronizeRps)
        if (!response.success) return response

        customerFiscalInfo.externalId = response.externalId
        if (customerInvoiceCredentials.userPassword) customerFiscalInfo.userPasswordSent = true
        if (customerInvoiceCredentials.accessToken) customerFiscalInfo.accessTokenSent = true
        customerFiscalInfo.save(failOnError: true)

        Map synchronizeLogoResponse = synchronizeLogoIfNecessary(customerFiscalInfo.customer)
        if (synchronizeLogoResponse && !synchronizeLogoResponse.success) return synchronizeLogoResponse

        if (customerInvoiceCredentials.certificateFile) {
            response = customerFiscalInfoSynchronizeService.synchronizeDigitalCertificate(customerFiscalInfo.id, customerInvoiceCredentials.certificateFile, customerInvoiceCredentials.certificatePassword)
            if (!response.success) return response

            customerFiscalInfo.certificateName = customerInvoiceCredentials.certificateName
            customerFiscalInfo.save(failOnError: true)
        }

        return response
    }

    public Map synchronizeLogoIfNecessary(Customer customer) {
        Map customerFiscalInfoMap = CustomerFiscalInfo.query([columnList: ["id", "externalId"], customerId: customer.id]).get()
        if (!customerFiscalInfoMap?.externalId) return null

        CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.findLatestApproved(customer)
        if (!customerInvoiceConfig?.providerInfoOnTop || !customerInvoiceConfig?.logoFile) return null

        if (!customerInvoiceConfig.getInvoiceLogo()) customerInvoiceConfigService.saveInvoiceLogoPicture(customerInvoiceConfig)

        return customerFiscalInfoSynchronizeService.synchronizeLogo(customerFiscalInfoMap.id, customerInvoiceConfig.getInvoiceLogo())
    }

    public void updateInvoiceFromAuthorizationRequest(Invoice invoice, InvoiceAuthorizationRequest invoiceAuthorizationRequest) {
        if (invoice.isAsaasInvoice()) return

        if (!invoice.status.isAuthorized()) return

        if (invoiceAuthorizationRequest.invoiceRpsNumber != null)  {
            AuditLogListener.withoutAuditLog ({
                CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.query([customerId: invoice.customerId]).get()
                customerFiscalInfo.rpsNumber = invoiceAuthorizationRequest.invoiceRpsNumber
                customerFiscalInfo.save(failOnError: true, flush: true)
            })
        }
    }

    public Map validateCustomerFiscalUse(Customer customer) {
        if (!customer.city) {
            return [code: "invalid_city", message: "Cadastre a sua cidade para começar a emitir notas fiscais de serviço."]
        }

        if (!customer.isLegalPerson()) {
            return [code: "invalid_person_type", message: Utils.getMessageProperty("customerFiscalInfo.invalidPersonType")]
        }

        customerFeatureService.enableInvoiceFeatureIfNecessary(customer, customer.city)
        if (!CustomerFeature.isInvoiceEnabled(customer.id)) {
            return [code: "city", message: "Esta funcionalidade ainda não está disponível para ${customer.city.toString()}."]
        }

        return null
    }

    public Boolean isApprovedToSetupFiscalInfo(Customer customer) {
        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return false
        if (!customer.hasCommercialInfoApproved()) return false

        return true
    }

    public void updateOperationNature(Customer customer, String operationNature) {
		CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.query([customerId: customer.id]).get()
		customerFiscalInfo.operationNature = operationNature
		customerFiscalInfo.save(failOnError: true)

        Map response = customerFiscalInfoSynchronizeService.synchronizeCustomerFiscalInfo(customerFiscalInfo.id, [:], false)
        if (!response.success) throw new BusinessException("Erro ao sincronizar os dados fiscais do cliente")

        customerInteractionService.updateOperationNature(customer, operationNature)
    }

    public void updateExternalId(Customer customer, String externalId) {
        Boolean isExternalIdAlreadyUsed = CustomerFiscalInfo.query([exists: true, "customerId[ne]": customer.id, externalId: externalId]).get().asBoolean()
        if (isExternalIdAlreadyUsed) throw new BusinessException("Este ID já está sendo utilizado em outro cadastro no Asaas.")

        CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.query([customerId: customer.id]).get()
        if (!customerFiscalInfo) throw new RuntimeException("O cliente não possui dados fiscais cadastrados.")

        customerFiscalInfo.externalId = externalId
        customerFiscalInfo.save(failOnError: true)
    }

    public void delete(Long id) {
        CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.get(id)
        customerFiscalInfo.deleted = true
        customerFiscalInfo.save(failOnError: true, flush: true)
    }

    public void toggleUseNationalPortal(Customer customer) {
        CustomerFiscalInfo customerFiscalInfo = CustomerFiscalInfo.query([customerId: customer.id]).get()

        if (customerFiscalInfo) {
            customerFiscalInfo.rpsSerie = null
            customerFiscalInfo.rpsNumber = null
            customerFiscalInfo.nationalEconomicActivityCode = null
            customerFiscalInfo.specialTaxRegime = null
            customerFiscalInfo.serviceListItem = null
            customerFiscalInfo.invoiceProviderUser = null
            customerFiscalInfo.certificateName = null
            customerFiscalInfo.userPasswordSent = false
            customerFiscalInfo.accessTokenSent = false
            customerFiscalInfo.nationalPortalTaxCalculationRegime = null
            customerFiscalInfo.aedf = null
        } else {
            customerFiscalInfo = new CustomerFiscalInfo()
            customerFiscalInfo.customer = customer
            customerFiscalInfo.email = customer.getFiscalInfoEmail()
        }

        customerFiscalInfo.useNationalPortal = !customerFiscalInfo.useNationalPortal
        customerFiscalInfo.save(failOnError: true)

        customerProductService.onToggleUseNationalPortal(customer)
        if (customerFiscalInfo.useNationalPortal) customerFiscalConfigService.onUseNationalPortal(customer)
    }
}
