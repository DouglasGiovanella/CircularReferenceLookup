package com.asaas.service.asaasInternalAccountBalanceZeroing

import com.asaas.debit.DebitType
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasInternalAccountBalanceZeroingService {

    def debitService
    def grailsApplication

    public void saveDebit() {
        for (Long asaasInternalAccountId : grailsApplication.config.asaas.customer.idList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer asaasCustomer = Customer.read(asaasInternalAccountId)

                BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(asaasCustomer)
                if (currentBalance <= 0) return

                String debitDescription = "Débito referente ao zeramento de saldo de conta interna Asaas"
                debitService.save(asaasCustomer, currentBalance, DebitType.ASAAS_INTERNAL_ACCOUNT_BALANCE_ZEROING, debitDescription, null)
            }, [logErrorMessage: "AsaasInternalAccountBalanceZeroingService.saveDebit >> Erro ao zerar saldo da conta interna Asaas id [${asaasInternalAccountId}]"])
        }
    }
}
