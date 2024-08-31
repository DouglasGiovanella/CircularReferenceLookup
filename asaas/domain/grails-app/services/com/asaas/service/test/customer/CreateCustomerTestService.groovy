package com.asaas.service.test.customer

import com.asaas.customer.adapter.CreateBootStrapTestCustomerAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.lead.LeadData
import com.asaas.domain.lead.LeadUtm
import com.asaas.domain.lead.LeadUtmSequence
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.UnitTestUtils

import grails.transaction.Transactional

@Transactional
class CreateCustomerTestService {

    def createCustomerService

    public Boolean runTests() {
        try {
            transactionStatus.setRollbackOnly()

            executeCustomerCreationTests()
            return true
        } catch (Exception exception) {
            String warningMessage = """
                ******************************
                           ATENCAO
                ******************************
                *
                Chame o time responsavel pela feature que quebrou e ajuste seu ambiente local.
            """.stripIndent()

            AsaasLogger.error("CreateCustomerTestService.runTests >> Erro na criação de Customer.\n \n${warningMessage}\n \n", exception)
            return false
        }
    }

    private void executeCustomerCreationTests() {
        UnitTestUtils.bindDefaultMockWebRequest()
        String currentTest
        try {
            currentTest = "Criação do Customer Adapter"
            CreateBootStrapTestCustomerAdapter customerAdapter = CreateBootStrapTestCustomerAdapter.buildForTest()

            currentTest = "Criação de Customer"
            Customer createdCustomer = saveCustomerTest(customerAdapter)

            currentTest = "Validação dos campos do Customer"
            validateCustomerFields(customerAdapter, createdCustomer)

            currentTest = "Validação da origem de cadastro do Customer"
            validateCustomerSignupOrigin(customerAdapter, createdCustomer)

            currentTest = "Validação do Lead do Customer"
            validateCustomerLead(customerAdapter, createdCustomer.id)
        } catch (Exception exception) {
            throw new RuntimeException("Falha no teste de ${currentTest}", exception)
        }
    }

    private Customer saveCustomerTest(CreateBootStrapTestCustomerAdapter customerAdapter) {
        Customer createdCustomer = createCustomerService.save(customerAdapter)

        if (createdCustomer.hasErrors()) {
            throw new BusinessException(DomainUtils.getValidationMessages(createdCustomer.getErrors()).first())
        }

        return createdCustomer
    }

    private void validateCustomerFields(CreateBootStrapTestCustomerAdapter adapter, Customer customer) {
        if (adapter.email != customer.email) {
            throw new RuntimeException("Email inválido: esperado ${adapter.email}, encontrado ${customer.email}")
        }

        if (adapter.name != customer.name) {
            throw new RuntimeException("Name inválido: esperado ${adapter.name}, encontrado ${customer.name}")
        }

        if (adapter.mobilePhone != customer.mobilePhone) {
            throw new RuntimeException("Mobile phone inválido: esperado ${adapter.mobilePhone}, encontrado ${customer.mobilePhone}")
        }

        if (adapter.registerIp != customer.registerIp) {
            throw new RuntimeException("Register IP inválido: esperado ${adapter.registerIp}, encontrado ${customer.registerIp}")
        }

        if (adapter.cpfCnpj != customer.cpfCnpj) {
            throw new RuntimeException("CPF/CNPJ inválido: esperado ${adapter.cpfCnpj}, encontrado ${customer.cpfCnpj}")
        }

        if (adapter.personType != customer.personType) {
            throw new RuntimeException("Person type inválido: esperado ${adapter.personType}, encontrado ${customer.personType}")
        }

        if (adapter.companyType != customer.companyType) {
            throw new RuntimeException("Company type inválido: esperado ${adapter.companyType}, encontrado ${customer.companyType}")
        }

        if (adapter.address != customer.address) {
            throw new RuntimeException("Address inválido: esperado ${adapter.address}, encontrado ${customer.address}")
        }

        if (adapter.addressNumber != customer.addressNumber) {
            throw new RuntimeException("Address number inválido: esperado ${adapter.addressNumber}, encontrado ${customer.addressNumber}")
        }

        if (adapter.province != customer.province) {
            throw new RuntimeException("Province inválido: esperado ${adapter.province}, encontrado ${customer.province}")
        }

        if (adapter.postalCode != customer.postalCode) {
            throw new RuntimeException("Postal code inválido: esperado ${adapter.postalCode}, encontrado ${customer.postalCode}")
        }

        if (adapter.city != customer.city?.id) {
            throw new RuntimeException("City inválido: esperado ${adapter.city}, encontrado ${customer.city?.id}")
        }
    }

    private void validateCustomerSignupOrigin(CreateBootStrapTestCustomerAdapter adapter, Customer customer) {
        Map customerSignUpOriginData = CustomerSignUpOrigin.query([columnList: ['originPlatform', 'originChannel'], customer: customer]).get()

        if (adapter.signUpOrigin.originPlatform != customerSignUpOriginData.originPlatform) {
            throw new RuntimeException("Origin platform inválido: esperado ${adapter.signUpOrigin.originPlatform}, encontrado ${customerSignUpOriginData.originPlatform}")
        }

        if (adapter.signUpOrigin.originChannel != customerSignUpOriginData.originChannel) {
            throw new RuntimeException("Origin channel inválido: esperado ${adapter.signUpOrigin.originChannel}, encontrado ${customerSignUpOriginData.originChannel}")
        }
    }

