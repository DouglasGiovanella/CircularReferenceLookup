package com.asaas.service.customer

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AccountOwnerManagerInheritanceService {

    def asyncActionService
    def customerAccountManagerService

    private final Integer MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION = 300

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingAccountOwnerManagerInheritance()) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer accountOwner = Customer.get(Utils.toLong(asyncActionData.accountOwnerId))
                AccountManager accountManager = AccountManager.get(Utils.toLong(asyncActionData.accountManagerId))

                applyInheritance(accountOwner, accountManager)

                if (!hasChildAccountToInherit(accountOwner, accountManager)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AccountOwnerManagerInheritanceService.start >> Erro no processamento da herança dos gerentes de contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyInheritance(Customer accountOwner, AccountManager accountManager) {
        List<Long> childAccountIdList = Customer.childAccounts(accountOwner, ["column": "id", "accountManager[ne]": accountManager]).list(max: MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION)

        Utils.forEachWithFlushSession(childAccountIdList, 50, { Long childAccountId ->
            Customer childAccount = Customer.get(childAccountId)

            customerAccountManagerService.save(accountManager, childAccount, AccountManagerChangeOrigin.ACCOUNT_OWNER_MANAGER_INHERITANCE, true)
        })
    }

    private Boolean hasChildAccountToInherit(Customer accountOwner, AccountManager accountManager) {
        return Customer.childAccounts(accountOwner, [exists: true, "accountManager[ne]": accountManager]).get().asBoolean()
    }
}
