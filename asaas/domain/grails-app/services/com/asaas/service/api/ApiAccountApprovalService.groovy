package com.asaas.service.api

import com.asaas.accountnumber.AccountNumberHermesSyncOperationType
import com.asaas.accountnumber.AccountNumberHermesSyncStatus
import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.accountnumber.hermessync.AccountNumberHermesSync
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class ApiAccountApprovalService extends ApiBaseService {

    def bankAccountInfoUpdateRequestService
    def customerDocumentProxyService
    def customerRegisterStatusService
    def customerGeneralAnalysisService
    def customerProofOfLifeService
    def synchronizeHermesAccountNumberService

    public Customer approve(Customer createdCustomer, Map fields) {
        if (fields.bankAccount) {
            BaseBankAccount bankAccountInfo = bankAccountInfoUpdateRequestService.save(createdCustomer.id, fields.bankAccount)
            if (bankAccountInfo.hasErrors()) {
                throw new BusinessException(DomainUtils.getValidationMessages(bankAccountInfo.getErrors()).first())
            }
        }

        customerRegisterStatusService.updateBankAccountInfoStatus(createdCustomer, Status.APPROVED)

        Boolean hasCustomDocumentNotSent = customerDocumentProxyService.exists(createdCustomer.id, [status: CustomerDocumentStatus.NOT_SENT, type: CustomerDocumentType.CUSTOM])
        if (!hasCustomDocumentNotSent) customerRegisterStatusService.updateDocumentStatus(createdCustomer, Status.APPROVED)

        customerGeneralAnalysisService.approveAutomaticallyIfPossible(createdCustomer)

        customerProofOfLifeService.autoApproveByAccountOwner(createdCustomer)

        synchronizeHermesAccountNumber(createdCustomer.accountNumber)

        return createdCustomer
    }

    private void synchronizeHermesAccountNumber(AccountNumber accountNumber) {
        AccountNumberHermesSync accountNumberHermesSync = AccountNumberHermesSync.query([accountNumber: accountNumber, status: AccountNumberHermesSyncStatus.AWAITING_SYNCHRONIZATION, operationType: AccountNumberHermesSyncOperationType.CREATE]).get()
        if (!accountNumberHermesSync) {
            accountNumberHermesSync = synchronizeHermesAccountNumberService.create(accountNumber.customer)

            if (accountNumberHermesSync.hasErrors()) {
                AsaasLogger.warn("ApiAccountApprovalService.synchronizeHermesAccountNumber() -> Falha ao salvar sincronização do número de conta: ${DomainUtils.getValidationMessages(accountNumberHermesSync.errors)}")
                return
            }
        }

        final Integer createTimeout = 3000
        synchronizeHermesAccountNumberService.synchronizeAccountNumber(accountNumberHermesSync, createTimeout)
    }
}
