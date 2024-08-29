package com.asaas.service.integration.cerc

import com.asaas.domain.auditlog.AuditLogEvent
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PrepareCercCompanyService {

    def cercCompanyService

    public void updateCompanies(Date startDate) {
        for (Long customerId : listRecentlyUpdatedCustomerId(startDate)) {
            Utils.withNewTransactionAndRollbackOnError({
                processRecentlyUpdatedCustomer(customerId, startDate)
            }, [logErrorMessage: "PrepareCercCompanyService.updateCompanies >> Falha ao atualizar estabelecimento comercial com o cliente [customerId: ${customerId}]"])
        }
    }

    private List<Long> listRecentlyUpdatedCustomerId(Date startDate) {
        Map searchCercCompany = [:]
        searchCercCompany.column = "customer.id"
        searchCercCompany."customerLastUpdated[ge]" = startDate
        searchCercCompany.withLastBuildInfoUpdated = true
        searchCercCompany.disableSort = true

        return CercCompany.query(searchCercCompany).list()
    }

    private void processRecentlyUpdatedCustomer(Long customerId, Date startDate) {
        List<CercCompany> cercCompanyList = CercCompany.query([customerId: customerId]).list()
        if (!cercCompanyList) return

        final List<String> propertyNameToTriggerUpdateList = [
            "company", "name", "tradingName", "cpfCnpj",
            "address", "addressNumber", "complement", "postalCode", "province",
            "email", "mobilePhone", "phone",
            "status", "deleted"
        ]

        Map searchAuditLog = [:]
        searchAuditLog.column = "persistedObjectId"
        searchAuditLog.className = Customer.simpleName
        searchAuditLog.persistedObjectId = customerId
        searchAuditLog.eventName = "UPDATE"
        searchAuditLog."dateCreated[ge]" = startDate
        searchAuditLog."propertyName[in]" = propertyNameToTriggerUpdateList

        String persistedObjectIdFromCustomer = AuditLogEvent.query(searchAuditLog).get()

        for (CercCompany cercCompany in cercCompanyList) {
            if (!persistedObjectIdFromCustomer) {
                cercCompanyService.registerLastBuildInfoUpdate(cercCompany)
                continue
            }

            cercCompanyService.updateIfNecessary(cercCompany)
        }
    }
}
