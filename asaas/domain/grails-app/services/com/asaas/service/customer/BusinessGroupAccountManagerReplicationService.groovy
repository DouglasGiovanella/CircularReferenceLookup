package com.asaas.service.customer

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.businessgroup.BusinessGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessGroup
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BusinessGroupAccountManagerReplicationService {

    def asyncActionService
    def customerAccountManagerService
    def customerAdminService

    private final Integer MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION = 300

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingBusinessGroupAccountManagerToReplicate()) {
            Utils.withNewTransactionAndRollbackOnError({
                BusinessGroup businessGroup = BusinessGroup.get(Utils.toLong(asyncActionData.businessGroupId))
                AccountManager accountManager = AccountManager.get(Utils.toLong(asyncActionData.accountManagerId))

                applyReplicationForCustomersWithoutAccountOwner(businessGroup, accountManager)
                applyReplicationForCustomersWithAccountOwner(businessGroup, accountManager)

                if (!hasCustomerToReplicate(businessGroup, accountManager)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "BusinessGroupAccountManagerReplicationService.start >> Erro no processamento da replicação dos gerentes de contas dos clientes vinculados ao grupo empresarial. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyReplicationForCustomersWithoutAccountOwner(BusinessGroup businessGroup, AccountManager accountManager) {
        Map search = [column: "customer.id", businessGroupId: businessGroup.id, "customerAccountManager[ne]": accountManager, "accountOwner[isNull]": true]
        List<Long> businessGroupCustomerIdList = CustomerBusinessGroup.query(search).list(max: MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION / 2)

        applyReplication(businessGroupCustomerIdList, accountManager.id)
    }

    private void applyReplicationForCustomersWithAccountOwner(BusinessGroup businessGroup, AccountManager accountManager) {
        Map search = [column: "customer", businessGroupId: businessGroup.id, "customerAccountManager[ne]": accountManager, "accountOwner[isNotNull]": true]

        List<Customer> customerListWithoutInheritance = CustomerBusinessGroup.query(search).list(max: MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION / 2).findAll {
            !customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(it.accountOwner)
        }
        List<Long> customerWithoutInheritanceIdList = customerListWithoutInheritance.collect{ it.id }

        applyReplication(customerWithoutInheritanceIdList, accountManager.id)
    }

    private void applyReplication(List<Long> businessGroupCustomerIdList, Long accountManagerId) {
        Utils.forEachWithFlushSession(businessGroupCustomerIdList, 50, { Long customerId ->
            Customer customer = Customer.get(customerId)
            AccountManager accountManager = AccountManager.get(accountManagerId)

            if (customer.accountManager == accountManager) return

            AsaasLogger.info("Alterando gerente da conta vinculada ${customerId} de [${customer.accountManager.id}] para [${accountManager.id}]")
            customerAccountManagerService.save(accountManager, customer, AccountManagerChangeOrigin.BUSINESS_GROUP_MANAGER_REPLICATION, true)
        })
    }

    private boolean hasCustomerToReplicate(BusinessGroup businessGroup, AccountManager accountManager) {
        Map search = [column: "customer", businessGroupId: businessGroup.id, "customerAccountManager[ne]": accountManager]

        List<Customer> customerListWithoutAccountOwner = CustomerBusinessGroup.query(search + ["accountOwner[isNull]": true]).list()

        List<Customer> customerListWithoutInheritance = CustomerBusinessGroup.query(search + ["accountOwner[isNotNull]": true]).list().findAll {
            !customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(it.accountOwner)
        }

        return (customerListWithoutAccountOwner + customerListWithoutInheritance).asBoolean()
    }
}
