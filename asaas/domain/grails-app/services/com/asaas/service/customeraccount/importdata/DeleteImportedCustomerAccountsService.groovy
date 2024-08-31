package com.asaas.service.customeraccount.importdata

import com.asaas.asyncaction.AsyncActionType
import com.asaas.base.importdata.ImportStatus
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customeraccount.importdata.CustomerAccountImportItem
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class DeleteImportedCustomerAccountsService {

    def asyncActionService
    def customerAccountService
    def externalIdentifierService

    public void start() {
        Map asyncActionData = asyncActionService.getPending(AsyncActionType.DELETE_IMPORTED_CUSTOMER_ACCOUNTS)
        if (!asyncActionData) return

        try {
            List<Long> importedCustomerAccounts = listImportedCustomerAccountsToDelete(asyncActionData.importGroupId)

            if (!importedCustomerAccounts) {
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            delete(importedCustomerAccounts, asyncActionData.customerId)
        } catch (Exception exception) {
            asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
        }
    }

    private void delete(List<Long> importedCustomerAccounts, Long customerId) {
        final Integer flushEvery = 100

        Utils.forEachWithFlushSession(importedCustomerAccounts, flushEvery, { Long importedItemId ->
            try {
                CustomerAccount customerAccountToDelete = CustomerAccount.read(importedItemId)
                customerAccountService.delete(importedItemId, customerId, false)
                externalIdentifierService.delete(customerAccountToDelete, ExternalApplication.OTHER, ExternalResource.IMPORT)
            } catch (Exception exception) {
                AsaasLogger.error("DeleteImportedCustomerAccountsService.delete() >> Erro na remoção do pagador ${importedItemId} do customer ${customerId}}", exception)
                transactionStatus.setRollbackOnly()
                throw exception
            }
        })
    }

    private List<Long> listImportedCustomerAccountsToDelete(Long importGroupId) {
        final Integer maxImportedItemsPerList = 500
        List<CustomerAccount> importedCustomerAccountList = CustomerAccountImportItem.query([column: "customerAccount", groupId: importGroupId, status: ImportStatus.IMPORTED]).list(maxImportedItemsPerList)
        List<Long> accountToDelete = []

        for (CustomerAccount customerAccount : importedCustomerAccountList) {
            if (customerAccount.canBeDeleted()) {
                accountToDelete.add(customerAccount.id)
            } else {
                AsaasLogger.warn("DeleteImportedCustomerAccountsService.listImportedCustomerAccountsToDelete() >> Não é possível adicionar o pagador ${customerAccount.id} do customer ${customerAccount.provider.id} para remoção em lote. Motivo: ${customerAccount.editDisabledReason}")
            }
        }

        return accountToDelete
    }
}
