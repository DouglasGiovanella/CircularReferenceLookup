package com.asaas.service.customer

import com.asaas.customer.CustomerSegment
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountSegmentMigrationService {

    def asyncActionService
    def customerSegmentService

    private final Integer MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION = 2000

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountSegmentAndAccountManagerMigration()) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer accountOwner = Customer.get(Utils.toLong(asyncActionData.accountOwnerId))
                CustomerSegment customerSegment = CustomerSegment.convert(asyncActionData.customerSegment)

                migrate(accountOwner, customerSegment)

                if (!hasChildAccountToMigrate(accountOwner, customerSegment)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountSegmentMigrationService.start >> Erro no processamento da migração dos segmentos de contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void migrate(Customer accountOwner, CustomerSegment customerSegment) {
        List<Long> childAccountIdList = Customer.childAccounts(accountOwner, ["column": "id", "segment[ne]": customerSegment]).list(max: MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION)

        for (Long childAccountId in childAccountIdList) {
            Customer childAccount = Customer.get(childAccountId)

            AsaasLogger.info("Alterando segmento da conta filha ${childAccountId}.")
            customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(childAccount, customerSegment, true)
        }
    }

    private Boolean hasChildAccountToMigrate(Customer accountOwner, CustomerSegment customerSegment) {
        return Customer.childAccounts(accountOwner, [exists: true, "segment[ne]": customerSegment]).get().asBoolean()
    }
}