    private void validateCustomerLead(CreateBootStrapTestCustomerAdapter adapter, Long customerId) {
        LeadData leadData = LeadData.query([customerId: customerId]).get()
        validateLeadData(leadData, adapter)

        List<LeadUtm> leadUtmList = LeadUtm.query([lead: leadData, customerId: customerId]).list()
        validateLeadUtm(leadUtmList, adapter)
    }

    private void validateLeadData(LeadData leadData, CreateBootStrapTestCustomerAdapter adapter) {
        if (adapter.email != leadData.email) {
            throw new RuntimeException("Email inválido: esperado ${adapter.email}, encontrado ${leadData.email}")
        }

        if (adapter.utmSource != leadData.utmSource) {
            throw new RuntimeException("UTM source inválido: esperado ${adapter.utmSource}, encontrado ${leadData.utmSource}")
        }

        if (adapter.utmMedium != leadData.utmMedium) {
            throw new RuntimeException("UTM medium inválido: esperado ${adapter.utmMedium}, encontrado ${leadData.utmMedium}")
        }

        if (adapter.utmCampaign != leadData.utmCampaign) {
            throw new RuntimeException("UTM campaign inválido: esperado ${adapter.utmCampaign}, encontrado ${leadData.utmCampaign}")
        }

        if (adapter.utmTerm != leadData.utmTerm) {
            throw new RuntimeException("UTM term inválido: esperado ${adapter.utmTerm}, encontrado ${leadData.utmTerm}")
        }

        if (adapter.utmContent != leadData.utmContent) {
            throw new RuntimeException("UTM content inválido: esperado ${adapter.utmContent}, encontrado ${leadData.utmContent}")
        }

        if (adapter.referrer != leadData.referer) {
            throw new RuntimeException("referrer inválido: esperado ${adapter.referrer}, encontrado ${leadData.referer}")
        }
    }

    private void validateLeadUtm(List<LeadUtm> leadUtmList, CreateBootStrapTestCustomerAdapter adapter) {
        LeadUtm firstLeadUtm = leadUtmList.find { it.sequence == LeadUtmSequence.FIRST }
        LeadUtm lastLeadUtm = leadUtmList.find { it.sequence == LeadUtmSequence.LAST }

        if (!firstLeadUtm || !lastLeadUtm) {
            throw new RuntimeException("First ou last LeadUtm não encontrados")
        }

        if (leadUtmList.size() != 2) {
            throw new RuntimeException("Quantidade de LeadUtm inválida: esperado 2, encontrado ${leadUtmList.size()}")
        }

        if (adapter.utmSource != firstLeadUtm.source) {
            throw new RuntimeException("First UTM source inválido: esperado ${adapter.utmSource}, encontrado ${firstLeadUtm.source}")
        }

        if (adapter.utmMedium != firstLeadUtm.medium) {
            throw new RuntimeException("First UTM medium inválido: esperado ${adapter.utmMedium}, encontrado ${firstLeadUtm.medium}")
        }

        if (adapter.utmCampaign != firstLeadUtm.campaign) {
            throw new RuntimeException("First UTM campaign inválido: esperado ${adapter.utmCampaign}, encontrado ${firstLeadUtm.campaign}")
        }

        if (adapter.utmTerm != firstLeadUtm.term) {
            throw new RuntimeException("First UTM term inválido: esperado ${adapter.utmTerm}, encontrado ${firstLeadUtm.term}")
        }

        if (adapter.utmContent != firstLeadUtm.content) {
            throw new RuntimeException("First UTM content inválido: esperado ${adapter.utmContent}, encontrado ${firstLeadUtm.content}")
        }

        if (adapter.referrer != firstLeadUtm.referrer) {
            throw new RuntimeException("First referrer inválido: esperado ${adapter.referrer}, encontrado ${firstLeadUtm.referrer}")
        }

        if (adapter.lastUtmSource != lastLeadUtm.source) {
            throw new RuntimeException("Last UTM source inválido: esperado ${adapter.lastUtmSource}, encontrado ${lastLeadUtm.source}")
        }

        if (adapter.lastUtmMedium != lastLeadUtm.medium) {
            throw new RuntimeException("Last UTM medium inválido: esperado ${adapter.lastUtmMedium}, encontrado ${lastLeadUtm.medium}")
        }

        if (adapter.lastUtmCampaign != lastLeadUtm.campaign) {
            throw new RuntimeException("Last UTM campaign inválido: esperado ${adapter.lastUtmCampaign}, encontrado ${lastLeadUtm.campaign}")
        }

        if (adapter.lastUtmTerm != lastLeadUtm.term) {
            throw new RuntimeException("Last UTM term inválido: esperado ${adapter.lastUtmTerm}, encontrado ${lastLeadUtm.term}")
        }

        if (adapter.lastUtmContent != lastLeadUtm.content) {
            throw new RuntimeException("Last UTM content inválido: esperado ${adapter.lastUtmContent}, encontrado ${lastLeadUtm.content}")
        }

        if (adapter.lastReferrer != lastLeadUtm.referrer) {
            throw new RuntimeException("Last referrer inválido: esperado ${adapter.lastReferrer}, encontrado ${lastLeadUtm.referrer}")
        }

        if (adapter.lastGclid != lastLeadUtm.gclid) {
            throw new RuntimeException("Last Gclid inválido: esperado ${adapter.lastGclid}, encontrado ${lastLeadUtm.gclid}")
        }

        if (adapter.lastFbclid != lastLeadUtm.fbclid) {
            throw new RuntimeException("Last Fbclid inválido: esperado ${adapter.lastFbclid}, encontrado ${lastLeadUtm.fbclid}")
        }
    }
}
