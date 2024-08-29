package com.asaas.service.companypartnerquery

import com.asaas.companypartner.adapter.CompanyPartnerQueryAdapter
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class CompanyPartnerQueryService {

    def receivableAnticipationValidationService

    public void addNewAndDeleteOldInvalid(String cnpj, List<CompanyPartnerQueryAdapter> companyPartnerAdapterList) {
        Boolean hasCompanyPartnerChanged = hasCompanyPartnerChanged(cnpj, companyPartnerAdapterList)
        if (!hasCompanyPartnerChanged) return

        deleteAllCompanyPartners(cnpj)

        List<CompanyPartnerQuery> partnerList = companyPartnerAdapterList.collect { CompanyPartnerQueryAdapter partner ->
            CompanyPartnerQuery companyPartnerQuery = save(partner)
            return companyPartnerQuery
        }

        if (partnerList) {
            List<Customer> customerList = Customer.query([cpfCnpj: cnpj]).list()
            for (Customer customer : customerList) {
                receivableAnticipationValidationService.onCustomerChange(customer)
            }
        }
    }

    public CompanyPartnerQuery findCompanyPartnerWithSingleAdminIfPossible(Customer customer) {
        if (!customer.isLegalPerson()) return null

        List<CompanyPartnerQuery> companyPartnerQueryList = CompanyPartnerQuery.query([companyCnpj: customer.cpfCnpj, isAdmin: true]).list(max: 2, readOnly: true)
        if (!companyPartnerQueryList) return null

        final Integer maxCompanyPartnerQueryListSizeEnabledToUseInfo = 1
        if (companyPartnerQueryList.size() > maxCompanyPartnerQueryListSizeEnabledToUseInfo) {
            AsaasLogger.warn("CompanyPartnerQueryService.findCompanyPartnerWithSingleAdminIfPossible >> Customer [${customer.id}] do tipo [${customer.companyType}] possui mais de 1 sócio.")
            return null
        }

        return companyPartnerQueryList.first()
    }

    private Boolean hasCompanyPartnerChanged(String cnpj, List<CompanyPartnerQueryAdapter> companyPartnerAdapterList) {
        if (!companyPartnerAdapterList) return false

        List<String> newAdminCompanyPartnerCpfList = companyPartnerAdapterList.findAll { it.isAdmin }.collect { it.cpf }.sort()

        List<String> currentAdminCompanyPartnerCpfList = CompanyPartnerQuery.query([column: "cpf", companyCnpj: cnpj, isAdmin: true]).list().sort()

        Boolean hasCompanyPartnerListChanged = newAdminCompanyPartnerCpfList != currentAdminCompanyPartnerCpfList

        return hasCompanyPartnerListChanged
    }

    private void deleteAllCompanyPartners(String companyCnpj) {
        CompanyPartnerQuery.executeUpdate("update CompanyPartnerQuery set deleted = true, version = version + 1, lastUpdated = :now where companyCnpj = :companyCnpj and deleted = false", [companyCnpj: companyCnpj, now: new Date()])
    }

    private CompanyPartnerQuery save(CompanyPartnerQueryAdapter companyPartnerAdapter) {
        CompanyPartnerQuery partner = new CompanyPartnerQuery()
        partner.name = companyPartnerAdapter.name
        partner.cpf = companyPartnerAdapter.cpf
        partner.companyCnpj = companyPartnerAdapter.companyCnpj
        partner.entryDate = companyPartnerAdapter.entryDate
        partner.departureDate = companyPartnerAdapter.departureDate
        partner.participationPercentage = companyPartnerAdapter.participationPercentage
        partner.isAdmin = companyPartnerAdapter.isAdmin
        partner.position = companyPartnerAdapter.position
        partner.save(flush: true, failOnError: true)

        return partner
    }
}
