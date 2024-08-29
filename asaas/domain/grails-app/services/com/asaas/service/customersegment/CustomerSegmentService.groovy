package com.asaas.service.customersegment

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.customer.CustomerSegment
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.businessgroup.BusinessGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessGroup
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class CustomerSegmentService {

    final static BigDecimal BUSINESS_MIN_PAYMENT_SUM = 5000

    def asaasSegmentioService
    def asyncActionService
    def beamerService
    def customerAccountManagerService
    def customerAdminService
    def customerInteractionService
    def customerSegmentHistoryService
    def hubspotContactService

    public void changeCustomerSegmentAndUpdateAccountManager(Customer customer, CustomerSegment customerSegment, Boolean isBatchMigration) {
        validateChangeSegment(customer, customerSegment)

        CustomerSegment oldCustomerSegment = customer.segment

        AsaasLogger.info("Atualizando segmento do cliente ${customer.id} de ${oldCustomerSegment} para ${customerSegment}.")
        customer.segment = customerSegment
        customer.save(failOnError: true)

        AccountManager accountManager = customerAccountManagerService.find(customer, null)
        customerAccountManagerService.save(accountManager, customer, AccountManagerChangeOrigin.SEGMENT_CHANGE, isBatchMigration)

        customerSegmentHistoryService.save(customer, customer.segment)

        asaasSegmentioService.identify(customer.id, ["asaasSegment": customerSegment.toString()])

        customerInteractionService.saveCustomerSegmentChange(customer, oldCustomerSegment, customerSegment)

        saveChildAccountSegmentAndAccountManagerMigrationIfPossible(customer, customerSegment)

        hubspotContactService.saveCommercialInfoUpdate(customer)

        beamerService.updateUserInformation(customer.id, [:])
    }

    public CustomerSegment getFromAccountOwnerOrInitialSegment(Customer customer) {
        CustomerSegment customerSegment = getFromAccountOwner(customer)

        if (customerSegment) return customerSegment

        return Customer.INITIAL_SEGMENT
    }

    public CustomerSegment getFromAccountOwner(Customer customer) {
        if (!customer.accountOwner) return null

        if (!customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer.accountOwner)) return null

        return customer.accountOwner.segment
    }

    public Map buildDefaultSearchToUpgradeOrDowngradeSegment() {
        Map search = [:]
        search.column = "customer.id"
        search.disableSort = true
        search.date = CustomDateUtils.getFirstDayOfLastMonth().clearTime()
        search."customerBusinessGroup[notExists]" = true

        return search
    }

    private void saveChildAccountSegmentAndAccountManagerMigrationIfPossible(Customer customer, CustomerSegment customerSegment) {
        Boolean hasChildAccountToMigrate = Customer.childAccounts(customer, [exists: true, "segment[ne]": customerSegment]).get().asBoolean()
        if (!hasChildAccountToMigrate) return

        Boolean hasActiveAccountOwnerManagerAndSegmentInheritance = customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer)
        if (!hasActiveAccountOwnerManagerAndSegmentInheritance) return

        asyncActionService.saveChildAccountSegmentAndAccountManagerMigration([accountOwnerId: customer.id, customerSegment: customerSegment])
    }

    private void validateChangeSegment(Customer customer, CustomerSegment newSegment) {
        if (customer.segment == newSegment) throw new BusinessException("O cliente já pertence a esse segmento.")

        BusinessGroup businessGroup = CustomerBusinessGroup.query([customerId: customer.id, column: "businessGroup"]).get()

        Boolean hasActiveAccountOwnerManagerAndSegmentInheritance = customer.accountOwner && customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer.accountOwner)

        if (!hasActiveAccountOwnerManagerAndSegmentInheritance && businessGroup && newSegment != businessGroup.segment) throw new BusinessException("O cliente precisa ter o mesmo segmento do grupo empresarial.")

        if (hasActiveAccountOwnerManagerAndSegmentInheritance && customer.accountOwner.segment != newSegment) throw new BusinessException("O cliente precisa ter o mesmo segmento da sua conta pai.")
    }
}
